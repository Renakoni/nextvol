package indi.renakoni.nextvol.sourcebrowser

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toolbar
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import indi.renakoni.nextvol.R
import kotlin.math.roundToInt

/** Window chrome only; each browser retains ownership of navigation and completion. */
internal fun sourceBrowserLayout(
    context: Context,
    title: String,
    browser: View,
    onClose: () -> Unit,
    onReload: () -> Unit,
    @StringRes confirmLabel: Int,
    onConfirm: () -> Unit,
): LinearLayout = LinearLayout(context).apply {
    val labels = ContextCompat.getContextForLanguage(context).resources
    fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    orientation = LinearLayout.VERTICAL
    fitsSystemWindows = true
    setBackgroundColor(context.getColor(R.color.source_browser_background))
    addView(LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        fun button(icon: Int, label: CharSequence, action: () -> Unit) = ImageButton(context, null,
            android.R.attr.borderlessButtonStyle).apply {
            setImageDrawable(context.getDrawable(icon)?.mutate()?.apply {
                setTint(context.getColor(R.color.source_browser_foreground))
            })
            contentDescription = label
            setOnClickListener { action() }
        }
        addView(button(R.drawable.close_24px, labels.getText(android.R.string.cancel), onClose), LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(button(R.drawable.refresh_24px, labels.getText(R.string.source_browser_reload), onReload),
            LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(Toolbar(context).apply {
            this.title = title.ifBlank { labels.getString(R.string.source_browser_title) }
            setTitleTextColor(context.getColor(R.color.source_browser_foreground))
            setContentInsetsRelative(0, 0)
            contentInsetEndWithActions = 0
            menu.add(0, android.R.id.button1, 0, labels.getText(confirmLabel)).apply {
                icon = context.getDrawable(R.drawable.check_24px)?.mutate()?.apply {
                    setTint(context.getColor(R.color.source_browser_accent))
                }
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener { onConfirm(); true }
            }
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }, LinearLayout.LayoutParams(-1, dp(56)))
    addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
}
