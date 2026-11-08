package indi.renakoni.nextvol.ui.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.export.EpubShareFiles
import java.util.UUID

/** Both foreground completion and notification taps use an Activity to open the chooser. */
open class EpubShareActivity : Activity() {
    companion object {
        fun intent(context: Context, id: UUID, automatic: Boolean = false) =
            // Keep the component identity used by already-issued notification PendingIntents.
            Intent().setClassName(context, "indi.renakoni.nextvol.ui.book.detail.EpubShareActivity")
                .setData(android.net.Uri.parse("nextvol-epub:$id"))
                .putExtra("automatic", automatic)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            try {
                val id = UUID.fromString(intent.data?.schemeSpecificPart)
                if (!intent.getBooleanExtra("automatic", false) || !EpubShareFiles.wasShared(this, id)) {
                    startActivity(EpubShareFiles.chooser(this, id))
                    EpubShareFiles.markShared(this, id)
                }
            } catch (_: RuntimeException) {
                Toast.makeText(this, R.string.epub_export_share_failed, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
