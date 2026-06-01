package so.kontext.kit.omsdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import so.kontext.kit.R
import kotlin.coroutines.resume

/**
 * Public manager-protocol for OMID session creation. Exposed so consumer
 * SDKs can inject mocks in tests without instantiating the binary OMID
 * SDK. Mirrors iOS's `OMManaging`.
 */
public interface OmManaging {
    public fun activate(context: Context): Boolean
    public suspend fun createSession(
        webView: WebView,
        url: String?,
        creativeType: OmCreativeType,
    ): OmSession?
}

/**
 * Manages the OMID native-SDK lifecycle and creates per-WebView OM
 * sessions. Caller-owned design: `createSession(...)` returns an
 * [OmSession] to the caller, which is responsible for calling
 * `retire()` then `finish()` at teardown. Mirrors iOS `OMManager`.
 *
 * The OMID library is accessed entirely via reflection so the SDK
 * compiles even without the OMID AAR; if it's not on the classpath,
 * `activate(context)` returns `false` and `createSession(...)` returns `null`.
 *
 * Class names + APIs mirror the IAB OMID Android library at
 * `com.iab.omid.library.kontextso.*`. Top-level entry points
 * (`Omid`) sit at the package root; everything session-related
 * (`Partner`, `AdSession`, `AdSessionContext`, etc.) lives under
 * `.adsession`. The reflection here must match those paths exactly.
 */
public class OmManager(partner: OmPartner) : OmManaging {

    private val partner: OmPartner = partner
    private var cachedPartner: Any? = null

