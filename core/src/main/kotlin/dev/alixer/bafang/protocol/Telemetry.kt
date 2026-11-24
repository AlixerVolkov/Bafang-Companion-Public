package dev.alixer.bafang.protocol

/** Unknown and unverified fields are deliberately NOT interpreted as reliable readings. */
sealed class Telemetry {
    data class SpeedAssist(val speedKmh: Double, val assistLevel: Int,
                           val speedRaw: Int? = null) : Telemetry()
    data class DistanceRawBattery(val distanceKm: Double, val batteryRaw: Int,
                                  val batteryPercentCandidate: Int?) : Telemetry()

    /**
     * B02NC_SMK_BF_N06 field-correlated SUB=06 PARAM=01 payload.
     * Photo: PAS 5, battery 78%, trip and odo 184.7 km.
     * Battery mapping has one real charge-level match; keep it provisional.
     */
    data class B02NcStatus(val assistLevel: Int, val batteryPercentCandidate: Int,
                           val tripKm: Double, val odometerKm: Double,
                           val maxAssistLevel: Int, val lightsOn: Boolean?) : Telemetry()
    /** Offsets calibrated against on-device 23:57–23:59 samples. */
    data class B02NcRideStats(val rideSecondsCandidate: Long, val averageKmhRaw: Double,
                              val brightnessLevelCandidate: Int?) : Telemetry()
}

object TelemetryDecoder {
    fun decode(packet: RxFrame, b02ncProfile: Boolean = false): Telemetry? {
        if (packet.sub != 0x06) return null
        val d = packet.payload
        // Field validation on real B02NC_SMK_BF_N06: old EKD01 offsets are wrong here.
        // [2:4] falsely yields 2.59 km/h while the display reads 0.0.
        // B02NC PARAM01: [4]=lights (0=off, 1=on), [5]=current PAS, [6]=PAS maximum, [7]=battery.
        // [11..14] TRIP/100; [15..18] ODO/100. PARAM09: [0..3] riding time,
        // [4..5] AVG*100; [12] correlates with light state, not brightness.
        if (b02ncProfile) {
            fun u16(i:Int):Int = FrameCodec.u(d[i]) or (FrameCodec.u(d[i+1]) shl 8)
            fun u32(i:Int):Long = (0..3).fold(0L){sum,n -> sum or (FrameCodec.u(d[i+n]).toLong() shl (8*n))}
            return when(packet.param) {
                0x01 -> {
                    if(d.size<19) return null
                    val pas=FrameCodec.u(d[5])
                    val maxPas=FrameCodec.u(d[6])
                    val battery=FrameCodec.u(d[7])
                    if(maxPas !in 1..5 || pas !in 0..maxPas || battery !in 0..100) return null
                    val trip=u32(11)/100.0
                    val odo=u32(15)/100.0
                    if(trip>1_000_000 || odo>1_000_000) return null
                    Telemetry.B02NcStatus(pas,battery,trip,odo,maxPas,
                        when(FrameCodec.u(d[4])) { 0 -> false; 1 -> true; else -> null })
                }
                0x09 -> {
                    if(d.size<13) return null
                    val rideSec=u32(0)
                    val avg=u16(4)/100.0
                    if(rideSec>500_000_000L || avg>150.0) return null
                    // PARAM09[12] toggles 0x55/0x51 with light OFF/ON in the latest field capture.
                    // Never present it as a physical backlight brightness reading.
                    Telemetry.B02NcRideStats(rideSec,avg,null)
                }
                else -> null
            }
        }
        return when (packet.param) {
            0x01 -> {
                if (d.size < 5) null else {
                    val speed = FrameCodec.u(d[2]) or (FrameCodec.u(d[3]) shl 8)
                    val assist = FrameCodec.u(d[4])
                    if (assist > 5) null else Telemetry.SpeedAssist(speed / 100.0, assist, speed)
                }
            }
            0x09 -> {
                if (d.size < 7) null else {
                    val dist = (FrameCodec.u(d[0]).toLong() or
                        (FrameCodec.u(d[1]).toLong() shl 8) or
                        (FrameCodec.u(d[2]).toLong() shl 16) or
                        (FrameCodec.u(d[3]).toLong() shl 24))
                    val raw = FrameCodec.u(d[6])
                    Telemetry.DistanceRawBattery(dist / 1000.0, raw,
                        raw.takeIf { it in 0..100 })
                }
            }
            else -> null
        }
    }
}