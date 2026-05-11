package so.kontext.kit.omsdk

import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView

/**
 * Wraps a single OMID ad session for one WebView.
 *
 * Reflective so the SDK compiles even without the OMID AAR on the
 * classpath (graceful degradation). Class names + factory methods
 * mirror the IAB OMID Android API exactly:
 *
 *   com.iab.omid.library.kontextso.adsession.AdSession
 *   com.iab.omid.library.kontextso.adsession.AdSessionConfiguration
 *   com.iab.omid.library.kontextso.adsession.AdSessionContext
 *   com.iab.omid.library.kontextso.adsession.CreativeType
 *   com.iab.omid.library.kontextso.adsession.ErrorType
 *   com.iab.omid.library.kontextso.adsession.ImpressionType
 *   com.iab.omid.library.kontextso.adsession.Owner
 *   com.iab.omid.library.kontextso.adsession.Partner
 *
 * All construction goes through static factories (`createX(...)`),
 * not constructors — that's the published IAB pattern.
 *
 * Per the IAB OMID Android `#webview-video` guidance, HTML video
 * sessions use `CreativeType.DEFINED_BY_JAVASCRIPT` +
 * `ImpressionType.DEFINED_BY_JAVASCRIPT` + `Owner.JAVASCRIPT` for the
 * media-events owner. Display sessions use `CreativeType.HTML_DISPLAY`
 * + `ImpressionType.BEGIN_TO_RENDER` + `Owner.NONE` for media events.
 */
