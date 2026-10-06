package app.mccdroid.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.mccdroid.R
import app.mccdroid.logic.Detected
import app.mccdroid.logic.Level
import app.mccdroid.logic.McEvent
import app.mccdroid.logic.NotifyLogic
import app.mccdroid.ui.MainActivity
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

object Notifier {
    const val CH_SERVICE = "service"
    private const val CH_HIGH_VIB = "alert_high_vib"
    private const val CH_HIGH = "alert_high"
    private const val CH_DEF_VIB = "alert_def_vib"
    private const val CH_DEF = "alert_def"
    private const val CH_LOW = "alert_low"
    const val SERVICE_ID = 1001
    const val EXTRA_PROFILE = "profileId"

    private val lastShown = ConcurrentHashMap<String, Long>()

    fun init(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fun ch(id: String, name: String, imp: Int, vib: Boolean, desc: String) {
            val c = NotificationChannel(id, name, imp)
            c.description = desc
            c.enableVibration(vib)
            if (vib) c.vibrationPattern = longArrayOf(0, 250, 150, 250)
            nm.createNotificationChannel(c)
        }
        ch(CH_SERVICE, "Status MCC (latar belakang)", NotificationManager.IMPORTANCE_LOW, false, "Notifikasi tetap saat MCC berjalan")
        ch(CH_HIGH_VIB, "Peringatan penting (getar)", NotificationManager.IMPORTANCE_HIGH, true, "Kick, putus, pesan pribadi")
        ch(CH_HIGH, "Peringatan penting", NotificationManager.IMPORTANCE_HIGH, false, "Pop-up tanpa getar")
        ch(CH_DEF_VIB, "Info (getar)", NotificationManager.IMPORTANCE_DEFAULT, true, "Info biasa dengan getar")
        ch(CH_DEF, "Info", NotificationManager.IMPORTANCE_DEFAULT, false, "Info biasa")
        ch(CH_LOW, "Senyap", NotificationManager.IMPORTANCE_LOW, false, "Tanpa suara")
    }

    private fun openApp(ctx: Context, profileId: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (profileId != null) putExtra(EXTRA_PROFILE, profileId)
        }
        return PendingIntent.getActivity(ctx, (profileId ?: "").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun serviceNotification(ctx: Context, total: Int, online: Int): Notification {
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, McService::class.java).setAction(McService.ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            total == 0 -> "Siaga"
            else -> "$total sesi berjalan, $online online"
        }
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("MCC Droid")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx, null))
            .addAction(0, "Hentikan semua", stop)
            .build()
    }

    private fun channelFor(level: Level, vibrate: Boolean): String = when (level) {
        Level.HIGH -> if (vibrate) CH_HIGH_VIB else CH_HIGH
        Level.NORMAL -> if (vibrate) CH_DEF_VIB else CH_DEF
        else -> CH_LOW
    }

    private fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun notifyEvent(ctx: Context, profile: Profile?, d: Detected) {
        val cfg = NotifyStore.config.value
        val pref = NotifyLogic.effective(cfg, d.event, nowMinutes()) ?: return
        val pid = profile?.id ?: ""
        val key = pid + ":" + d.event.name
        val now = System.currentTimeMillis()
        if (pref.cooldownSec > 0) {
            val prev = lastShown[key]
            if (prev != null && now - prev < pref.cooldownSec * 1000L) return
        }
        lastShown[key] = now
        val name = profile?.name ?: "MCC"
        val (title, text) = describe(name, d)
        val b = NotificationCompat.Builder(ctx, channelFor(pref.level, pref.vibrate))
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setGroup("profile:$pid")
            .setContentIntent(openApp(ctx, profile?.id))
            .setPriority(if (pref.level == Level.HIGH) NotificationCompat.PRIORITY_HIGH else if (pref.level == Level.NORMAL) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
        if (pref.vibrate && Build.VERSION.SDK_INT < 26) b.setVibrate(longArrayOf(0, 250, 150, 250))
        if (d.event == McEvent.DEVICE_CODE) {
            val url = d.extra["url"]
            if (!url.isNullOrEmpty()) {
                val view = PendingIntent.getActivity(
                    ctx, url.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                b.addAction(0, "Buka tautan login", view)
            }
        }
        if (pref.reply && profile?.id != null) {
            val replyIntent = Intent(ctx, NotificationReplyReceiver::class.java).apply {
                putExtra(EXTRA_PROFILE, profile.id)
            }
            val reply = PendingIntent.getBroadcast(
                ctx, (profile.id + ":reply").hashCode(), replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val input = RemoteInput.Builder(NotiferKeys.REPLY).setLabel("Balasan ke console MCC").build()
            b.addAction(NotificationCompat.Action.Builder(0, "Balas", reply).addRemoteInput(input).build())
        }
        post(ctx, key.hashCode(), b.build())
    }

    /** Notifikasi dari aturan otomasi (selalu tampil, level normal). */
    fun custom(ctx: Context, profile: Profile?, title: String, text: String) {
        val b = NotificationCompat.Builder(ctx, CH_DEF)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, profile?.id))
        post(ctx, (title + text).hashCode(), b.build())
    }

    /** Notifikasi uji dari layar pengaturan. */
    fun test(ctx: Context, level: Level, vibrate: Boolean) {
        val b = NotificationCompat.Builder(ctx, channelFor(level, vibrate))
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Tes notifikasi MCC Droid")
            .setContentText("Level: ${level.label}${if (vibrate) ", getar" else ""}")
            .setAutoCancel(true)
            .setPriority(if (level == Level.HIGH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(ctx, null))
        post(ctx, 4242, b.build())
    }

    private fun post(ctx: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    private fun describe(name: String, d: Detected): Pair<String, String> = when (d.event) {
        McEvent.JOINED -> "$name online" to "Berhasil masuk ke server."
        McEvent.KICKED -> "$name dikeluarkan dari server" to d.detail.ifEmpty { "Tanpa alasan." }
        McEvent.LOST -> "$name terputus" to "Koneksi ke server hilang."
        McEvent.LOGIN_FAILED -> "$name gagal login" to d.detail.ifEmpty { "Periksa akun/kata sandi." }
        McEvent.RECONNECTING -> "$name menyambung ulang" to d.detail
        McEvent.RESTARTING -> "$name: MCC memulai ulang" to ""
        McEvent.DEAD -> "$name mati" to "Karakter mati."
        McEvent.DEVICE_CODE -> "$name: login Microsoft diperlukan" to "Kode ${d.extra["code"].orEmpty()} di ${d.extra["url"].orEmpty()}"
        McEvent.WHISPER -> "Pesan pribadi dari ${d.extra["from"].orEmpty()}" to d.extra["message"].orEmpty()
        McEvent.MENTION -> "$name disebut di chat" to d.detail
        McEvent.PLAYER_JOIN -> "${d.detail} masuk" to name
        McEvent.PLAYER_LEAVE -> "${d.detail} keluar" to name
        McEvent.KEYWORD -> "Kata kunci '${d.detail}' ($name)" to d.extra["message"].orEmpty()
        McEvent.PROCESS_STARTED -> "$name dimulai" to "Proses MCC berjalan."
        McEvent.PROCESS_STOPPED -> "$name berhenti" to d.detail
        McEvent.PROCESS_CRASHED -> "$name crash" to "Proses MCC berhenti tak terduga (${d.detail})."
    }
}
