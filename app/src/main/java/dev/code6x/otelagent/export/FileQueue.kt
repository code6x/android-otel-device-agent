package dev.code6x.otelagent.export

import java.io.File

/**
 * The own disk queue (from the spike, docs/spike.md): one file per OTLP/HTTP request body,
 * named by the time it was written, so the files sort in order. A sender posts them in order
 * and deletes a file only after a 2xx answer. The files live through network cuts and app
 * restarts. A size cap and a max age drop the oldest files first.
 */
class FileQueue(
    val dir: File,
    @Volatile var maxBytes: Long,
    @Volatile var maxAgeMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Files dropped by the cap or the age limit since this object was made. */
    @Volatile var dropped: Long = 0
        private set

    init {
        dir.mkdirs()
        // A crash between write and rename leaves a .tmp file; it is not complete.
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    @Synchronized
    fun add(body: ByteArray): File {
        val now = clock()
        var seq = 0
        var target: File
        do {
            target = File(dir, String.format(java.util.Locale.ROOT, "%013d-%04d.pb", now, seq++))
        } while (target.exists())
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(body)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("could not rename ${tmp.name}")
        }
        trim()
        return target
    }

    /** Queued files, oldest first. */
    @Synchronized
    fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".pb") }?.sortedBy { it.name } ?: emptyList()

    @Synchronized
    fun sizeBytes(): Long = files().sumOf { it.length() }

    /** Drops files older than the max age, then the oldest files until the queue fits the cap. */
    @Synchronized
    fun trim(): Int {
        var n = 0
        val files = files().toMutableList()
        val oldest = clock() - maxAgeMs
        val it = files.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (timeOf(f) < oldest) {
                if (f.delete()) n++
                it.remove()
            }
        }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            if (f.delete()) n++
        }
        dropped += n
        return n
    }

    @Synchronized
    fun remove(f: File) {
        f.delete()
    }

    companion object {
        /** Write time of a queue file, from its name (milliseconds). */
        fun timeOf(f: File): Long = f.name.substringBefore('-').toLongOrNull() ?: 0
    }
}