    /**
     * Activates the OMID SDK. Idempotent — subsequent calls return the
     * already-active state. Requires a Context to register the SDK with
     * the host application.
     *
     * Activation state is process-global ([globallyActivated]) because the
     * underlying OMID SDK is a process-wide singleton: once it is up, every
     * [OmManager] (one per [so.kontext.kit] consumer Session) shares it.
     *
     * The reflective `Omid.activate()` call is marshalled onto the main
     * thread: OMID creates `Handler`s during activation and its viewability
     * `TreeWalker` runs on the main `Looper`. If a publisher calls
     * `createSession()` (→ Session init → `activate()`) from a background
     * coroutine, activating off-main throws "Can't create handler … that has
     * not called Looper.prepare()", which leaves OMID half-initialized — and
     * because OMID flips its internal `isActive` flag *before* that throw, its
     * own guard then no-ops every retry. A later session would sail through
     * that guard, arm the half-initialized `TreeWalker`, and crash on the main
     * thread dereferencing a never-initialized `WeakReference`. Running on the
     * main thread avoids the throw entirely; [permanentlyUnavailable] is the
     * defense-in-depth backstop if activation ever fails for any other reason.
     */
    public override fun activate(context: Context): Boolean {
        if (!permanentlyUnavailable && !globallyActivated) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                activateGlobalOmid(context)
            } else {
                // Activation is fire-and-forget on the main thread (never
                // blocking the caller): blocking the caller while posting to
                // main deadlocks if the caller is itself holding up the main
                // thread (e.g. a `runBlocking` on main). Sessions start OMID
                // much later — after preload + ad-done, also on the main
                // thread — so this posted activation always completes first.
                Handler(Looper.getMainLooper()).post {
                    if (!permanentlyUnavailable && !globallyActivated) {
                        activateGlobalOmid(context)
                    }
                    ensurePartner()
                }
            }
        }
        ensurePartner()
        return globallyActivated
    }

    /**
     * Performs the one-time, process-wide OMID activation. Always invoked on
     * the main thread (inline when the caller is on main, otherwise from the
     * posted runnable in [activate]) because `Omid.activate()` creates
     * `Handler`s and the viewability `TreeWalker` runs on the main `Looper`.
     * On success sets [globallyActivated]; on any failure sets
     * [permanentlyUnavailable] so no session is ever created this process and
     * the half-initialized `TreeWalker` can never be armed. `Method.invoke`
     * wraps a thrown target exception in `InvocationTargetException`, which is
     * a [ReflectiveOperationException] — so this single catch covers both
     * missing-AAR and activation-time failures.
     */
    private fun activateGlobalOmid(context: Context) {
        try {
            val omidClass = Class.forName("com.iab.omid.library.kontextso.Omid")
            omidClass.getMethod("activate", Context::class.java).invoke(null, context)
            if (omidClass.getMethod("isActive").invoke(null) == true) {
                globallyActivated = true
            } else {
                permanentlyUnavailable = true
            }
        } catch (e: ReflectiveOperationException) {
            permanentlyUnavailable = true
            android.util.Log.w(TAG, "OM: activation failed — disabling OMID for process", e)
        }
    }

    private fun ensurePartner() {
        if (globallyActivated && cachedPartner == null) {
            cachedPartner = createOmidPartner()
        }
    }

    /**
     * Creates an OMID session for [webView]. Waits 50 ms for geometry
     * stabilization, then constructs + starts the session before
     * returning. Returns `null` if the SDK isn't activated, the partner
     * couldn't be initialized, or session creation failed via reflection.
     *
     * Caller owns the returned session — call `retire()` + `finish()`
     * when the ad is torn down. Mirrors iOS's
     * `createSession(_:, url:, creativeType:)`.
     */
    public override suspend fun createSession(
        webView: WebView,
        url: String?,
        creativeType: OmCreativeType,
    ): OmSession? {
        val partnerRef = cachedPartner
        if (permanentlyUnavailable || !globallyActivated || partnerRef == null) {
            android.util.Log.w(
                TAG,
                "createSession: cannot create — " +
                    "activated=$globallyActivated partner=${partnerRef != null}",
            )
            return null
        }

        val session = OmSession(webView, url, creativeType, partnerRef)
        if (session.isValid) {
            // For video, wait until the inner `<video>` element has loaded
            // metadata (`readyState >= 1`) so it has non-zero intrinsic
            // dimensions when OMID measures `adView.geometry` at impression
            // time. Without this, cold-start WebViews fire impression while
            // the videoEl is still 0×0 → OMID reports
            // `geometry: 0×0 + reasons: ["hidden"]`, IAB compliance failure.
            // Polls every 25 ms up to 500 ms; warm sessions return
            // immediately, cold sessions wait only as long as needed.
            if (creativeType == OmCreativeType.VIDEO) {
                waitForVideoMetadata(webView)
            }
            // Geometry-stabilisation pause between registerAdView and start().
            // Matches v3 sdk-kotlin + iOS sdk-swift.
            delay(GEOMETRY_STABILITY_DELAY_MS)
            session.start()
        } else {
            android.util.Log.w(TAG, "createSession: session.isValid=false")
        }
        return session.takeIf { it.isValid }
    }

    /**
     * Polls the WebView's `<video>` element via JS until it reports
     * `readyState >= 1` (HAVE_METADATA — intrinsic width/height available),
     * or [VIDEO_METADATA_POLL_MAX_MS] elapses. Returns immediately if the
     * video already has metadata (warm session) or there is no `<video>`
     * element in the document.
     *
     * Used for video creative types — OMID JS measures the videoEl's
     * bounding rect for `adView.geometry`. Until metadata loads the rect
     * is 0×0 and OMID reports `reasons: ["hidden"]` in the impression
     * payload, which fails IAB compliance.
     */
    internal suspend fun waitForVideoMetadata(webView: WebView) {
        val gotMetadata = pollWithTimeout(
            maxWaitMs = VIDEO_METADATA_POLL_MAX_MS,
            pollIntervalMs = VIDEO_METADATA_POLL_INTERVAL_MS,
        ) { pollVideoReady(webView) }
        if (!gotMetadata) {
            android.util.Log.w(
                TAG,
                "waitForVideoMetadata: timed out after ${VIDEO_METADATA_POLL_MAX_MS}ms",
            )
        }
    }

    internal suspend fun pollVideoReady(webView: WebView): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                webView.evaluateJavascript(VIDEO_READY_PROBE) { result ->
                    if (cont.isActive) cont.resume(result == "true")
                }
            } catch (e: IllegalStateException) {
                android.util.Log.w(TAG, "pollVideoReady: evaluateJavascript failed", e)
                if (cont.isActive) cont.resume(false)
            }
        }

    /**
     * Builds the OMID `Partner` instance via reflection. The IAB API
     * uses a static factory (`Partner.createPartner(name, version)`),
     * not a constructor.
     */
    private fun createOmidPartner(): Any? {
        return try {
            val partnerClass = Class.forName("com.iab.omid.library.kontextso.adsession.Partner")
            val createPartner = partnerClass.getMethod(
                "createPartner",
                String::class.java,
                String::class.java,
            )
            createPartner.invoke(null, partner.name, partner.version)
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w(TAG, "OM: partner creation failed", e)
            null
        }
    }

    public companion object {
        private const val TAG = "KontextKit/OM"

        /**
         * Process-global OMID activation state. The OMID SDK is a process-wide
         * singleton, so the first [OmManager] to activate it sets this `true`
         * and every later manager reuses the live SDK instead of re-activating.
         */
        @Volatile
        private var globallyActivated = false

        /**
         * Sticky kill-switch set when `Omid.activate()` ever fails (throws, or
         * leaves the SDK inactive). OMID flips its own `isActive` flag before a
         * mid-activation throw, leaving it half-initialized and its retry-guard
         * permanently no-op; once that happens, starting any session arms a
         * broken `TreeWalker` that crashes on the main thread. When this is set,
         * no manager will activate or create a session for the rest of the
         * process, so the walker is never armed and the crash cannot occur.
         */
        @Volatile
        private var permanentlyUnavailable = false

        /**
         * Resets the process-global activation flags. Test-only — the flags are
         * static and otherwise leak across test cases in the same JVM. Not part
         * of the runtime contract; never called in production.
         */
        internal fun resetActivationStateForTest() {
            globallyActivated = false
            permanentlyUnavailable = false
        }

        /**
         * Pause between OmSession init (which calls `registerAdView`) and
         * `start()` for display ads — gives WebView geometry a chance to
         * settle so the OMID `loaded` and `impression` JS events fire
         * against a stable adView.geometry, not a 1×1 placeholder. Matches
         * v3 sdk-kotlin + iOS sdk-swift.
         */
        private const val GEOMETRY_STABILITY_DELAY_MS = 50L

        /**
         * Maximum total wait for video metadata to load before falling
         * back to `session.start()` anyway. 500 ms covers cold-start
         * metadata load with margin; beyond that something is wrong
         * (network failure, video src 404) and we shouldn't block ads.
         */
        private const val VIDEO_METADATA_POLL_MAX_MS = 500L

        /**
         * Poll interval for [waitForVideoMetadata]. 25 ms gives ~20
         * polls within the 500 ms budget — fine-grained enough to
         * exit promptly when metadata lands, cheap enough that the
         * JS-bridge round-trips don't add measurable overhead.
         */
        private const val VIDEO_METADATA_POLL_INTERVAL_MS = 25L

        /**
         * JS probe — returns `true` (string) when there is a `<video>`
         * element in the document with `readyState >= 1` (HAVE_METADATA).
         * Also returns `true` when there is no `<video>` (display ad in
         * the document; the caller only invokes this for video creatives,
         * but the guard is defensive against malformed iframes).
         */
        private const val VIDEO_READY_PROBE =
            "(function(){var v=document.querySelector('video');" +
                "return !v||v.readyState>=1;})()"

        /**
         * Polls [pollNow] repeatedly with [pollIntervalMs] gaps until it
         * returns `true` or [maxWaitMs] elapses. Returns `true` on early
         * exit, `false` on timeout. Extracted from [waitForVideoMetadata]
         * for unit-testing the deadline math without needing a real
         * WebView + JS bridge.
         */
        internal suspend fun pollWithTimeout(
            maxWaitMs: Long,
            pollIntervalMs: Long,
            pollNow: suspend () -> Boolean,
        ): Boolean {
            val deadline = System.currentTimeMillis() + maxWaitMs
            while (System.currentTimeMillis() < deadline) {
                if (pollNow()) return true
                delay(pollIntervalMs)
            }
            return false
        }

        /**
         * Returns the contents of the bundled `omsdk_v1.js` script. Consumer
         * SDKs inject this at WebView creation (`addDocumentStartJavaScript`)
         * so the OMID JS layer is present before any ad content loads.
         *
         * Resolves through the kit's own [R.raw.omsdk_v1] reference rather
         * than `getIdentifier(...)` — compile-time-checked (build fails if
         * the .js file is missing), no runtime string lookup, lint-clean,
         * and resolves against the kit's own R class regardless of which
         * package the consumer app uses.
         *
         * Mirrors iOS's `OMManager.omsdkScript()`.
         */
        public fun omsdkScript(context: Context): String? = try {
            context.resources.openRawResource(R.raw.omsdk_v1)
                .use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: java.io.IOException) {
            // Theoretically impossible since the resource is packaged in
            // the AAR; defensive catch in case of disk-pressure / OOM
            // surfaces during read.
            android.util.Log.w("Kontext SDK", "OM: omsdk_v1.js load failed", e)
            null
        }
    }
}
