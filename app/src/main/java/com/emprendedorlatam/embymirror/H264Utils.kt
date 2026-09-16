package com.emprendedorlatam.embymirror

import java.nio.ByteBuffer

object H264Utils {
    private val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    fun splitLengthPrefixed(buffer: ByteBuffer): List<ByteArray> {
        val dup = buffer.slice()
        val out = mutableListOf<ByteArray>()
        while (dup.remaining() > 4) {
            val size = dup.int
            if (size <= 0 || size > dup.remaining()) break
            val nal = ByteArray(size)
            dup.get(nal)
            out.add(nal)
        }
        return out
    }

    fun withStartCodes(nals: List<ByteArray>): ByteArray {
        val total = nals.sumOf { it.size + startCode.size }
        val out = ByteArray(total)
        var pos = 0
        for (nal in nals) {
            System.arraycopy(startCode, 0, out, pos, startCode.size)
            pos += startCode.size
            System.arraycopy(nal, 0, out, pos, nal.size)
            pos += nal.size
        }
        return out
    }

    fun isIdr(nal: ByteArray): Boolean {
        if (nal.isEmpty()) return false
        return (nal[0].toInt() and 0x1F) == 5
    }
}
