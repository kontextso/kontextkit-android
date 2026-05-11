package so.kontext.kit.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens URLs in Chrome Custom Tabs (in-app browser).
 *
 * Validates http(s) scheme. Auto-dismisses the Custom Tab when
 * the user returns to the app (covers deep-link redirect case).
 */
public object InAppBrowserManager {

    private var isCustomTabOpen = false

    /**
     * Opens a URL in Chrome Custom Tabs.
     *
     * Returns [Result.success] when the tab launched, [Result.failure]
     * with a stdlib exception otherwise — `IllegalArgumentException` for
     * an unsupported scheme (anything other than http/https) and
     * `IllegalStateException` when the supplied [context] is not an
     * Activity.
     *
     * Mirrors iOS's `openFromURLString(_:) -> Result<Bool, NSError>`,
     * just using Kotlin's stdlib exception hierarchy instead of a custom
     * sealed type — `NSError`'s domain+message envelope has no Kotlin
     * equivalent, but `Result<Unit>` paired with a typed stdlib exception
     * gives bridges the same structured-failure shape.
     */
    public fun open(context: Context, url: String): Result<Unit> {
        val uri = Uri.parse(url)
        return when {
            uri.scheme != "http" && uri.scheme != "https" ->
                Result.failure(IllegalArgumentException("Unsupported URL scheme: ${uri.scheme}"))

            context !is Activity ->
                Result.failure(IllegalStateException("No Activity context to attach Custom Tab"))

            else -> {
                val customTabsIntent = CustomTabsIntent.Builder().build()
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                customTabsIntent.launchUrl(context, uri)
                isCustomTabOpen = true
                startAutoDismissListener(context.application)
                Result.success(Unit)
            }
        }
    }

    /**
     * Bridge-friendly variant that maps [Result] onto Promise-like
     * `resolve`/`reject` callbacks for sdk-react-native + sdk-flutter.
     */
    public fun open(
        context: Context,
        url: String?,
        resolve: (Any?) -> Unit,
        reject: (String, String, Throwable?) -> Unit,
    ) {
        if (url.isNullOrEmpty()) {
            reject(BRIDGE_ERROR_CODE, "URL is required", null)
            return
        }
        open(context, url).fold(
            onSuccess = { resolve(true) },
            onFailure = { error ->
                reject(BRIDGE_ERROR_CODE, error.message ?: "Failed to open browser", error)
            },
        )
    }

    /** Bridge error code shared with iOS — single domain across cases. */
    private const val BRIDGE_ERROR_CODE = "IN_APP_BROWSER_ERROR"

    private fun startAutoDismissListener(application: Application) {
        var skipFirstResume = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (skipFirstResume) {
                    skipFirstResume = false
                    return
                }
                if (isCustomTabOpen) {
                    isCustomTabOpen = false
                    application.unregisterActivityLifecycleCallbacks(this)
                }
            }

            // Remaining lifecycle callbacks are framework-mandated by
            // ActivityLifecycleCallbacks (no default methods until API 29);
            // we only care about resume.
            @Suppress("EmptyFunctionBlock")
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            @Suppress("EmptyFunctionBlock")
            override fun onActivityStarted(activity: Activity) {}

            @Suppress("EmptyFunctionBlock")
            override fun onActivityPaused(activity: Activity) {}

            @Suppress("EmptyFunctionBlock")
            override fun onActivityStopped(activity: Activity) {}

            @Suppress("EmptyFunctionBlock")
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            @Suppress("EmptyFunctionBlock")
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
