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
        if (activated) return true

        return try {
            val omidClass = Class.forName("com.iab.omid.library.kontextso.Omid")
            val activateMethod = omidClass.getMethod("activate", Context::class.java)
            activateMethod.invoke(null, context)

            val isActiveMethod = omidClass.getMethod("isActive")
            activated = isActiveMethod.invoke(null) as Boolean

            if (activated) {
                cachedPartner = createOmidPartner()
            }
            activated
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w("Kontext SDK", "OM: activation failed (OMSDK not available)", e)
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
        if (!activated || cachedPartner == null) return null

        delay(GEOMETRY_STABILITY_DELAY_MS)

        val session = OmSession(webView, url, creativeType, cachedPartner)
        return if (session.isValid) {
            session.start()
            session
        } else {
            null
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
            android.util.Log.w("Kontext SDK", "OM: partner creation failed", e)
            null
        }
    }

    public companion object {
        /**
         * Pause between OmSession init and `start()` — gives WebView geometry
         * a chance to settle so the OMID `loaded` event fires with stable
         * bounds. Matches iOS's 50 ms.
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
