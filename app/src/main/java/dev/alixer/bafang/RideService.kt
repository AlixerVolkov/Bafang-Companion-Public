package dev.alixer.bafang

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import dev.alixer.bafang.protocol.Telemetry
import dev.alixer.bafang.protocol.LiveGuidancePolicy
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Foreground service preserves BLE and navigation when phone screen turns off. */
class RideService: Service(), BikeBleManager.Listener, LocationListener {
    companion object { @Volatile var active:RideService?=null; private const val CHANNEL="ride";private const val NOTIFICATION=1604 }
    interface Observer { fun onState(); fun onLog(message:String); fun onDevice(device:BluetoothDevice,label:String) }
    var observer:Observer?=null
    lateinit var ble:BikeBleManager
        private set
    var status="Sin conectar"
        private set
    /** Experimental BLE speed from undocumented per-firmware fields; NEVER primary speed. */
    var speedKmh:Double?=null
        private set
    /** GPS speed is independent of the unverified B02NC BLE speed field. */
    val gpsSpeedKmh:Double?
        get() {
            val fix = gps ?: return null
            if (!fix.hasSpeed() || !fix.hasAccuracy() || fix.accuracy > 35f) return null
            val ageNs = SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos
            if (ageNs < 0L || ageNs > 10_000_000_000L) return null
            return (fix.speed.toDouble() * 3.6).coerceAtLeast(0.0)
        }
    var batteryRaw:Int?=null
        private set
    var distanceKm:Double?=null
        private set
    /** Field-correlated values from B02NC telemetry; battery requires another charge-level test. */
    var b02ncBatteryCandidate:Int?=null
        private set
    var b02ncTripKm:Double?=null
        private set
    var b02ncOdometerKm:Double?=null
        private set
    var b02ncRideSecondsCandidate:Long?=null
        private set
    var b02ncAvgKmhRaw:Double?=null
        private set
    var b02ncBrightnessCandidate:Int?=null
        private set
    val b02ncAvgCalculatedKmh:Double?
        get(){
            val sec=b02ncRideSecondsCandidate ?: return null
            val trip=b02ncTripKm ?: return null
            return if(sec>0 && trip>=0.0) trip*3600.0/sec.toDouble() else null
        }
    var assist:Int=0
        private set
    var gps:Location?=null
        private set
    var trackMeters=0.0
        private set
    val track=mutableListOf<Location>()
    var route:Routing.Route?=null
        private set
    /** A route can be previewed without sending BLE turn-by-turn frames. */
    var navigationActive=false
        private set
    var navigationText="Busca una dirección y calcula la ruta"
        private set
    var routingBusy=false
        private set
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newSingleThreadExecutor()
    private var ticker:Runnable?=null
    private var offRouteCount=0
    private var lastReroute=0L
    private var navSeq=0
    private var lastNavPush=-1L
    private var lastNavTurn=-1
    private var lastNavReport=-1L
    private var navProgressM=0.0
    private var routeRevision=0L
    private var lastBleNavReady=false
    private var arrivedFrames=0
    private var arrivalStopAt=0L
    private lateinit var lm:LocationManager
    private var lastSaved=0L