public class OmSession(
    private val webView: WebView,
    private val url: String?,
    private val creativeType: OmCreativeType,
    partner: Any?,
) {
    private var session: Any? = null
    private var adEvents: Any? = null
    private var started = false
    private var loadedFired = false
    private var impressionFired = false

    public val isValid: Boolean get() = session != null

    init {
        if (partner == null) {
            android.util.Log.w(TAG, "OmSession.init: partner is null — skipping session creation")
        } else {
            try {
                val partnerClass = Class.forName("com.iab.omid.library.kontextso.adsession.Partner")
                val contextClass = Class.forName("com.iab.omid.library.kontextso.adsession.AdSessionContext")
                val configClass = Class.forName("com.iab.omid.library.kontextso.adsession.AdSessionConfiguration")
                val sessionClass = Class.forName("com.iab.omid.library.kontextso.adsession.AdSession")
                val creativeTypeClass = Class.forName("com.iab.omid.library.kontextso.adsession.CreativeType")
                val impressionTypeClass = Class.forName("com.iab.omid.library.kontextso.adsession.ImpressionType")
                val ownerClass = Class.forName("com.iab.omid.library.kontextso.adsession.Owner")
                android.util.Log.d(TAG, "OmSession.init: all 7 OMID classes loaded")

                // AdSessionContext.createHtmlAdSessionContext(partner, webView, contentUrl, customReferenceData)
                val createContext = contextClass.getMethod(
                    "createHtmlAdSessionContext",
                    partnerClass,
                    WebView::class.java,
                    String::class.java,
                    String::class.java,
                )
                val context = createContext.invoke(null, partner, webView, url, "")
                android.util.Log.d(TAG, "OmSession.init: AdSessionContext created (contentUrl=$url)")

                // Pick creative-type / impression-type / impression-owner / media-events-owner.
                // - Display: NATIVE impression owner. SDK fires loaded() + impressionOccurred()
                //   via AdEvents so the JS verification script does NOT poll geometry and
                //   does NOT emit a `notFound` geometryChange when the WebView detaches.
                // - Video: DEFINED_BY_JAVASCRIPT triple per the IAB OMID Android
                //   #webview-video docs — JS owns impression + media events.
                val nativeOwner = ownerClass.getField("NATIVE").get(null)!!
                val jsOwner = ownerClass.getField("JAVASCRIPT").get(null)!!
                val noneOwner = ownerClass.getField("NONE").get(null)!!

                val omCreativeType: Any
                val omImpressionType: Any
                val impressionOwner: Any
                val mediaOwner: Any
                if (creativeType == OmCreativeType.VIDEO) {
                    omCreativeType = creativeTypeClass.getField("DEFINED_BY_JAVASCRIPT").get(null)!!
                    omImpressionType = impressionTypeClass.getField("DEFINED_BY_JAVASCRIPT").get(null)!!
                    impressionOwner = jsOwner
                    mediaOwner = jsOwner
                } else {
                    omCreativeType = creativeTypeClass.getField("HTML_DISPLAY").get(null)!!
                    omImpressionType = impressionTypeClass.getField("BEGIN_TO_RENDER").get(null)!!
                    impressionOwner = nativeOwner
                    mediaOwner = noneOwner
                }

                // AdSessionConfiguration.createAdSessionConfiguration(
                //     creative, impression, impressionOwner, mediaOwner, isolateVerificationScripts=false
                // )
                val createConfig = configClass.getMethod(
                    "createAdSessionConfiguration",
                    creativeTypeClass,
                    impressionTypeClass,
                    ownerClass,
                    ownerClass,
                    Boolean::class.javaPrimitiveType,
                )
                val config = createConfig.invoke(
                    null,
                    omCreativeType,
                    omImpressionType,
                    impressionOwner,
                    mediaOwner,
                    false,
                )
                android.util.Log.d(TAG, "OmSession.init: AdSessionConfiguration created (creative=$creativeType)")

                // AdSession.createAdSession(config, context)
                val createSession = sessionClass.getMethod("createAdSession", configClass, contextClass)
                val sess = createSession.invoke(null, config, context)
                android.util.Log.d(TAG, "OmSession.init: AdSession.createAdSession returned ${sess?.javaClass?.name}")

                // session.registerAdView(webView)
                val registerAdView = sessionClass.getMethod("registerAdView", View::class.java)
                registerAdView.invoke(sess, webView)
                android.util.Log.d(TAG, "OmSession.init: registerAdView(webView) done")

                session = sess

                // For NATIVE impression-owner sessions (display), create AdEvents so the
                // SDK can fire loaded() + impressionOccurred() from Kotlin. For JS-owner
                // sessions (video) AdEvents is still created but loaded/impression are
                // owned by the in-iframe verification script and we don't call them.
                if (creativeType != OmCreativeType.VIDEO) {
                    val adEventsClass = Class.forName("com.iab.omid.library.kontextso.adsession.AdEvents")
                    val createAdEvents = adEventsClass.getMethod("createAdEvents", sessionClass)
                    adEvents = createAdEvents.invoke(null, sess)
                    android.util.Log.d(TAG, "OmSession.init: AdEvents created (display, NATIVE owner)")
                }
            } catch (e: ReflectiveOperationException) {
                android.util.Log.w(TAG, "OM: session init failed", e)
                session = null
            }
        }
    }

    public fun start() {
        if (started) {
            android.util.Log.d(TAG, "OmSession.start: already started")
            return
        }
        if (session == null) {
            android.util.Log.w(TAG, "OmSession.start: session is null")
            return
        }
        try {
            session!!.javaClass.getMethod("start").invoke(session)
            started = true
            android.util.Log.d(TAG, "OmSession.start: session.start() invoked successfully")
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w(TAG, "OM: session start failed", e)
        }
    }

    /**
     * Fires the OMID `loaded` event from native code. Only valid for sessions
     * with `Owner.NATIVE` as impressionOwner (display). For JS-owner sessions
     * (video) the verification script emits this — calling here is a no-op.
     * Must be called after [start].
     */
    public fun loaded() {
        if (loadedFired) return
        val ev = adEvents ?: return
        try {
            ev.javaClass.getMethod("loaded").invoke(ev)
            loadedFired = true
            android.util.Log.d(TAG, "OmSession.loaded: fired")
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w(TAG, "OM: AdEvents.loaded failed", e)
        }
    }

    /**
     * Fires the OMID `impressionOccurred` event from native code. Only valid
     * for sessions with `Owner.NATIVE` as impressionOwner (display). Must be
     * called after [loaded] and after the ad is actually rendered.
     */
    public fun impressionOccurred() {
        if (impressionFired) return
        val ev = adEvents ?: return
        try {
            ev.javaClass.getMethod("impressionOccurred").invoke(ev)
            impressionFired = true
            android.util.Log.d(TAG, "OmSession.impressionOccurred: fired")
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w(TAG, "OM: AdEvents.impressionOccurred failed", e)
        }
    }

    public fun retire() {
        try {
            webView.evaluateJavascript(
                "window.postMessage({ type: 'retire-iframe' }, '*'); null",
                null,
            )
        } catch (e: IllegalStateException) {
            // WebView destroyed before retire() — verification scripts already gone.
            android.util.Log.w("Kontext SDK", "OM: session retire failed", e)
        }
    }

    /**
     * Terminates the OMID session natively and **holds the WebView alive
     * for 1 second** so the in-iframe verification scripts can handle the
     * `sessionFinish` event before the host tears the WebView down. Per
     * `OMIDAdSession.h`:
     *
     * > "Note that ending an OMID ad session sends a message to the
     * > verification scripts running inside the webview supplied by the
     * > integration. So that the verification scripts have enough time
     * > to handle the `sessionFinish` event, the integration must
     * > maintain a strong reference to the webview for at least 1.0
     * > seconds after ending the session."
     *
     * The hold is implemented as a delayed Handler post that captures
     * the WebView reference. Caller can drop their `OmSession` reference
     * (and any other strong WebView reference) immediately after this
     * returns — the WebView stays alive until the delayed action fires.
     *
     * Pair with `retire()` — retire first so JS can flush, then finish
     * to dispatch the session-finish event.
     */
    public fun finish() {
        if (session == null) return
        try {
            session!!.javaClass.getMethod("finish").invoke(session)
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w("Kontext SDK", "OM: session finish failed", e)
        }
        session = null
        adEvents = null

        // Hold the WebView alive for 1s so verification scripts can flush.
        val heldWebView = webView
        Handler(Looper.getMainLooper()).postDelayed({ heldWebView.toString() }, OMID_FINISH_HOLD_MS)
    }

    /**
     * Reports a session-level error to the OMID JS verification layer.
     * The IAB Android API exposes this as `AdSession.error(ErrorType, String)`,
     * not `logError(...)`.
     */
    public fun logError(errorType: String?, message: String?) {
        if (session == null) return
        try {
            val errorTypeClass = Class.forName("com.iab.omid.library.kontextso.adsession.ErrorType")
            val omErrorType = if (errorType == "video") {
                errorTypeClass.getField("VIDEO").get(null)
            } else {
                errorTypeClass.getField("GENERIC").get(null)
            }
            session!!.javaClass.getMethod("error", errorTypeClass, String::class.java)
                .invoke(session, omErrorType, message ?: "unknown")
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w("Kontext SDK", "OM: error report failed", e)
        }
    }

    private companion object {
        private const val OMID_FINISH_HOLD_MS = 1_000L
        private const val TAG = "KontextKit/OM"
    }
}
