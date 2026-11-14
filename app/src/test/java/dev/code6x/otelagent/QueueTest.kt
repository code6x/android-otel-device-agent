package dev.code6x.otelagent

import dev.code6x.otelagent.export.FileQueue
import dev.code6x.otelagent.export.Poster
import dev.code6x.otelagent.export.QueueSender
import dev.code6x.otelagent.export.Target
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class QueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private fun queue(maxBytes: Long = 1_000_000, maxAgeMs: Long = 3_600_000) =
        FileQueue(tmp.newFolder(), maxBytes, maxAgeMs) { now }

    private val target = Target("http://example.com/v1/metrics", emptyMap(), 5)

    @Test fun filesKeepTheirOrder() {
        val q = queue()
        q.add(byteArrayOf(1))
        q.add(byteArrayOf(2)) // same millisecond
        now += 10
        q.add(byteArrayOf(3))
        assertEquals(listOf<Byte>(1, 2, 3), q.files().map { it.readBytes()[0] })
    }

    @Test fun sizeCapDropsOldestFirst() {
        val q = queue(maxBytes = 250)
        repeat(5) {
            q.add(ByteArray(100) { _ -> it.toByte() })
            now += 1000
        }
        // 5 x 100 bytes over a 250 byte cap: the 3 oldest are dropped.
        assertEquals(listOf<Byte>(3, 4), q.files().map { it.readBytes()[0] })
        assertEquals(200, q.sizeBytes())
        assertEquals(3, q.dropped)
    }

    @Test fun maxAgeDropsOldFiles() {
        val q = queue(maxAgeMs = 60_000)
        q.add(byteArrayOf(1))
        now += 30_000
        q.add(byteArrayOf(2))
        now += 31_000 // the first is now 61 s old
        assertEquals(1, q.trim())
        assertEquals(listOf<Byte>(2), q.files().map { it.readBytes()[0] })
        now += 60_000
        q.add(byteArrayOf(3)) // add trims too
        assertEquals(listOf<Byte>(3), q.files().map { it.readBytes()[0] })
        assertEquals(2, q.dropped)
    }

    @Test fun halfWrittenFilesAreRemovedAtStart() {
        val dir = tmp.newFolder()
        File(dir, "0000000000001-0000.pb.tmp").writeBytes(byteArrayOf(9))
        File(dir, "0000000000002-0000.pb").writeBytes(byteArrayOf(1))
        val q = FileQueue(dir, 1000, 1_000_000_000_000) { now }
        assertEquals(1, q.files().size)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test fun senderDeletesOnlyAfter2xxAndKeepsOrder() {
        val q = queue()
        repeat(3) { q.add(byteArrayOf(it.toByte())) }
        val posted = mutableListOf<Byte>()
        val codes = ArrayDeque(listOf(200, 503))
        val sender = QueueSender(q, Poster { _, body -> posted += body[0]; codes.removeFirst() })
        val r = sender.drain(target)
        assertEquals(1, r.sent)
        assertEquals(2, r.left)
        assertEquals("HTTP 503", r.error)
        assertEquals(listOf<Byte>(0, 1), posted)
        assertEquals(listOf<Byte>(1, 2), q.files().map { it.readBytes()[0] })
    }

    @Test fun networkErrorKeepsEverything() {
        val q = queue()
        repeat(2) { q.add(byteArrayOf(it.toByte())) }
        val sender = QueueSender(q, Poster { _, _ -> throw IOException("timeout") })
        val r = sender.drain(target)
        assertEquals(0, r.sent)
        assertEquals(2, r.left)
        assertNotNull(r.error)
    }

    @Test fun rejectedBodyIsDroppedSoItDoesNotBlock() {
        val q = queue()
        repeat(2) { q.add(byteArrayOf(it.toByte())) }
        val codes = ArrayDeque(listOf(400, 204))
        val r = QueueSender(q, Poster { _, _ -> codes.removeFirst() }).drain(target)
        assertEquals(1, r.sent)
        assertEquals(1, r.dropped)
        assertEquals(0, r.left)
    }

    @Test fun authErrorsNotFoundAndRedirectsKeepTheQueue() {
        for (code in listOf(301, 302, 401, 403, 404, 405, 408, 415, 500, 502)) {
            val q = queue()
            repeat(3) { q.add(byteArrayOf(it.toByte())) }
            var posts = 0
            val r = QueueSender(q, Poster { _, _ -> posts++; code }).drain(target)
            assertEquals("HTTP $code keeps all files", 3, r.left)
            assertEquals("HTTP $code stops at the first file", 1, posts)
            assertEquals(0, r.dropped)
        }
    }

    @Test fun tooManyRequestsIsRetried() {
        val q = queue()
        q.add(byteArrayOf(7))
        val r = QueueSender(q, Poster { _, _ -> 429 }).drain(target)
        assertEquals(1, r.left)
        assertEquals("HTTP 429", r.error)
    }

    @Test fun noEndpointKeepsTheQueue() {
        val q = queue()
        q.add(byteArrayOf(1))
        val r = QueueSender(q, Poster { _, _ -> error("must not post") }).drain(null)
        assertEquals(1, r.left)
        assertEquals("no endpoint set", r.error)
    }

    @Test fun bodyIsPostedUnchanged() {
        val q = queue()
        val body = byteArrayOf(10, 20, 30)
        q.add(body)
        var got: ByteArray? = null
        QueueSender(q, Poster { _, b -> got = b; 200 }).drain(target)
        assertArrayEquals(body, got)
        assertNull(q.files().firstOrNull())
    }
}
