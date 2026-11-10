package indi.renakoni.nextvol.sourcebrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import indi.renakoni.nextvol.R

/** Foreground login or verification; cancellation never submits the current document. */
class SourceBrowserActivity : Activity() {
    private var browser: SourceBrowserService? = null
    private var systemBack: SourceBrowserBack? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = SourceBrowserService.active ?: run { finish(); return }
        browser = active; active.activity = this
        val view = active.webView ?: run { finish(); return }
        (view.parent as? ViewGroup)?.removeView(view)
        setContentView(sourceBrowserLayout(this, active.pageTitle, view,
            getText(android.R.string.cancel), { active.cancel() }, view::reload,
            getText(if (active.verificationCode) android.R.string.ok else R.string.source_browser_done), { active.evaluate() }))
        systemBack = SourceBrowserBack(this) { handleBack() }.also { it.register() }
    }
    private fun handleBack() {
        val active = browser ?: run { super.onBackPressed(); return }
        if (active.verificationCode) active.cancel()
        else if (active.webView?.canGoBack() == true) active.webView?.goBack()
        else active.cancel()
    }
    // API 24-32 fallback; API 33+ uses SourceBrowserBack's registered callback.
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = handleBack()
    override fun onDestroy() {
        systemBack?.unregister()
        if (browser?.activity === this) {
            browser?.activity = null
            if (isFinishing) browser?.cancel()
        }
        super.onDestroy()
    }
}
