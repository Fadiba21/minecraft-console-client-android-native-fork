package app.mccdroid.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Meneruskan balasan inline dari notifikasi mention ke console sesi MCC terkait. */
class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotiferKeys.REPLY)?.toString()?.trim().orEmpty()
        val profileId = intent.getStringExtra(Notifier.EXTRA_PROFILE).orEmpty()
        if (reply.isNotEmpty() && profileId.isNotEmpty()) {
            SessionManager.session(profileId).send(reply)
        }
    }
}

object NotiferKeys {
    const val REPLY = "mccdroid.reply"
}
