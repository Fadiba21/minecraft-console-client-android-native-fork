package app.mccdroid.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground service agar MCC tetap hidup saat aplikasi di latar belakang. */
class McService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var idleJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(Notifier.SERVICE_ID, Notifier.serviceNotification(this, 0, 0))
        scope.launch {
            SessionManager.summary.collect { s ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(Notifier.SERVICE_ID, Notifier.serviceNotification(this@McService, s.total, s.online))
                updateLocks(s.total > 0)
                idleJob?.cancel()
                if (s.total == 0) {
                    // Tunggu sebentar (mis. mulai-ulang otomatis) sebelum menghentikan service.
                    idleJob = launch {
                        delay(25_000)
                        if (SessionManager.summary.value.total == 0) {
                            stopForeground(true)
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) SessionManager.stopAll()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun updateLocks(active: Boolean) {
        try {
            if (active && AppPrefs.wakeLock) {
                if (wake == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mccdroid:session").apply { setReferenceCounted(false) }
                }
                if (wake?.isHeld == false) wake?.acquire()
            } else if (wake?.isHeld == true) {
                wake?.release()
            }
            if (active && AppPrefs.wifiLock) {
                if (wifi == null) {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mccdroid:wifi").apply { setReferenceCounted(false) }
                }
                if (wifi?.isHeld == false) wifi?.acquire()
            } else if (wifi?.isHeld == true) {
                wifi?.release()
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        updateLocks(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_ALL = "app.mccdroid.STOP_ALL"

        fun ensureRunning(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, McService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
