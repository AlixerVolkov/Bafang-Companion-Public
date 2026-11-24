import dev.alixer.bafang.protocol.*

fun main() {
    fun checkVector(expected: String, actual: ByteArray) {
        check(FrameCodec.hex(actual) == expected) { "Expected [$expected], got [${FrameCodec.hex(actual)}]" }
    }
    checkVector("55 AA 01 11 10 01 00 04 D8 FF", EkdProtocol.authChallengeRead())
    checkVector("55 AA 02 11 A5 02 A4 03 00 9E FE", EkdProtocol.assistWrite(3))
    checkVector("55 AA 02 11 A5 02 A6 00 00 9F FE", EkdProtocol.lightWrite(true))
    checkVector("55 AA 02 11 A5 02 A6 01 00 9E FE", EkdProtocol.lightWrite(false))
    checkVector("55 AA 01 11 F1 02 02 00 F8 FE", NavEncoder.stop())
    checkVector("55 AA 01 11 A5 01 A4 01 A2 FE", EkdProtocol.assistRead())
    checkVector("55 AA 12 11 F1 03 00 00 02 96 00 00 01 90 01 00 03 B0 04 00 02 62 3B 00 00 68 FC",
        NavEncoder.frame(0, 150, 1, 400, 3, 1200, 2, 15202))
    checkVector("55 AA 12 11 F1 03 00 00 02 96 00 00 01 90 01 00 03 B0 04 00 02 82 3B 00 00 48 FC",
        NavEncoder.frame(0, 150, 1, 400, 3, 1200, 2, 15234))
    val a = FrameCodec.unhex("55 AA 05 A5 11 06 01 00 00 E8 03 02")
    val b = FrameCodec.unhex("55 AA 05 A5 06 01 00 00 E8 03 02")
    for (f in listOf(a,b)) {
        val decoded = FrameCodec.decodeReceive(f + FrameCodec.checksum(f)) ?: error("RX parser failed")
        val value = TelemetryDecoder.decode(decoded) as Telemetry.SpeedAssist
        check(value.speedKmh == 10.0 && value.assistLevel == 2)
    }
    val corrupt = a + FrameCodec.checksum(a)
    corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
    check(FrameCodec.decodeReceive(corrupt) == null)
    check(EkdProtocol.authenticationResponse(byteArrayOf(1,2,3,4)).size == 16)
    println("OK — 11 codec checks: frames, nav, RX layouts, telemetry, invalid checksum, AES challenge")
}