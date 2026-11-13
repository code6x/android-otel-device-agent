package dev.code6x.otelagent.export

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Where and how to send. */
data class Target(val url: String, val headers: Map<String, String>, val timeoutSeconds: Int)

fun interface Poster {
    /** Posts one OTLP/HTTP protobuf body; returns the HTTP status. Throws IOException on a network error. */
    fun post(target: Target, body: ByteArray): Int
}

data class SendResult(val sent: Int, val dropped: Int, val left: Int, val error: String?)

/**
 * Sends the queue in order, oldest first. Stops at the first failure, so the order is kept
 * and nothing is lost; the next call tries again.
 */
class QueueSender(private val queue: FileQueue, private val poster: Poster = HttpPoster) {
    @Synchronized
    fun drain(target: Target?): SendResult {
        queue.trim()
        if (target == null || target.url.isEmpty()) {
            return SendResult(0, 0, queue.files().size, "no endpoint set")
        }
        var sent = 0
        var dropped = 0
        var error: String? = null
        for (f in queue.files()) {
            val code = try {
                poster.post(target, f.readBytes())
            } catch (e: IOException) {
                error = "${e.javaClass.simpleName}: ${e.message}"
                break
            }
            when {
                code in 200..299 -> {
                    queue.remove(f)
                    sent++
                }
                // The receiver will never take this body: drop it, or it blocks the queue for ever.
                code in 400..499 && code != 408 && code != 429 -> {
                    queue.remove(f)
                    dropped++
                    error = "HTTP $code, file dropped"
                }
                else -> {
                    error = "HTTP $code"
                    break
                }
            }
        }
        return SendResult(sent, dropped, queue.files().size, error)
    }
}

object HttpPoster : Poster {
    override fun post(target: Target, body: ByteArray): Int {
        val c = URL(target.url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = target.timeoutSeconds * 1000
            c.readTimeout = target.timeoutSeconds * 1000
            c.setRequestProperty("Content-Type", "application/x-protobuf")
            target.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            // Read the answer so the connection can be reused.
            try {
                (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() }
            } catch (_: IOException) {
            }
            return code
        } finally {
            c.disconnect()
        }
    }
}
