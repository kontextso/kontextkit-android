package so.kontext.kit.omsdk

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Wraps a single OMID ad session for one WebView.
 *
 * Uses reflection to interact with the OMID library so the SDK compiles
 * even without the OMID dependency (graceful degradation).
 */
public class OmSession(
    private val webView: WebView,
    private val url: String?,
    creativeType: OmCreativeType,
    partner: Any?,
) {
    private var session: Any? = null
    private var started = false

    public val isValid: Boolean get() = session != null

    init {
        if (partner != null) {
            try {
                val partnerClass = partner.javaClass

                // Create context
                val contextClass = Class.forName("com.iab.omid.library.kontextso.OMIDKontextsoAdSessionContext")
                val context = contextClass.getConstructor(
                    partnerClass,
                    android.view.View::class.java,
                    String::class.java,
                    String::class.java,
                ).newInstance(partner, webView, url, null)

                // Determine creative type and owners
                val creativeTypeClass = Class.forName("com.iab.omid.library.kontextso.OMIDCreativeType")
                val ownerClass = Class.forName("com.iab.omid.library.kontextso.OMIDOwner")
                val impressionTypeClass = Class.forName("com.iab.omid.library.kontextso.OMIDImpressionType")

                val omCreativeType: Any
                val mediaEventsOwner: Any
                val jsOwner = ownerClass.getField("JAVASCRIPT_OWNER").get(null)!!
                val noneOwner = ownerClass.getField("NONE_OWNER").get(null)!!

                if (creativeType == OmCreativeType.VIDEO) {
                    omCreativeType = creativeTypeClass.getField("VIDEO").get(null)!!
                    mediaEventsOwner = jsOwner
                } else {
                    omCreativeType = creativeTypeClass.getField("HTML_DISPLAY").get(null)!!
                    mediaEventsOwner = noneOwner
                }

                val beginToRender = impressionTypeClass.getField("BEGIN_TO_RENDER").get(null)!!

                // Create configuration
                val configClass = Class.forName("com.iab.omid.library.kontextso.OMIDKontextsoAdSessionConfiguration")
                val config = configClass.getConstructor(
                    creativeTypeClass,
                    impressionTypeClass,
                    ownerClass,
                    ownerClass,
                    Boolean::class.java,
                ).newInstance(omCreativeType, beginToRender, jsOwner, mediaEventsOwner, false)

                // Create session
                val sessionClass = Class.forName("com.iab.omid.library.kontextso.OMIDKontextsoAdSession")
                session = sessionClass.getConstructor(configClass, contextClass)
                    .newInstance(config, context)

                // Register ad view
                val registerAdView = sessionClass.getMethod("registerAdView", android.view.View::class.java)
                registerAdView.invoke(session, webView)
            } catch (e: ReflectiveOperationException) {
                android.util.Log.w("Kontext SDK", "OM: session init failed", e)
                session = null
            }
        }
    }

    public fun start() {
        if (started || session == null) return
        try {
            session!!.javaClass.getMethod("start").invoke(session)
            started = true
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w("Kontext SDK", "OM: session start failed", e)
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

        // Hold the WebView alive for 1s so verification scripts can flush.
        val heldWebView = webView
        Handler(Looper.getMainLooper()).postDelayed({ heldWebView.toString() }, OMID_FINISH_HOLD_MS)
    }

    private companion object {
        private const val OMID_FINISH_HOLD_MS = 1_000L
    }

    public fun logError(errorType: String?, message: String?) {
        if (session == null) return
        try {
            val errorTypeClass = Class.forName("com.iab.omid.library.kontextso.OMIDErrorType")
            val omErrorType = if (errorType == "video") {
                errorTypeClass.getField("MEDIA").get(null)
            } else {
                errorTypeClass.getField("GENERIC").get(null)
            }
            session!!.javaClass.getMethod("logError", errorTypeClass, String::class.java)
                .invoke(session, omErrorType, message ?: "unknown")
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w("Kontext SDK", "OM: logError failed", e)
        }
    }
}