    override fun onCreate(){
        super.onCreate(); active=this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Bafang Companion · viaje",NotificationManager.IMPORTANCE_LOW))
        ble=BikeBleManager(applicationContext,this)
        lm=getSystemService(LOCATION_SERVICE) as LocationManager
        startInForeground()
        startGps()
        startTicker()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int = START_STICKY
    override fun onBind(intent:Intent?)=null

    private fun notification():Notification{
        val intent=Intent(this,MainActivity::class.java)
        val pi=PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Bafang Companion")
            .setContentText("${status} · GPS y navegación activos").setContentIntent(pi).setOngoing(true).build()
    }
    private fun startInForeground(){
        if(Build.VERSION.SDK_INT>=34)
            startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION,notification())
    }
    private fun refresh(){
        observer?.onState()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification())
    }
    @SuppressLint("MissingPermission")
    fun startGps(){
        val ok=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!ok){log("Concede permiso de ubicación para GPS");return}
        for(provider in listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)){
            try { lm.requestLocationUpdates(provider,1500L,3f,this,Looper.getMainLooper()) }
            catch (_:Exception){}
        }
        try {val last=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if(last!=null) onLocationChanged(last)}catch (_:Exception){}
    }
    private fun freshGps(fix: Location?): Boolean {
        if(fix == null || !fix.hasAccuracy()) return false
        val ageMs=(SystemClock.elapsedRealtimeNanos()-fix.elapsedRealtimeNanos)/1_000_000L
        return LiveGuidancePolicy.usableFix(ageMs,fix.accuracy.toDouble())
    }

    private fun setNavigationMessage(message:String){
        if(navigationText!=message) {
            navigationText=message
            refresh()
        }
    }

    override fun onLocationChanged(location:Location){
        // Ignore older provider callbacks; they can reverse turn-by-turn directions.
        val last=gps
        if(last!=null && location.elapsedRealtimeNanos<last.elapsedRealtimeNanos) return
        gps=location
        val prev=track.lastOrNull()
        if(prev==null || location.time>prev.time && location.distanceTo(prev)>4f){
            if(prev!=null && location.distanceTo(prev)<1000f) trackMeters+=location.distanceTo(prev)
            track.add(Location(location))
        }
        val r=route
        if(navigationActive && r!=null && freshGps(location) && !routingBusy) {
            val position=Routing.position(r,GeoPoint(location.latitude,location.longitude),navProgressM)
            if(position.distanceOffM>LiveGuidancePolicy.MAX_OFF_ROUTE_M) offRouteCount++ else offRouteCount=0
            if(offRouteCount>=3 && SystemClock.elapsedRealtime()-lastReroute>30_000L){
                lastReroute=SystemClock.elapsedRealtime()
                offRouteCount=0
                setNavigationMessage("Fuera de ruta · recalculando recorrido ciclista…")
                log("NAV desviación confirmada; solicitando ruta nueva al destino")
                planRoute(r.target,rerouting=true)
            }
        }
        if(navigationActive) sendLiveGuidance()
        if(System.currentTimeMillis()-lastSaved>30_000){lastSaved=System.currentTimeMillis();saveTrack()}
        observer?.onState()
    }

    private fun sendLiveGuidance(force:Boolean=false){
        if(!navigationActive) return
        val r=route ?: return
        if(routingBusy) return
        if(!ble.navigationReady) {
            setNavigationMessage("Navegación en pausa · reconecta B02NC")
            return
        }
        val fix=gps
        if(!freshGps(fix)) {
            val detail = when {
                fix == null -> "sin posición"
                !fix.hasAccuracy() -> "precisión desconocida"
                else -> {
                    val ageMs=(SystemClock.elapsedRealtimeNanos()-fix.elapsedRealtimeNanos)/1_000_000L
                    "precisión ${fix.accuracy.toInt()} m · antigüedad ${(ageMs/1000L).coerceAtLeast(0L)} s"
                }
            }
            setNavigationMessage("Navegación activada · esperando GPS fiable ($detail)")
            return
        }
        val location=fix ?: return
        val position=Routing.position(r,GeoPoint(location.latitude,location.longitude),navProgressM)
        if(position.distanceOffM>LiveGuidancePolicy.MAX_OFF_ROUTE_M){
            setNavigationMessage("Fuera de ruta (${position.distanceOffM.roundToInt()} m) · esperando recálculo")
            return
        }
        navProgressM=LiveGuidancePolicy.progress(navProgressM,position.distanceAlongM)
        val now=SystemClock.elapsedRealtime()
        // Arrival requires a near-end route projection and a usable, on-route GPS fix.
        if(LiveGuidancePolicy.arrived(position.distanceToEndM,position.distanceOffM)){
            if(arrivedFrames<3 && (lastNavPush<0L || now-lastNavPush>=600L)){
                ble.navUpdate(navSeq++,0,10,0,10,0,10,0)
                arrivedFrames++
                lastNavPush=now
                setNavigationMessage("Destino alcanzado · finalizando navegación")
                if(arrivedFrames==3) arrivalStopAt=now+4_000L
            }
            if(arrivalStopAt>0 && now>=arrivalStopAt){
                ble.stopNavigation()
                route=null
                navigationActive=false
                arrivalStopAt=0L
                log("NAV finalizada: destino alcanzado, pantalla liberada")
                setNavigationMessage("Has llegado a tu destino")
            }
            return
        }
        if(!LiveGuidancePolicy.shouldSend(now,lastNavPush,position.turnIndex,lastNavTurn,force))return
        val moves=Routing.navPayload(r,position)
        val next=moves[0]
        val remaining=position.distanceToEndM.roundToInt().coerceAtLeast(0)
        ble.navUpdate(navSeq++,moves[0].first,moves[0].second,
            moves[1].first,moves[1].second,moves[2].first,moves[2].second,remaining.toLong())
        val changedTurn=position.turnIndex!=lastNavTurn
        lastNavPush=now
        lastNavTurn=position.turnIndex
        val instruction=r.turns.getOrNull(position.turnIndex)?.label ?: "Llegada"
        setNavigationMessage("$instruction · ${next.first} m · quedan $remaining m")
        // Periodic diagnostics verify actual route progression without flooding the BLE log.
        if(force || changedTurn || lastNavReport<0L || now-lastNavReport>=15_000L){
            log("NAV B02NC TX: giro=${next.second} en ${next.first} m · resto=$remaining m · paso=${position.turnIndex+1}/${r.turns.size}")
            lastNavReport=now
        }
    }

    private fun startTicker(){
        ticker=object:Runnable {
            override fun run(){
                if(navigationActive) sendLiveGuidance()
                main.postDelayed(this,500L)
            }
        }
        main.post(ticker!!)
    }

    /** Calculates a preview; only startNavigation() activates turn-by-turn BLE output. */
    fun planRoute(target:GeoPoint,rerouting:Boolean=false){
        if(routingBusy)return
        if(!rerouting) {
            if(navigationActive && ble.navigationReady) ble.stopNavigation()
            navigationActive=false
            route=null
        }
        val origin=gps ?: run {setNavigationMessage("No hay posición GPS para calcular la ruta");log(navigationText);return}
        // Preview may use an approximate last location; BLE guidance remains strictly gated.
        // Do not regress route planning indoors, where GPS accuracy often exceeds 40 m.
        if(!freshGps(origin)) log("GPS aproximado para previsualización; al iniciar se exigirá una señal precisa")
        val revision=++routeRevision
        routingBusy=true
        navigationText=if(rerouting) "Recalculando ruta ciclista…" else "Calculando ruta ciclista…"
        refresh()
        val start=GeoPoint(origin.latitude,origin.longitude)
        io.execute {
            try {
                val planned=Routing.fetch(start,target)
                main.post {
                    if(revision!=routeRevision)return@post
                    route=planned
                    routingBusy=false
                    navSeq=0;lastNavPush=-1L;lastNavTurn=-1;lastNavReport=-1L
                    navProgressM=0.0
                    offRouteCount=0;arrivedFrames=0;arrivalStopAt=0L
                    if(rerouting && navigationActive){
                        navigationText="Nueva ruta calculada · reanudando indicaciones"
                        log("NAV recálculo: ${planned.meters.roundToInt()} m, ${planned.turns.size} giros")
                        sendLiveGuidance(force=true)
                    }else{
                        navigationText="Ruta lista: ${planned.meters.roundToInt()} m · ${planned.turns.size} indicaciones. Pulsa Iniciar navegación."
                        log("Ruta PREPARADA: ${planned.meters.roundToInt()} m, ${planned.turns.size} giros · pendiente de iniciar")
                    }
                    refresh()
                }
            }catch(e:Exception){
                main.post {
                    if(revision!=routeRevision)return@post
                    routingBusy=false
                    navigationText="No se pudo calcular la ruta: ${e.message}"
                    log(navigationText)
                    refresh()
                }
            }
        }
    }

    /** Explicit action after route preview; starts verified F1/03/00 BLE navigation. */
    fun startNavigation():Boolean {
        val r=route
        if(r==null || routingBusy) {
            setNavigationMessage("Primero calcula una ruta y espera al resultado")
            return false
        }
        if(!ble.navigationReady){
            setNavigationMessage("Conecta y autentica B02NC para enviar las flechas")
            return false
        }
        // Allow the user to arm navigation while indoors or with coarse GPS.
        // sendLiveGuidance() still refuses to transmit any BLE directions until
        // a fresh and accurate fix is available, then resumes automatically.
        val gpsReady=freshGps(gps)
        navigationActive=true
        navSeq=0;lastNavPush=-1L;lastNavTurn=-1;lastNavReport=-1L
        navProgressM=0.0
        arrivedFrames=0;arrivalStopAt=0L;offRouteCount=0
        log("NAV ACTIVADA: ${r.meters.roundToInt()} m, ${r.turns.size} giros · GPS ${if(gpsReady) "listo" else "pendiente"}")
        if(gpsReady) sendLiveGuidance(force=true)
        else setNavigationMessage("Navegación activada · esperando mejor señal GPS para enviar flechas")
        refresh()
        return true
    }
    fun stopRoute(){
        ++routeRevision
        route=null
        routingBusy=false
        navigationActive=false
        navProgressM=0.0;lastNavPush=-1L;lastNavTurn=-1
        arrivedFrames=0;arrivalStopAt=0
        navigationText="Ruta detenida · selecciona otro destino"
        ble.stopNavigation()
        log("NAV detenida por el usuario; pantalla liberada")
        refresh()
    }
    fun clearTrip(){track.clear();trackMeters=0.0;refresh()}
    fun exportGpx():String{
        val df=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}
        return buildString{
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Bafang Companion\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>Bafang Ride</name><trkseg>\n")
            for(p in track){append(String.format(Locale.US,"<trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele><time>%s</time></trkpt>\n",p.latitude,p.longitude,p.altitude,df.format(Date(p.time))))}
            append("</trkseg></trk></gpx>\n")
        }
    }
    private fun saveTrack(){try { openFileOutput("ride-autosave.gpx",MODE_PRIVATE).bufferedWriter().use{it.write(exportGpx())} } catch (_:Exception){} }
    override fun log(message:String){android.util.Log.i("BafangCompanion",message);observer?.onLog(message)}
    override fun deviceFound(device:BluetoothDevice,label:String){observer?.onDevice(device,label)}
    override fun state(connected:Boolean,authenticated:Boolean,name:String){
        status=when { authenticated->"$name · autenticada"; connected->"$name · BLE conectado";else->"Desconectado"}
        val ready=ble.navigationReady
        if(navigationActive && ready && !lastBleNavReady){
            log("NAV: B02NC reconectada; enviando instrucciones actualizadas")
            lastNavPush=-1L
            sendLiveGuidance(force=true)
        }
        lastBleNavReady=ready
        refresh()
    }
    override fun telemetry(value:Telemetry){
        when(value){
            is Telemetry.SpeedAssist->{if(!value.speedKmh.isNaN())speedKmh=value.speedKmh;assist=value.assistLevel}
            is Telemetry.DistanceRawBattery->{distanceKm=value.distanceKm;batteryRaw=value.batteryRaw}
            is Telemetry.B02NcStatus->{
                assist=value.assistLevel
                speedKmh=null
                batteryRaw=null
                distanceKm=null
                b02ncBatteryCandidate=value.batteryPercentCandidate
                b02ncTripKm=value.tripKm
                b02ncOdometerKm=value.odometerKm
            }
            is Telemetry.B02NcRideStats->{
                b02ncRideSecondsCandidate=value.rideSecondsCandidate
                b02ncAvgKmhRaw=value.averageKmhRaw
                b02ncBrightnessCandidate=value.brightnessLevelCandidate
            }
        }
        observer?.onState()
    }
    override fun onDestroy(){
        ticker?.let(main::removeCallbacks);ticker=null
        try{lm.removeUpdates(this)}catch(_:Exception){}
        saveTrack();ble.disconnect();io.shutdownNow();active=null
        super.onDestroy()
    }
}