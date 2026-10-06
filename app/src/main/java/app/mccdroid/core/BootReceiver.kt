package app.mccdroid.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Menyalakan profil "mulai otomatis" setelah perangkat selesai boot (bila diaktifkan di Pengaturan). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON") return
        if (!AppPrefs.bootStart) return
        if (ProfileStore.profiles.value.none { it.autoStart }) return
        McService.ensureRunning(context)
        SessionManager.autoStartAll()
    }
}
