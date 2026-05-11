package so.kontext.kit.omsdk

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.delay
import so.kontext.kit.R

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

    private var activated = false
    private val partner: OmPartner = partner
    private var cachedPartner: Any? = null

    /**
     * Activates the OMID SDK. Idempotent — subsequent calls return the
     * already-active state. Requires a Context to register the SDK with
     * the host application.
     */
    public override fun activate(context: Context): Boolean {
        if (activated) {
            android.util.Log.d(TAG, "activate: already activated, skipping")
            return true
        }

        return try {
            val omidClass = Class.forName("com.iab.omid.library.kontextso.Omid")
            android.util.Log.d(TAG, "activate: Omid class loaded")

            val activateMethod = omidClass.getMethod("activate", Context::class.java)
            activateMethod.invoke(null, context)
            android.util.Log.d(TAG, "activate: Omid.activate(context) invoked")

            val isActiveMethod = omidClass.getMethod("isActive")
            activated = isActiveMethod.invoke(null) as Boolean
            android.util.Log.d(TAG, "activate: Omid.isActive() = $activated")

            if (activated) {
                cachedPartner = createOmidPartner()
                android.util.Log.d(
                    TAG,
                    "activate: partner cached = ${cachedPartner != null} " +
                        "(${partner.name} / ${partner.version})",
                )
            }
            activated
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w(TAG, "OM: activation failed (OMSDK not available)", e)
            false
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
        android.util.Log.d(
            TAG,
            "createSession: activated=$activated " +
                "partnerCached=${cachedPartner != null} " +
                "creativeType=$creativeType url=$url",
        )
        val partnerRef = cachedPartner
        if (!activated || partnerRef == null) {
            android.util.Log.w(
                TAG,
                "createSession: cannot create — " +
                    "activated=$activated partner=${partnerRef != null}",
            )
            return null
        }

        val session = OmSession(webView, url, creativeType, partnerRef)
        android.util.Log.d(TAG, "createSession: OmSession instantiated, isValid=${session.isValid}")
        if (session.isValid) {
            // 50 ms between registerAdView (done inside OmSession.init) and
            // start() — matches v3 sdk-kotlin + iOS sdk-swift. Lets the
            // WebView geometry stabilise so the OMID JS layer's `loaded` and
            // `impression` events fire against a stable adView.geometry,
            // not a 1x1 placeholder.
            delay(GEOMETRY_STABILITY_DELAY_MS)
            session.start()
            android.util.Log.d(
                TAG,
                "createSession: session.start() returned " +
                    "(session.started should now be true)",
            )
        } else {
            android.util.Log.w(
                TAG,
                "createSession: session.isValid=false — " +
                    "reflection in OmSession.init failed (check earlier Log.w)",
            )
        }
        return session.takeIf { it.isValid }
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
            val result = createPartner.invoke(null, partner.name, partner.version)
            android.util.Log.d(
                TAG,
                "createOmidPartner: Partner.createPartner(" +
                    "${partner.name}, ${partner.version}) → $result",
            )
            result
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w(TAG, "OM: partner creation failed", e)
            null
        }
    }

    public companion object {
        private const val TAG = "KontextKit/OM"

        /**
         * Pause between OmSession init (which calls `registerAdView`) and
         * `start()` — gives WebView geometry a chance to settle so the
         * OMID `loaded` and `impression` JS events fire against the
         * stable adView.geometry, not a 1x1 placeholder. Matches v3
         * sdk-kotlin + iOS sdk-swift.
         */
        private const val GEOMETRY_STABILITY_DELAY_MS = 50L

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
