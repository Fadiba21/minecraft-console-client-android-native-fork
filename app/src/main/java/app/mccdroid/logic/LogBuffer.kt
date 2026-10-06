package app.mccdroid.logic

import kotlinx.coroutines.flow.MutableStateFlow

enum class LineKind { OUT, IN, SYS, ERR }

/** Satu baris konsol. Teks mentah menyimpan kode warna; [clean] dan [spans] dihitung malas. */
class LogLine(
    val id: Long,
    val ts: Long,
    val text: String,
    val kind: LineKind,
) {
    private val normalized: String by lazy(LazyThreadSafetyMode.PUBLICATION) { McText.normalize(text) }
    val clean: String by lazy(LazyThreadSafetyMode.PUBLICATION) { McText.strip(normalized) }
    val spans: List<Span> by lazy(LazyThreadSafetyMode.PUBLICATION) { McText.parse(normalized) }
}

/**
 * Buffer log berbatas. Thread pembaca menambahkan baris ke antrean; [flush] (dipanggil berkala)
 * memindahkannya ke [flow] sekaligus agar UI tidak dibanjiri pembaruan per baris.
 */
class LogBuffer(private val maxLines: () -> Int) {
    private val lock = Any()
    private val pending = ArrayList<LogLine>()
    private var nextId = 1L

    val flow = MutableStateFlow<List<LogLine>>(emptyList())

    fun append(text: String, kind: LineKind = LineKind.OUT): LogLine {
        synchronized(lock) {
            val l = LogLine(nextId++, System.currentTimeMillis(), text, kind)
            pending.add(l)
            return l
        }
    }

    /** @return true bila ada baris baru yang dipublikasikan. */
    fun flush(): Boolean {
        val batch: List<LogLine>
        synchronized(lock) {
            if (pending.isEmpty()) return false
            batch = ArrayList(pending)
            pending.clear()
        }
        val cur = flow.value
        val max = maxOf(100, maxLines())
        val total = cur.size + batch.size
        val merged = ArrayList<LogLine>(minOf(total, max))
        val skip = maxOf(0, total - max)
        var seen = 0
        for (l in cur) {
            if (seen++ >= skip) merged.add(l)
        }
        for (l in batch) {
            if (seen++ >= skip) merged.add(l)
        }
        flow.value = merged
        return true
    }

    fun clear() {
        synchronized(lock) { pending.clear() }
        flow.value = emptyList()
    }
}
