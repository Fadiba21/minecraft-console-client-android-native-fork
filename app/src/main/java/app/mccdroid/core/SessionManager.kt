package app.mccdroid.core

import android.app.Application
import app.mccdroid.logic.ActionType
import app.mccdroid.logic.Detected
import app.mccdroid.logic.Fired
import app.mccdroid.logic.LineAnalyzer
import app.mccdroid.logic.LineKind
import app.mccdroid.logic.McEvent
import app.mccdroid.logic.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class Summary(val total: Int, val online: Int)

/** Pusat pengelola semua sesi MCC: memulai/menghentikan, menyalurkan kejadian ke notifikasi & otomasi. */
object SessionManager {
    private lateinit var app: Application
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<String, McSession>()
    private val engine = RuleEngine()
    private val restartAttempts = ConcurrentHashMap<String, Int>()
    private val lastKick = ConcurrentHashMap<String, Long>()

    val summary = MutableStateFlow(Summary(0, 0))

    fun init(a: Application) {
        app = a
        scope.launch {
            while (isActive) {
                delay(120)
                for (s in sessions.values) s.log.flush()
                ShellSession.log.flush()
            }
        }
    }

    fun session(id: String): McSession = sessions.getOrPut(id) { McSession(app, id) }

    fun forget(id: String) {
        sessions.remove(id)?.let { if (it.isAlive) it.kill() }
        refreshSummary()
    }

    fun refreshSummary() {
        val running = sessions.values.filter { it.isAlive || it.state.value == SState.STARTING }
        summary.value = Summary(running.size, running.count { it.state.value == SState.ONLINE })
    }

    fun start(id: String): String? {
        val s = session(id)
        val err = s.start()
        if (err == null) McService.ensureRunning(app)
        refreshSummary()
        return err
    }

    fun stop(id: String) {
        sessions[id]?.stop()
    }

    fun stopAll() {
        for (s in sessions.values) if (s.isAlive) s.stop()
    }

    fun restart(id: String) {
        val s = session(id)
        scope.launch {
            if (s.isAlive) {
                s.stop()
                var waited = 0
                while (s.isAlive && waited < 150) {
                    delay(100); waited++
                }
                if (s.isAlive) s.kill()
                delay(500)
            }
            start(id)
        }
    }

    /** Mulai semua profil bertanda "mulai otomatis" setelah runtime siap. */
    fun autoStartAll() {
        scope.launch {
            RuntimeInstaller.state.first { it is RtState.Ready || it is RtState.Missing || it is RtState.Failed }
            if (!RuntimeInstaller.isReady) return@launch
            for (p in ProfileStore.profiles.value) {
                if (p.autoStart && !session(p.id).isAlive) {
                    start(p.id)
                    delay(1500)
                }
            }
        }
    }

    internal fun onProcessStarted(s: McSession) {
        val p = ProfileStore.get(s.profileId)
        Notifier.notifyEvent(app, p, Detected(McEvent.PROCESS_STARTED))
        refreshSummary()
    }

    internal fun onLine(s: McSession, clean: String) {
        val p = ProfileStore.get(s.profileId)
        val name = p?.name ?: s.profileId
        ChatGameAi.onLine(s, clean)
        val keywords = NotifyStore.config.value.keywords
        for (d in LineAnalyzer.analyze(clean, s.accountName, keywords)) onEvent(s, d)
        val fired = engine.onLine(RuleStore.rules.value, s.profileId, name, clean)
        for (f in fired) runFired(s, f)
    }

    internal fun onEvent(s: McSession, d: Detected) {
        val p = ProfileStore.get(s.profileId)
        val name = p?.name ?: s.profileId
        val now = System.currentTimeMillis()
        var notify = true
        when (d.event) {
            McEvent.JOINED -> {
                s.setState(SState.ONLINE)
                s.deviceCode.value = null
                restartAttempts.remove(s.profileId)
            }
            McEvent.KICKED -> {
                s.setState(SState.DISCONNECTED)
                lastKick[s.profileId] = now
            }
            McEvent.LOST -> {
                s.setState(SState.DISCONNECTED)
                // Kick biasanya diikuti "connection lost"; jangan beri dua notifikasi.
                val k = lastKick[s.profileId]
                if (k != null && now - k < 8000) notify = false
            }
            McEvent.LOGIN_FAILED -> s.setState(SState.DISCONNECTED)
            McEvent.RECONNECTING -> s.setState(SState.RECONNECTING)
            McEvent.DEVICE_CODE -> {
                s.setState(SState.WAITING_LOGIN)
                val url = d.extra["url"].orEmpty()
                val code = d.extra["code"].orEmpty()
                if (url.isNotEmpty() && code.isNotEmpty()) s.deviceCode.value = DeviceCode(url, code)
            }
            else -> {}
        }
        s.lastEvent.value = d.event.label + if (d.detail.isNotEmpty()) ": " + d.detail else ""
        if (notify) Notifier.notifyEvent(app, p, d)
        for (f in engine.onEvent(RuleStore.rules.value, s.profileId, name, d)) runFired(s, f)
    }

    internal fun onExit(s: McSession, code: Int) {
        val p = ProfileStore.get(s.profileId)
        s.deviceCode.value = null
        val crashed = !s.stopRequested && code != 0
        Notifier.notifyEvent(
            app, p,
            Detected(if (crashed) McEvent.PROCESS_CRASHED else McEvent.PROCESS_STOPPED, "kode $code"),
        )
        refreshSummary()
        if (p != null && !s.stopRequested && p.autoRestart && ProfileStore.get(p.id) != null) {
            val n = (restartAttempts[p.id] ?: 0) + 1
            if (n <= 10) {
                restartAttempts[p.id] = n
                val waitSec = minOf(120, 5 * (1 shl minOf(n - 1, 5)))
                s.log.append("Mulai ulang otomatis dalam $waitSec dtk (percobaan $n/10)…", LineKind.SYS)
                scope.launch {
                    delay(waitSec * 1000L)
                    if (!s.isAlive && !s.stopRequested) start(p.id)
                }
            } else {
                s.log.append("Mulai ulang otomatis dihentikan setelah 10 percobaan.", LineKind.ERR)
            }
        }
    }

    private fun runFired(s: McSession, f: Fired) {
        scope.launch {
            for (a in f.actions) {
                when (a.type) {
                    ActionType.SEND -> if (a.arg.isNotBlank()) s.send(a.arg)
                    ActionType.NOTIFY -> {
                        val parts = a.arg.split('|', limit = 2)
                        val title = if (parts.size == 2) parts[0] else f.rule.name
                        val text = if (parts.size == 2) parts[1] else a.arg
                        Notifier.custom(app, ProfileStore.get(s.profileId), title, text)
                    }
                    ActionType.DELAY -> delay((a.arg.trim().toLongOrNull() ?: 0L).coerceIn(0L, 60_000L))
                    ActionType.RESTART -> restart(s.profileId)
                    ActionType.STOP -> stop(s.profileId)
                }
            }
        }
    }
}
