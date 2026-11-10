package indi.renakoni.nextvol.sourcebrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import indi.renakoni.nextvol.R

/** User-controlled website window; completion returns the current document to its waiting request. */
class NativeSourceBrowserActivity : Activity() {
    private var browser: NativeSourceBrowserService? = null
    private var systemBack: SourceBrowserBack? = null
    private val jobId get() = intent.getStringExtra("jobId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = NativeSourceBrowserService.active ?: run { finish(); return }
        if (jobId == null || active.interactiveJobId != jobId) { finish(); return }
        val view = active.webView ?: run { finish(); return }
        browser = active; active.activity = this
        (view.parent as? ViewGroup)?.removeView(view)
        setContentView(sourceBrowserLayout(this, active.title, view,
            getText(android.R.string.cancel), { active.cancel(jobId) },
            { if (active.interactiveJobId == jobId) view.reload() },
            getText(R.string.source_browser_done), { active.confirm(jobId) }))
        systemBack = SourceBrowserBack(this) { handleBack() }.also { it.register() }
    }
    private fun handleBack() {
        if (browser?.interactiveJobId != jobId) { finish(); return }
        if (browser?.webView?.canGoBack() == true) browser?.webView?.goBack() else browser?.cancel(jobId)
    }
    // API 24-32 fallback; API 33+ uses SourceBrowserBack's registered callback.
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = handleBack()
    override fun onDestroy() {
        systemBack?.unregister()
        if (browser?.activity === this) {
            browser?.activity = null
            if (isFinishing) browser?.cancel(jobId)
        }
        super.onDestroy()
    }
}
