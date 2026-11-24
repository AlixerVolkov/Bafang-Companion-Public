package dev.alixer.bafang.protocol

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun verify(expected: String, actual: ByteArray) =
        assertEquals(expected, FrameCodec.hex(actual))

    @Test fun checksumVectorsFromProtocolMd() {
        verify("55 AA 01 11 10 01 00 04 D8 FF", EkdProtocol.authChallengeRead())
        verify("55 AA 02 11 A5 02 A4 03 00 9E FE", EkdProtocol.assistWrite(3))
        verify("55 AA 02 11 A5 02 A6 00 00 9F FE", EkdProtocol.lightWrite(true))
        verify("55 AA 02 11 A5 02 A6 01 00 9E FE", EkdProtocol.lightWrite(false))
        verify("55 AA 01 11 A5 02 A7 03 9C FE", EkdProtocol.brightnessWrite(3))
        verify("55 AA 01 11 F1 02 02 00 F8 FE", NavEncoder.stop())
        verify("55 AA 01 11 A5 01 A4 01 A2 FE", EkdProtocol.assistRead())
    }
    @Test fun navFrameVector() {
        verify("55 AA 12 11 F1 03 00 00 02 96 00 00 01 90 01 00 03 B0 04 00 02 82 3B 00 00 48 FC",
            NavEncoder.frame(0, 150, 1, 400, 3, 1200, 2, 15234))
    }
    @Test fun rxLayoutAAndB() {
        val aPre = FrameCodec.unhex("55 AA 05 A5 11 06 01 00 00 E8 03 02")
        val bPre = FrameCodec.unhex("55 AA 05 A5 06 01 00 00 E8 03 02")
        for (bytes in listOf(aPre, bPre)) {
            val f = FrameCodec.decodeReceive(bytes + FrameCodec.checksum(bytes))!!
            assertEquals(0x06, f.sub)
            assertEquals(0x01, f.param)
            val t = TelemetryDecoder.decode(f) as Telemetry.SpeedAssist
            assertEquals(10.0, t.speedKmh, 0.001)
            assertEquals(2, t.assistLevel)
        }
    }
    @Test fun rejectsBadChecksumAndLen() {
        val pre = FrameCodec.unhex("55 AA 01 A5 11 04 A4 03")
        val valid = pre + FrameCodec.checksum(pre)
        assertNotNull(FrameCodec.decodeReceive(valid))
        val bad = valid.clone(); bad[bad.lastIndex] = (bad.last().toInt() xor 1).toByte()
        assertNull(FrameCodec.decodeReceive(bad))
        val badLen = valid.clone(); badLen[2] = 5
        assertNull(FrameCodec.decodeReceive(badLen))
    }
    @Test fun batteryFieldIsOnlyCandidate() {
        val p = FrameCodec.unhex("55 AA 07 A6 11 06 09 10 27 00 00 00 00 AA")
        val v = TelemetryDecoder.decode(FrameCodec.decodeReceive(p + FrameCodec.checksum(p))!!)
            as Telemetry.DistanceRawBattery
        assertEquals(10.0, v.distanceKm, 0.001)
        assertEquals(170, v.batteryRaw)
        assertNull(v.batteryPercentCandidate)
    }
    @Test fun b02ncPhotoCorrelatedFields() {
        val raw = FrameCodec.unhex(
            "00 00 03 00 00 05 05 4E 00 00 00 28 48 00 00 28 48 00 00 00 00"
        )
        val packet = RxFrame(0xA5, 0x06, 0x01, raw, RxLayout.A)
        val status = TelemetryDecoder.decode(packet, b02ncProfile=true) as Telemetry.B02NcStatus
        assertEquals(5, status.assistLevel)
        assertEquals(5, status.maxAssistLevel)
        assertEquals(78, status.batteryPercentCandidate)
        assertEquals(184.72, status.tripKm, 0.001)
        assertEquals(184.72, status.odometerKm, 0.001)
        // Internal bytes 03 01 generated a false 2.59 km/h in older EKD01 mapping.
        val changed=raw.clone(); changed[3]=1
        val again = TelemetryDecoder.decode(RxFrame(0xA5,0x06,0x01,changed,RxLayout.A),true)
            as Telemetry.B02NcStatus
        assertEquals(78, again.batteryPercentCandidate)
        assertEquals(5, again.assistLevel)
        // Proof from user's actual six PAS levels, with fixed maxPAS byte 6.
        for(level in 0..5) {
            val sample=raw.clone()
            sample[5]=level.toByte()
            val value=TelemetryDecoder.decode(RxFrame(0xA5,0x06,0x01,sample,RxLayout.A),true)
                as Telemetry.B02NcStatus
            assertEquals(level,value.assistLevel)
            assertEquals(5,value.maxAssistLevel)
        }
    }

    @Test fun b02ncIgnoresMislabelledParam09BatteryAndBadFields() {
        val raw09=FrameCodec.unhex("2C 7E 00 00 0A 08 23 0E 13 01 90 01 50 00 01 05")
        val avg=TelemetryDecoder.decode(RxFrame(0xA6,0x06,0x09,raw09,RxLayout.A),true)
            as Telemetry.B02NcRideStats
        assertEquals(32300L,avg.rideSecondsCandidate)
        assertEquals(20.58,avg.averageKmhRaw,0.001)
        assertNull(avg.brightnessLevelCandidate) // 0x50 remains unknown
        val sample=raw09.clone();sample[12]=0x54
        val bright=TelemetryDecoder.decode(RxFrame(0xA6,0x06,0x09,sample,RxLayout.A),true)
            as Telemetry.B02NcRideStats
        assertNull(bright.brightnessLevelCandidate) // light-correlated status byte must not masquerade as brightness
        assertEquals(20.59,184.72*3600.0/32300.0,0.005)
        val raw01=FrameCodec.unhex("00 00 03 00 00 05 05 4E 00 00 00 28 48 00 00 28 48 00 00 00 00")
        assertNull(TelemetryDecoder.decode(RxFrame(0xA5,0x06,0x01,raw01.copyOfRange(0,14),RxLayout.A),true))
        val invalid=raw01.clone();invalid[7]=0x7f
        assertNull(TelemetryDecoder.decode(RxFrame(0xA5,0x06,0x01,invalid,RxLayout.A),true))
        val badPas=raw01.clone();badPas[5]=6
        assertNull(TelemetryDecoder.decode(RxFrame(0xA5,0x06,0x01,badPas,RxLayout.A),true))
    }

    @Test fun b02ncNovember24LightAndAssistTelemetryCapture() {
        val base=FrameCodec.unhex("00 00 03 01 00 05 05 4E 00 00 00 28 48 00 00 28 48 00 00 00 00")
        val lightsOn=base.clone().apply { this[4]=1 }
        val off=TelemetryDecoder.decode(RxFrame(0xA5,0x06,0x01,base,RxLayout.A),true)
            as Telemetry.B02NcStatus
        val on=TelemetryDecoder.decode(RxFrame(0xA5,0x06,0x01,lightsOn,RxLayout.A),true)
            as Telemetry.B02NcStatus
        assertEquals(off.assistLevel,on.assistLevel)
        assertEquals(5,on.maxAssistLevel)
        assertEquals(78,on.batteryPercentCandidate)
        assertEquals(false,off.lightsOn)
        assertEquals(true,on.lightsOn)
        // The physical light transition is observed in byte 4; the status decoder
        // does not pretend the frame contains a confirmed brightness readback.
        for(level in intArrayOf(5,4,2,0)) {
            val sample=base.clone().apply { this[5]=level.toByte() }
            val status=TelemetryDecoder.decode(RxFrame(0xA5,0x06,0x01,sample,RxLayout.A),true)
                as Telemetry.B02NcStatus
            assertEquals(level,status.assistLevel)
        }
        val statOff=FrameCodec.unhex("2C 7E 00 00 0A 08 23 0E 13 01 90 01 55 00 01 05")
        val statOn=FrameCodec.unhex("2C 7E 00 00 0A 08 23 0E 13 01 90 01 51 00 01 05")
        val rideOff=TelemetryDecoder.decode(RxFrame(0xA6,0x06,0x09,statOff,RxLayout.A),true)
            as Telemetry.B02NcRideStats
        val rideOn=TelemetryDecoder.decode(RxFrame(0xA6,0x06,0x09,statOn,RxLayout.A),true)
            as Telemetry.B02NcRideStats
        assertEquals(32300L,rideOff.rideSecondsCandidate)
        assertEquals(32300L,rideOn.rideSecondsCandidate)
        assertEquals(20.58,rideOn.averageKmhRaw,0.001)
        assertNull(rideOff.brightnessLevelCandidate)
        assertNull(rideOn.brightnessLevelCandidate)
        val elapsed=FrameCodec.unhex("32 7E 00 00 0A 08 23 0E 13 01 90 01 55 00 01 05")
        val later=TelemetryDecoder.decode(RxFrame(0xA6,0x06,0x09,elapsed,RxLayout.A),true)
            as Telemetry.B02NcRideStats
        assertEquals(32306L,later.rideSecondsCandidate)
    }

    @Test fun challengeLengthAndEncryptionDeterministic() {
        assertEquals(16, EkdProtocol.authenticationResponse(byteArrayOf(1,2,3,4)).size)
        assertArrayEquals(EkdProtocol.authenticationResponse(byteArrayOf(1,2,3,4)),
            EkdProtocol.authenticationResponse(byteArrayOf(1,2,3,4)))
        try { EkdProtocol.authenticationResponse(byteArrayOf(1,2)); fail("Should reject short challenge") }
        catch (_: IllegalArgumentException) { }
    }
}