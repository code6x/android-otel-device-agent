package dev.code6x.otelagent.collect

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Runs commands as the shell user through Shizuku (https://github.com/RikkaApps/Shizuku). */
object ShizukuShell {
    /** What the app can do with Shizuku now. [key] is the metric attribute value. */
    enum class State(val key: String, val text: String) {
        NOT_RUNNING("not_running", "Shizuku is not running (not installed, or not started since the last boot)"),
        TOO_OLD("too_old", "Shizuku is too old (version 11 or newer is needed)"),
        NO_PERMISSION("no_permission", "Shizuku runs, but this app has no Shizuku permission"),
        READY("ready", "ready"),
    }

    const val PERMISSION_REQUEST = 4

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.NOT_RUNNING
            Shizuku.isPreV11() -> State.TOO_OLD
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
            else -> State.READY
        }
    } catch (e: RuntimeException) {
        // The binder can die between the calls.
        State.NOT_RUNNING
    }

    /** Thrown when a collector cannot run because Shizuku is not [State.READY]. */
    class NotAvailable(val state: State) : IOException("not available: ${state.text}")

    /** Runs [cmd] (no shell; the first item is the program) and returns its standard output. */
    fun run(vararg cmd: String, timeoutMs: Long = 10_000): String = read(*cmd, timeoutMs = timeoutMs) { it.joinToString("\n") }

    /**
     * Runs [cmd] and gives its output lines to [parse], so a long output is not held in memory.
     * Throws [NotAvailable] when Shizuku is not ready, [IOException] on a time-out, an exit code
     * other than 0, or a failure.
     */
    fun <T> read(vararg cmd: String, timeoutMs: Long = 10_000, parse: (Sequence<String>) -> T): T {
        val s = state()
        if (s != State.READY) throw NotAvailable(s)
        // Shizuku.newProcess is private in API 13; the service binder has the same call.
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder()) ?: throw NotAvailable(State.NOT_RUNNING)
        val process = try {
            service.newProcess(arrayOf(*cmd), null, null)
        } catch (e: Exception) {
            throw IOException("Shizuku could not start ${cmd[0]}: $e", e)
        }
        try {
            process.outputStream?.close()
            // Error output is read and dropped, so the command cannot block on a full pipe.
            process.errorStream?.let { err ->
                Thread {
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(err).use { it.copyTo(NullOutput) }
                    } catch (_: IOException) {
                    }
                }.apply { isDaemon = true; start() }
            }
            val out = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
            var result: Result<T>? = null
            val reader = Thread {
                result = try {
                    Result.success(out.bufferedReader().useLines(parse))
                } catch (e: Throwable) {
                    // Also OutOfMemoryError: an uncaught error here would end the app.
                    Result.failure(e)
                }
            }.apply { isDaemon = true; start() }
            reader.join(timeoutMs)
            if (reader.isAlive || !process.waitForTimeout(1000, TimeUnit.MILLISECONDS.toString())) {
                try {
                    out.close()
                } catch (_: IOException) {
                }
                throw IOException("${cmd[0]}: no answer in $timeoutMs ms")
            }
            val r = result!!.getOrElse { throw IOException("${cmd[0]}: output not read: $it", it) }
            val code = process.exitValue()
            if (code != 0) throw IOException("${cmd[0]}: exit code $code")
            return r
        } finally {
            try {
                process.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private object NullOutput : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
