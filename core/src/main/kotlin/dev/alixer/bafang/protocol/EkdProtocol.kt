package dev.alixer.bafang.protocol

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object EkdProtocol {
    const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val WRITE_CHARACTERISTIC = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val NOTIFY_CHARACTERISTIC = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    const val HID_SERVICE = "00001812-0000-1000-8000-00805f9b34fb"
    const val MIN_NAV_MTU = 30

    /** Device-protocol constant from the user-supplied EKD01 docs. NOT an application secret. */
    private val hardwareKey = "2CTDU40qNyCgTjb1".toByteArray(Charsets.US_ASCII)

    fun authenticationResponse(challenge: ByteArray): ByteArray {
        require(challenge.size == 4) { "Challenge must be exactly four bytes" }
        val plain = ByteArray(16)
        challenge.copyInto(plain)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hardwareKey, "AES"))
        return cipher.doFinal(plain)
    }

    fun authChallengeRead(): ByteArray = FrameCodec.read(0x10, 0x00, 4)
    fun authenticate(challenge: ByteArray): ByteArray =
        FrameCodec.encode(0x10, 0x20, 0x00, authenticationResponse(challenge))

    fun assistRead(): ByteArray = FrameCodec.read(0xA5, 0xA4, 1)
    fun modelRead(): ByteArray = FrameCodec.read(0xA5, 0x18, 24)
    fun nameRead(): ByteArray = FrameCodec.read(0xA5, 0x88, 24)

    /** Control commands are encoded for future experimentation but not used by the v0.1 UI. */
    fun assistWrite(level: Int): ByteArray {
        require(level in 0..5)
        return FrameCodec.write(0xA5, 0xA4, byteArrayOf(level.toByte(), 0))
    }
    fun lightWrite(on: Boolean): ByteArray =
        FrameCodec.write(0xA5, 0xA6, byteArrayOf(if (on) 0 else 1, 0))
    fun brightnessWrite(level: Int): ByteArray {
        require(level in 1..5)
        return FrameCodec.write(0xA5, 0xA7, byteArrayOf(level.toByte()))
    }
}

object NavEncoder {
    const val STRAIGHT = 1
    const val LEFT = 2
    const val RIGHT = 3
    const val SLIGHT_LEFT = 4
    const val SLIGHT_RIGHT = 5
    const val SHARP_LEFT = 6
    const val SHARP_RIGHT = 7
    const val UTURN = 8
    const val ARRIVE = 10

    fun frame(
        sequence: Int, currentMeters: Int, currentTurn: Int,
        nextMeters: Int, nextTurn: Int, thirdMeters: Int, thirdTurn: Int,
        totalMeters: Long,
    ): ByteArray {
        require(sequence in 0..255)
        require(totalMeters in 0..0xFFFFFFFFL)
        for (value in intArrayOf(currentMeters, nextMeters, thirdMeters)) require(value in 0..0xFFFFFF)
        for (turn in intArrayOf(currentTurn, nextTurn, thirdTurn)) require(turn in 0..255)
        val p = mutableListOf(sequence, 0x02)
        fun addLe(value: Long, size: Int) {
            repeat(size) { i -> p += ((value shr (8 * i)) and 0xFF).toInt() }
        }
        addLe(currentMeters.toLong(), 3); p += currentTurn
        addLe(nextMeters.toLong(), 3); p += nextTurn
        addLe(thirdMeters.toLong(), 3); p += thirdTurn
        addLe(totalMeters, 4)
        return FrameCodec.encode(0xF1, 0x03, 0x00, p.map { it.toByte() }.toByteArray())
    }

    fun stop(): ByteArray = FrameCodec.write(0xF1, 0x02, byteArrayOf(0))
}