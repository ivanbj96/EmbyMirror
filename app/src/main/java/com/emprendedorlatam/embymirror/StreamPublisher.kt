package com.emprendedorlatam.embymirror

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque

class QueueInputStream : InputStream() {
    private val queue = LinkedBlockingDeque<ByteArray>(256)
    private var current: ByteArray? = null
    private var index = 0
    @Volatile private var closed = false

    fun offer(bytes: ByteArray) {
        if (closed) return
        if (!queue.offer(bytes)) {
            queue.pollFirst()
            queue.offer(bytes)
        }
    }

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        while (current == null || index >= current!!.size) {
            current = try {
                queue.take()
            } catch (_: InterruptedException) {
                return -1
            }
            index = 0
            if (closed) return -1
        }
        val source = current!!
        val toCopy = minOf(len, source.size - index)
        System.arraycopy(source, index, b, off, toCopy)
        index += toCopy
        return toCopy
    }

    override fun close() {
        closed = true
        queue.clear()
        queue.offer(ByteArray(0))
        super.close()
    }
}

class StreamPublisher {
    private val clients = CopyOnWriteArrayList<QueueInputStream>()

    fun registerClient(): QueueInputStream {
        val client = QueueInputStream()
        clients.add(client)
        return client
    }

    fun unregisterClient(client: QueueInputStream) {
        clients.remove(client)
        runCatching { client.close() }
    }

    fun broadcast(packet: ByteArray) {
        val iterator = clients.iterator()
        while (iterator.hasNext()) {
            val client = iterator.next()
            try {
                client.offer(packet)
            } catch (_: IOException) {
                unregisterClient(client)
            } catch (_: Exception) {
                unregisterClient(client)
            }
        }
    }

    fun closeAll() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }
}
