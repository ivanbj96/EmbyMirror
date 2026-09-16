package com.emprendedorlatam.embymirror

import java.io.ByteArrayOutputStream
import kotlin.math.min

class TsMuxer {
    private val videoPid = 0x0101
    private val pmtPid = 0x0100
    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0

    fun muxAccessUnit(nals: List<ByteArray>, ptsUs: Long, sendTables: Boolean): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        if (sendTables) {
            packets.add(buildPat())
            packets.add(buildPmt())
        }

        val annexB = H264Utils.withStartCodes(nals)
        val pes = buildPes(annexB, ptsUs)
        packets.addAll(packetizePes(videoPid, pes, ptsUs, isKeyFrame = true))
        return packets
    }

    private fun buildPes(payload: ByteArray, ptsUs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x00, 0x01, 0xE0.toByte()))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(0x80)
        out.write(0x80)
        out.write(0x05)
        out.write(encodePts(ptsUs * 90L))
        out.write(payload)
        return out.toByteArray()
    }

    private fun packetizePes(pid: Int, pes: ByteArray, pts90k: Long, isKeyFrame: Boolean): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        var first = true
        while (offset < pes.size) {
            val packet = ByteArray(188) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = (((if (first) 0x40 else 0x00) or ((pid shr 8) and 0x1F))).toByte()
            packet[2] = (pid and 0xFF).toByte()

            val payloadStart = 4
            var cursor = payloadStart
            if (first) {
                packet[3] = (0x30 or (ccVideo and 0x0F)).toByte()
                val adaptationLength = 7
                packet[4] = adaptationLength.toByte()
                packet[5] = if (isKeyFrame) 0x50.toByte() else 0x10.toByte()
                writePcr(packet, 6, pts90k)
                cursor = 12
            } else {
                packet[3] = (0x10 or (ccVideo and 0x0F)).toByte()
            }
            ccVideo = (ccVideo + 1) and 0x0F

            val room = 188 - cursor
            val toCopy = min(room, pes.size - offset)
            System.arraycopy(pes, offset, packet, cursor, toCopy)
            offset += toCopy

            if (toCopy < room) {
                val stuffing = room - toCopy
                if ((packet[3].toInt() and 0x20) == 0) {
                    packet[3] = (packet[3].toInt() or 0x20).toByte()
                    System.arraycopy(packet, 4, packet, 5 + stuffing, cursor - 4)
                    packet[4] = (stuffing - 1).toByte()
                    if (stuffing > 1) {
                        packet[5] = 0x00
                        for (i in 6 until 5 + stuffing) packet[i] = 0xFF.toByte()
                    }
                }
            }
            packets.add(packet)
            first = false
        }
        return packets
    }

    private fun buildPat(): ByteArray {
        val packet = ByteArray(188) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = 0x40
        packet[2] = 0x00
        packet[3] = (0x10 or (ccPat and 0x0F)).toByte()
        ccPat = (ccPat + 1) and 0x0F
        val section = byteArrayOf(
            0x00,
            0xB0.toByte(), 0x0D,
            0x00, 0x01,
            0xC1.toByte(),
            0x00,
            0x00,
            0x00, 0x01,
            (0xE0 or ((pmtPid shr 8) and 0x1F)).toByte(),
            (pmtPid and 0xFF).toByte(),
            0x2E, (0x70).toByte(), 0x19, 0x05
        )
        packet[4] = 0x00
        System.arraycopy(section, 0, packet, 5, section.size)
        return packet
    }

    private fun buildPmt(): ByteArray {
        val packet = ByteArray(188) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or ((pmtPid shr 8) and 0x1F)).toByte()
        packet[2] = (pmtPid and 0xFF).toByte()
        packet[3] = (0x10 or (ccPmt and 0x0F)).toByte()
        ccPmt = (ccPmt + 1) and 0x0F
        val section = byteArrayOf(
            0x02,
            0xB0.toByte(), 0x12,
            0x00, 0x01,
            0xC1.toByte(),
            0x00,
            0x00,
            (0xE0 or ((videoPid shr 8) and 0x1F)).toByte(),
            (videoPid and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
            0x1B,
            (0xE0 or ((videoPid shr 8) and 0x1F)).toByte(),
            (videoPid and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
            0x15, 0xBD.toByte(), 0x4D, 0x56
        )
        packet[4] = 0x00
        System.arraycopy(section, 0, packet, 5, section.size)
        return packet
    }

    private fun encodePts(pts: Long): ByteArray {
        val value = pts and 0x1FFFFFFFFL
        return byteArrayOf(
            (((value shr 29) and 0x0E) or 0x21).toByte(),
            ((value shr 22) and 0xFF).toByte(),
            ((((value shr 14) and 0xFE) or 0x01)).toByte(),
            ((value shr 7) and 0xFF).toByte(),
            ((((value shl 1) and 0xFE) or 0x01)).toByte()
        )
    }

    private fun writePcr(packet: ByteArray, offset: Int, pcrBase: Long) {
        val base = pcrBase and 0x1FFFFFFFFL
        packet[offset] = (base shr 25).toByte()
        packet[offset + 1] = (base shr 17).toByte()
        packet[offset + 2] = (base shr 9).toByte()
        packet[offset + 3] = (base shr 1).toByte()
        packet[offset + 4] = (((base and 0x1) shl 7) or 0x7E).toByte()
        packet[offset + 5] = 0x00
    }
}
