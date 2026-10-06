package app.mccdroid.core

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

/** Resource sampling for the foreground Dashboard only. CPU percentage is normalized to total machine capacity. */
data class ResourceUsage(
    val systemCpuPercent: Float,
    val appCpuPercent: Float,
    val systemRamPercent: Float,
    val appRamMb: Int,
    val appRamPercent: Float,
    val totalRamMb: Int,
    val cores: Int,
)

private data class CpuTicks(val total: Long, val idle: Long)

class ResourceUsageSampler {
    private var previousCpu: CpuTicks? = null
    private var previousProcessTicks: Long? = null

    /** Android vendor builds may restrict procfs; sampling must never crash the Dashboard. */
    fun sampleOrNull(context: Context): ResourceUsage? = runCatching { sample(context) }.getOrNull()

    fun sample(context: Context): ResourceUsage {
        val cpu = readCpu()
        val process = readProcessTicks()
        val previous = previousCpu
        val previousProcess = previousProcessTicks
        previousCpu = cpu
        previousProcessTicks = process
        val totalDelta = if (previous == null) 0L else (cpu.total - previous.total).coerceAtLeast(1L)
        val idleDelta = if (previous == null) 0L else (cpu.idle - previous.idle).coerceAtLeast(0L)
        val processDelta = if (previousProcess == null) 0L else (process - previousProcess).coerceAtLeast(0L)
        val systemCpu = if (previous == null) 0f else ((totalDelta - idleDelta).coerceAtLeast(0L) * 100f / totalDelta).coerceIn(0f, 100f)
        val appCpu = if (previous == null) 0f else (processDelta * 100f / totalDelta).coerceIn(0f, 100f)

        val manager = context.getSystemService(ActivityManager::class.java) ?: return ResourceUsage(
            systemCpuPercent = systemCpu,
            appCpuPercent = appCpu,
            systemRamPercent = 0f,
            appRamMb = 0,
            appRamPercent = 0f,
            totalRamMb = 0,
            cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        )
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val total = memory.totalMem.coerceAtLeast(1L)
        val used = (total - memory.availMem).coerceAtLeast(0L)
        val processInfo = manager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()
        val appKb = processInfo?.totalPss?.coerceAtLeast(0) ?: 0
        return ResourceUsage(
            systemCpuPercent = systemCpu,
            appCpuPercent = appCpu,
            systemRamPercent = used * 100f / total,
            appRamMb = appKb / 1024,
            appRamPercent = appKb * 1024f * 100f / total,
            totalRamMb = (total / (1024 * 1024)).toInt(),
            cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        )
    }

    private fun readCpu(): CpuTicks {
        val line = File("/proc/stat").bufferedReader(StandardCharsets.US_ASCII).use { it.readLine() ?: "cpu 0 0 0 0" }
        val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        val idle = (values.getOrNull(3) ?: 0L) + (values.getOrNull(4) ?: 0L)
        return CpuTicks(values.sum(), idle)
    }

    private fun readProcessTicks(): Long {
        val raw = File("/proc/self/stat").readText(StandardCharsets.US_ASCII)
        val afterName = raw.substringAfterLast(')').trim().split(Regex("\\s+"))
        val user = afterName.getOrNull(11)?.toLongOrNull() ?: 0L
        val system = afterName.getOrNull(12)?.toLongOrNull() ?: 0L
        return user + system
    }
}
