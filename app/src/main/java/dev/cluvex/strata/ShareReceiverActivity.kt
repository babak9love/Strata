package dev.cluvex.strata

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.cluvex.strata.core.open.IncomingShare
import dev.cluvex.strata.feature.explorer.ShareMode

/**
 * Receives files shared from other apps. It has no screen of its own: each entry in the share sheet
 * ("Copy to…", "Extract to…", "Archive to…") is an alias of this activity, and the alias that was
 * tapped decides what Strata does with the files once it opens.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = IncomingShare.uris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }
        val alias = intent.component?.className.orEmpty()
        val mode = when {
            alias.endsWith(".ShareExtract") -> ShareMode.EXTRACT
            alias.endsWith(".ShareArchive") -> ShareMode.ARCHIVE
            else -> null
        }
        open(uris, mode)
    }

    private fun open(uris: List<Uri>, mode: ShareMode?) {
        val forward = Intent(this, MainActivity::class.java).apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            mode?.let { putExtra(IncomingShare.EXTRA_MODE, it.name) }
            clipData = ClipData.newRawUri(null, uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(forward)
        finish()
    }
}
