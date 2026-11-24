package dev.alixer.bafang.protocol

/** Wire format from the supplied EKD01-BF protocol revision 1.3 (not verified on B02NC-BF). */
object FrameCodec {
    private const val SYNC1 = 0x55
    private const val SYNC2 = 0xAA

    fun encode(target: Int, sub: Int, param: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        for (v in intArrayOf(target, sub, param)) require(v in 0..255) { "Frame field outside byte range" }
        require(payload.size <= 255) { "Payload too large" }
        val pre = byteArrayOf(SYNC1.toByte(), SYNC2.toByte(), payload.size.toByte(),
            0x11, target.toByte(), sub.toByte(), param.toByte()) + payload
        return pre + checksum(pre)
    }

    fun checksum(bytes: ByteArray): ByteArray {
        val sum = bytes.sumOf { it.toInt() and 0xFF }
        val first = (0xFE - (sum and 0xFF)) and 0xFF
        val second = (0x100 - ((sum shr 8) and 0xFF)) and 0xFF
        return byteArrayOf(first.toByte(), second.toByte())
    }

    /** Incoming frames have two documented layouts; length and BOTH checksums must agree. */
    fun decodeReceive(frame: ByteArray): RxFrame? {
        if (frame.size < 8 || u(frame[0]) != SYNC1 || u(frame[1]) != SYNC2) return null
        val preferred = if (u(frame[4]) == 0x11) intArrayOf(7, 6) else intArrayOf(6, 7)
        for (header in preferred) {
            if (frame.size != header + u(frame[2]) + 2) continue
            val raw = frame.copyOfRange(0, frame.size - 2)
            val check = checksum(raw)
            if (check[0] != frame[frame.size - 2] || check[1] != frame[frame.size - 1]) continue
            return if (header == 7) {
                RxFrame(u(frame[3]), u(frame[5]), u(frame[6]),
                    frame.copyOfRange(7, frame.size - 2), RxLayout.A)
            } else {
                RxFrame(u(frame[3]), u(frame[4]), u(frame[5]),
                    frame.copyOfRange(6, frame.size - 2), RxLayout.B)
            }
        }
        return null
    }

    fun read(target: Int, param: Int, expectedSize: Int): ByteArray {
        require(expectedSize in 1..255)
        return encode(target, 0x01, param, byteArrayOf(expectedSize.toByte()))
    }

    fun write(target: Int, param: Int, payload: ByteArray): ByteArray =
        encode(target, 0x02, param, payload)

    fun u(b: Byte): Int = b.toInt() and 0xFF

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(u(it)) }
    fun unhex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

enum class RxLayout { A, B }

data class RxFrame(
    val command: Int,
    val sub: Int,
    val param: Int,
    val payload: ByteArray,
    val layout: RxLayout,
) {
    // RX command position is uncertain in the source protocol: do not require it for correlation.
    fun matches(subExpected: Int, paramExpected: Int): Boolean =
        param == paramExpected && (sub == subExpected || sub == 0x04)
}