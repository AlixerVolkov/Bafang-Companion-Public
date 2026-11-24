package dev.alixer.bafang

import android.location.Location
import dev.alixer.bafang.protocol.NavStepDistances
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*

/** Cycling route from community routing.openstreetmap.de. Requires internet to plan, not to follow. */
object Routing {
    data class Turn(val point: GeoPoint, val progressM: Double, val code: Int, val label: String)
    data class Route(val line: List<GeoPoint>, val turns: List<Turn>, val meters: Double, val target: GeoPoint)
    data class Position(val distanceAlongM: Double, val distanceOffM: Double, val distanceToEndM: Double,
                        val turnIndex: Int, val nextMeters: Double)

    private fun dist(a: GeoPoint, b: GeoPoint): Double {
        val lat = Math.toRadians((a.latitude+b.latitude)/2)
        val x = Math.toRadians(b.longitude-a.longitude)*cos(lat)
        val y = Math.toRadians(b.latitude-a.latitude)
        return hypot(x,y)*6371000.0
    }
    fun fetch(from: GeoPoint, to: GeoPoint): Route {
        val coord = {p:GeoPoint -> String.format(Locale.US,"%.6f,%.6f",p.longitude,p.latitude)}
        val address = "https://routing.openstreetmap.de/routed-bike/route/v1/driving/${coord(from)};${coord(to)}?overview=full&geometries=geojson&steps=true"
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent","BafangCompanion/0.10 (Android cycling client)")
        try {
            if (conn.responseCode != 200) error("Servidor de rutas: HTTP ${conn.responseCode}")
            val json = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            if (json.optString("code") != "Ok") error("No se ha encontrado ruta ciclista")
            val route = json.getJSONArray("routes").getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val line = (0 until coords.length()).map { i ->
                val pair=coords.getJSONArray(i); GeoPoint(pair.getDouble(1),pair.getDouble(0))
            }
            require(line.size>=2) { "Ruta vacía" }
            val length = DoubleArray(line.size)
            for(i in 1 until line.size) length[i]=length[i-1]+dist(line[i-1],line[i])
            val steps = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            val turns = (0 until steps.length()).mapNotNull { i ->
                val step=steps.getJSONObject(i)
                val m=step.getJSONObject("maneuver")
                val loc=m.getJSONArray("location")
                val point=GeoPoint(loc.getDouble(1),loc.getDouble(0))
                val type=m.optString("type", "")
                val modifier=m.optString("modifier", "straight")
                val code=if(type=="arrive") 10 else turnCode(modifier)
                val progress=nearest(point,line,length).first
                if(i==0 && type=="depart") null else Turn(point,progress,code,when(type){"arrive"->"Llegada";"roundabout","rotary"->"Rotonda";else->modifier})
            }.sortedBy { it.progressM }
            return Route(line,turns,length.last(),to)
        } finally { conn.disconnect() }
    }

    private fun turnCode(modifier:String):Int=when(modifier.lowercase(Locale.ROOT)) {
        "left"->2;"right"->3;"slight left"->4;"slight right"->5;
        "sharp left"->6;"sharp right"->7;"uturn","u-turn"->8;"straight"->1;else->1
    }

    private fun nearest(point:GeoPoint,line:List<GeoPoint>,length:DoubleArray):Pair<Double,Double>{
        var best=Double.MAX_VALUE; var along=0.0
        val cosLat=cos(Math.toRadians(point.latitude))
        for(i in 0 until line.size-1){
            val a=line[i];val b=line[i+1]
            val ax=(a.longitude-point.longitude)*cosLat*111320.0
            val ay=(a.latitude-point.latitude)*110540.0
            val bx=(b.longitude-point.longitude)*cosLat*111320.0
            val by=(b.latitude-point.latitude)*110540.0
            val dx=bx-ax;val dy=by-ay
            val t=if(dx*dx+dy*dy<1e-8) 0.0 else (-(ax*dx+ay*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
            val d=hypot(ax+t*dx,ay+t*dy)
            if(d<best){best=d;along=length[i]+t*(length[i+1]-length[i])}
        }
        return along to best
    }

    fun position(route:Route,here:GeoPoint,minProgressM:Double=0.0):Position{
        val prefix=DoubleArray(route.line.size)
        for(i in 1 until route.line.size)prefix[i]=prefix[i-1]+dist(route.line[i-1],route.line[i])
        val (rawAlong,off)=nearest(here,route.line,prefix)
        // Keep progressing along the route even if GPS drifts a few metres backwards.
        val along=if(off<=50.0) maxOf(rawAlong,minProgressM).coerceAtMost(route.meters) else rawAlong
        val index=route.turns.indexOfFirst { it.progressM >= along-4 }.let {if(it<0) route.turns.size else it}
        return Position(along,off,(route.meters-along).coerceAtLeast(0.0),index,
            ((route.turns.getOrNull(index)?.progressM?:route.meters)-along).coerceAtLeast(0.0))
    }
    fun navPayload(route:Route,pos:Position):List<Pair<Int,Int>> =
        NavStepDistances.nextThree(pos.distanceAlongM,
            route.turns.drop(pos.turnIndex).map { NavStepDistances.Turn(it.progressM,it.code) })
}