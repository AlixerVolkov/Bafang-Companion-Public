package dev.alixer.bafang

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import dev.alixer.bafang.protocol.*
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.min

/** BLE transport implementing the documented EKD01 family profile, with feature-gated writes.
 * B02NC compatibility is based on the user's BIKEGO confirmation; protocol-level matching
 * requires the actual Nordic UART GATT service, and an accepted handshake.
 */
class BikeBleManager(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun log(message: String)
        fun deviceFound(device: BluetoothDevice, label: String)
        fun state(connected: Boolean, authenticated: Boolean, name: String)
        fun telemetry(value: Telemetry)
    }
    private val main = Handler(Looper.getMainLooper())
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var scanCb: ScanCallback? = null
    private val seen = mutableSetOf<String>()
    private var gatt: BluetoothGatt? = null
    private var savedDevice: BluetoothDevice? = null
    private var requestedDisconnect = true
    private var reconnectCount = 0
    private var retry: Runnable? = null
    private var authTimeout: Runnable? = null
    private var keepAlive: Runnable? = null
    private var gattName = ""
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var mtuTries = 0
    private var subscribed = false
    private var authenticated = false
    private var authStage = 0
    private var writeBusy = false
    private val writeQueue = ArrayDeque<ByteArray>()
    private val diagnosticLastAt = mutableMapOf<Int,Long>()
    private val diagnosticLastHex = mutableMapOf<Int,String>()
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val assistSelection = AssistSelection()
    val assistLevel: Int get() = assistSelection.level
    val pendingAssistLevel: Int? get() = assistSelection.pendingLevel
    var brightnessLevel: Int? = null
        private set
    /** Last light command, not a physical light readback. Null on reconnection. */
    var requestedLightsOn: Boolean? = null
        private set
    var reportedLightsOn: Boolean? = null
        private set
    private var lightRequestedAtMs = 0L
    /** Prefer display telemetry; protect recent command from stale notifications. */
    val lightsOnForUi: Boolean?
        get() {
            val request = requestedLightsOn
            if (request != null && SystemClock.elapsedRealtime() - lightRequestedAtMs < 2000L) return request
            return reportedLightsOn ?: request
        }
    val isAuthenticated get() = authenticated && subscribed && gatt != null
    val navigationReady get() = isAuthenticated && mtu >= 30
    val deviceName get() = gattName

    private fun log(message: String) = main.post { listener.log(message) }
    private fun reportState() = main.post { listener.state(subscribed && gatt != null, isAuthenticated, gattName) }

    fun hasPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (!hasPermissions()) { log("Sin permisos Bluetooth"); return }
        val scanner = adapter.bluetoothLeScanner ?: run { log("Activa Bluetooth"); return }
        stopScan(); seen.clear()
        scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                if (!seen.add(d.address)) return
                val name = result.scanRecord?.deviceName ?: d.name ?: return
                val candidate = name.startsWith("B02",true) || name.startsWith("EKD",true) || name.contains("BAFANG",true)
                if (candidate) main.post { listener.deviceFound(d, "$name · ${result.rssi} dBm") }
            }
            override fun onScanFailed(errorCode: Int) { log("Escáner BLE: error $errorCode") }
        }
        scanner.startScan(scanCb)
        log("Buscando pantallas B02 / EKD / Bafang…")
        val active = scanCb
        main.postDelayed({ if (scanCb === active) stopScan() }, 17000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCb?.let { try { adapter.bluetoothLeScanner?.stopScan(it) } catch (_:Exception) {} }
        scanCb = null
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan(); requestedDisconnect = true; closeGatt()
        savedDevice = device; requestedDisconnect = false; reconnectCount = 0
        openGatt()
    }

    @SuppressLint("MissingPermission")
    private fun openGatt() {
        val device = savedDevice ?: return
        if (!hasPermissions()) return
        try {
            gattName = device.name ?: "Bafang BLE"
            log("Conectando a $gattName")
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            reportState()
        } catch (e: Exception) { log("Conexión: ${e.message}"); scheduleRetry() }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        requestedDisconnect = true
        retry?.let(main::removeCallbacks); retry = null
        closeGatt()
        gattName = ""
        reportState()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        authTimeout?.let(main::removeCallbacks); authTimeout = null
        keepAlive?.let(main::removeCallbacks); keepAlive = null
        authenticated = false; subscribed = false; authStage = 0; mtu = 23
        assistSelection.reset(); brightnessLevel = null; requestedLightsOn = null; reportedLightsOn = null; lightRequestedAtMs = 0L
        writeChar = null; notifyChar = null; writeQueue.clear(); writeBusy = false
        val old = gatt; gatt = null
        if (old != null) try { old.disconnect(); old.close() } catch (_:Exception) {}
    }

    /** Rebuild GATT completely after a failed operation; otherwise the UI can stay connected
     * while auth is false and the reconnect loop never runs.
     * All callers should execute on the main thread.
     */
    private fun recoverTransport(reason: String) {
        if (requestedDisconnect) return
        log("BLE recuperación: $reason")
        closeGatt()
        reportState()
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (requestedDisconnect || savedDevice == null || reconnectCount >= 8) return
        val delay = min(30_000L, 1_000L shl min(reconnectCount, 5))
        reconnectCount++
        log("Reconexión en ${delay/1000}s (intento $reconnectCount)")
        retry?.let(main::removeCallbacks)
        retry = Runnable { if (!requestedDisconnect) openGatt() }
        main.postDelayed(retry!!, delay)
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            if (client !== gatt) return
            main.post {
                if (client !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    reconnectCount = 0; log("BLE enlazado · descubriendo GATT"); client.discoverServices()
                } else {
                    log("BLE desconectado (estado $newState / código $status)")
                    closeGatt(); reportState(); scheduleRetry()
                }
            }
        }
        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            if (client !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                main.post { if (client === gatt) recoverTransport("descubrimiento GATT $status") }
                return
            }
            client.services.forEach { service ->
                log("GATT ${service.uuid}")
                service.characteristics.forEach { log("  ${it.uuid} props ${it.properties}") }
            }
            val nus = client.getService(UUID.fromString(EkdProtocol.NUS_SERVICE))
            val tx = nus?.getCharacteristic(UUID.fromString(EkdProtocol.WRITE_CHARACTERISTIC))
            val rx = nus?.getCharacteristic(UUID.fromString(EkdProtocol.NOTIFY_CHARACTERISTIC))
            if (tx == null || rx == null ||
                tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
                rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                log("Pantalla sin perfil EKD01/NUS: comandos desactivados")
                reportState(); return
            }
            writeChar = tx; notifyChar = rx
            mtuTries = 1
            if (!client.requestMtu(64)) subscribe(client)
        }
        override fun onMtuChanged(client: BluetoothGatt, size: Int, status: Int) {
            if (client !== gatt) return
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) size else 23
            log("MTU: $mtu")
            if (mtu < 30 && mtuTries < 3) {
                mtuTries++
                main.postDelayed({ if (client === gatt) client.requestMtu(64) }, 500)
            } else subscribe(client)
        }
        @Suppress("DEPRECATION")
        private fun subscribe(client: BluetoothGatt) {
            val rx = notifyChar ?: return
            if (!client.setCharacteristicNotification(rx, true)) {
                main.post { if (client === gatt) recoverTransport("no se pudo habilitar Notify") }
                return
            }
            val d = rx.getDescriptor(cccd) ?: run {
                main.post { if (client === gatt) recoverTransport("falta descriptor CCCD") }
                return
            }
            val ok = if (Build.VERSION.SDK_INT >= 33)
                client.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            else {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                client.writeDescriptor(d)
            }
            if (!ok) main.post { if (client === gatt) recoverTransport("error iniciando CCCD") }
        }
        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (client !== gatt || descriptor.uuid != cccd) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                main.post { if (client === gatt) recoverTransport("suscripción Notify $status") }
                return
            }
            subscribed = true; reportState()
            log("NUS disponible; iniciando autenticación compatible EKD01/B02NC")
            main.postDelayed({ if (client === gatt && subscribed) startAuth() }, 700)
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == notifyChar?.uuid) onRx(client, characteristic.value.clone())
        }
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == notifyChar?.uuid) onRx(client, value.clone())
        }
        override fun onCharacteristicWrite(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (client !== gatt) return
            main.post {
                if (client !== gatt) return@post
                writeBusy = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    recoverTransport("escritura GATT $status")
                } else sendNext()
            }
        }
    }

    private fun startAuth() {
        if (!subscribed || gatt == null || authStage != 0 || mtu < 28) {
            if (mtu < 28) log("MTU insuficiente para autenticación (mínimo 28)")
            return
        }
        authStage = 1
        log("Solicitando desafío de autenticación")
        send(EkdProtocol.authChallengeRead())
        authTimeout?.let(main::removeCallbacks)
        authTimeout = Runnable {
            if (authStage == 1 || authStage == 2) {
                recoverTransport("autenticación sin respuesta")
            }
        }
        main.postDelayed(authTimeout!!, 6000)
    }

    private fun onRx(client: BluetoothGatt, data: ByteArray) {
        if (client !== gatt) return
        val packet = FrameCodec.decodeReceive(data)
        if (packet == null) { log("RX no reconocido: ${FrameCodec.hex(data)}"); return }
        // Captures are rate-limited; we need raw data to validate speed offset and battery meaning.
        if (authenticated && packet.sub==0x06 && packet.param in listOf(0x01,0x09)) {
            val now = SystemClock.elapsedRealtime()
            val hex = FrameCodec.hex(packet.payload)
            val last = diagnosticLastAt[packet.param] ?: 0L
            if (now-last>=15000L || diagnosticLastHex[packet.param]!=hex) {
                diagnosticLastAt[packet.param]=now;diagnosticLastHex[packet.param]=hex
                log("BLE telemetría RAW param=0x${packet.param.toString(16).uppercase()} datos=$hex")
            }
        }
        if(authenticated && packet.param==0x1C && packet.sub in listOf(0x01,0x04)) {
            log("BLE batería A6/1C RAW: ${FrameCodec.hex(packet.payload)} (24 bytes sin decodificar)")
        }
        val b02ncProfile = gattName.startsWith("B02NC", ignoreCase = true)
        val t = TelemetryDecoder.decode(packet, b02ncProfile)
        when (t) {
            is Telemetry.SpeedAssist -> {
                updateAssistFromBike(t.assistLevel)
                main.post { listener.telemetry(t.copy(assistLevel = assistLevel)) }
            }
            is Telemetry.B02NcStatus -> {
                updateAssistFromBike(t.assistLevel)
                if (t.lightsOn != null) reportedLightsOn = t.lightsOn
                main.post { listener.telemetry(t.copy(assistLevel = assistLevel)) }
            }
            is Telemetry.B02NcRideStats -> {
                main.post { listener.telemetry(t) }
            }
            null -> Unit
            else -> main.post { listener.telemetry(t) }
        }
        if (packet.param == 0xA4 && packet.sub in listOf(0x01,0x04,0x05) && packet.payload.isNotEmpty()) {
            updateAssistFromBike(FrameCodec.u(packet.payload[0]))
            main.post { listener.telemetry(Telemetry.SpeedAssist(Double.NaN, assistLevel)) }
        }
        if (authStage == 1 && packet.param == 0 && packet.payload.size == 4 && packet.sub in listOf(0x01,0x04)) {
            authStage = 2
            val auth = EkdProtocol.authenticate(packet.payload)
            log("Desafío de cuatro bytes recibido; respondiendo")
            send(auth)
        } else if (authStage == 2 && packet.param == 0 && packet.payload.isNotEmpty() && packet.sub in listOf(0x20,0x04)) {
            authTimeout?.let(main::removeCallbacks); authTimeout = null
            if (FrameCodec.u(packet.payload[0]) == 0) {
                authStage = 3; authenticated = true
                reportState(); log("Autenticación aceptada por la pantalla")
                val now = System.currentTimeMillis() / 1000L
                send(FrameCodec.write(0x10, 0x3E, byteArrayOf(now.toByte(),(now shr 8).toByte(),(now shr 16).toByte(),(now shr 24).toByte())))
                send(EkdProtocol.modelRead()); send(EkdProtocol.nameRead()); send(EkdProtocol.assistRead())
                send(FrameCodec.read(0xA6,0x1C,24))
                scheduleHeartbeat()
            } else { authStage = 0; log("Autenticación rechazada por la pantalla") }
        } else if (authenticated && packet.sub in listOf(0x01,0x04) && packet.param in listOf(0x18,0x88)) {
            val text = packet.payload.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
            if (text.isNotBlank()) log("Identificación de pantalla: $text")
        }
    }

    private fun updateAssistFromBike(level: Int) {
        when (assistSelection.observe(level, SystemClock.elapsedRealtime())) {
            AssistSelection.Feedback.CONFIRMED -> log("Asistencia confirmada por pantalla: $level/5")
            AssistSelection.Feedback.DIFFERENT_AFTER_TIMEOUT ->
                log("La pantalla sigue en asistencia $level/5: no se confirmó el nivel solicitado")
            AssistSelection.Feedback.INVALID -> log("Nivel de asistencia BLE fuera de rango: $level")
            else -> Unit
        }
    }

    private fun scheduleHeartbeat() {
        keepAlive?.let(main::removeCallbacks)
        keepAlive = Runnable {
            if (isAuthenticated) {
                send(EkdProtocol.assistRead())
                scheduleHeartbeat()
            }
        }
        main.postDelayed(keepAlive!!, 15_000)
    }

    private fun send(data: ByteArray) {
        if (gatt == null || !subscribed) return
        if (data.size > mtu - 3) { log("Paquete ${data.size} bytes excede MTU $mtu"); return }
        if (writeQueue.size >= 30) { log("Cola BLE saturada; descartando mensaje"); return }
        writeQueue.addLast(data)
        main.post { sendNext() }
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun sendNext() {
        if (writeBusy || writeQueue.isEmpty()) return
        val client = gatt ?: return
        val tx = writeChar ?: return
        val bytes = writeQueue.removeFirst()
        writeBusy = true
        val ok = if (Build.VERSION.SDK_INT >= 33)
            client.writeCharacteristic(tx,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else {
            tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            tx.value = bytes
            client.writeCharacteristic(tx)
        }
        if (!ok) { writeBusy=false; recoverTransport("WriteRequest no iniciado") }
    }

    fun setAssist(level: Int) {
        if (!isAuthenticated || level !in 0..5) return
        // Store the requested value before TX; old telemetry may arrive from earlier polls.
        assistSelection.request(level, SystemClock.elapsedRealtime())
        send(EkdProtocol.assistWrite(level))
        log("Asistencia solicitada: $level/5")
        main.post { listener.telemetry(Telemetry.SpeedAssist(Double.NaN, assistLevel)) }
    }
    fun setLights(on: Boolean) {
        if (!isAuthenticated) return
        requestedLightsOn = on
        lightRequestedAtMs = SystemClock.elapsedRealtime()
        send(EkdProtocol.lightWrite(on))
        log(if (on) "Luces: encendido solicitado" else "Luces: apagado solicitado")
        reportState()
    }
    fun setBrightness(level: Int) {
        if (!isAuthenticated || level !in 1..5) return
        brightnessLevel = level
        send(EkdProtocol.brightnessWrite(level))
        log("Brillo solicitado: $level/5 (sin lectura de confirmación en el protocolo)")
        reportState()
    }
    fun setUnit(metric: Boolean) { if (isAuthenticated) send(FrameCodec.write(0xA5,0xE0, byteArrayOf(if(metric) 0 else 1))) }
    fun navUpdate(sequence: Int, curMeters: Int, curTurn: Int, nextMeters: Int, nextTurn: Int,
                  thirdMeters: Int, thirdTurn: Int, remainingMeters: Long) {
        if (navigationReady) send(NavEncoder.frame(sequence and 255,curMeters.coerceIn(0,0xFFFFFF),curTurn,
            nextMeters.coerceIn(0,0xFFFFFF),nextTurn,thirdMeters.coerceIn(0,0xFFFFFF),thirdTurn,
            remainingMeters.coerceIn(0L,0xFFFFFFFFL)))
    }
    fun stopNavigation() { if (navigationReady) send(NavEncoder.stop()) }
}