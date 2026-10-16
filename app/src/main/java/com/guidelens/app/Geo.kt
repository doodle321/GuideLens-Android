package com.guidelens.app

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

/** Distinguishable failure causes — the spoken error depends on which this is. */
enum class GeoError { NO_INTERNET, NOT_FOUND, RATE_LIMITED, NETWORK }

sealed class GeoResult<out T> {
    data class Ok<T>(val value: T) : GeoResult<T>()
    data class Err(val error: GeoError) : GeoResult<Nothing>()
}

fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    // VALIDATED excludes captive portals and "connected but no data" Wi-Fi
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

object Geo {

    // ---------- geometry ----------
    fun hav(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val t = Math.PI / 180.0
        val dla = (b.lat - a.lat) * t
        val dlo = (b.lng - a.lng) * t
        val h = sin(dla / 2).pow(2) + cos(a.lat * t) * cos(b.lat * t) * sin(dlo / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    fun decodePolyline(str: String): List<LatLng> {
        val pts = mutableListOf<LatLng>()
        var i = 0
        var la = 0
        var ln = 0
        while (i < str.length) {
            var b: Int
            var s = 0
            var r = 0
            do { b = str[i++].code - 63; r = r or ((b and 31) shl s); s += 5 } while (b >= 32)
            la += if (r and 1 == 1) (r shr 1).inv() else (r shr 1)
            s = 0; r = 0
            do { b = str[i++].code - 63; r = r or ((b and 31) shl s); s += 5 } while (b >= 32)
            ln += if (r and 1 == 1) (r shr 1).inv() else (r shr 1)
            pts.add(LatLng(la / 1e5, ln / 1e5))
        }
        return pts
    }

    fun routeLen(coords: List<LatLng>): Double {
        var d = 0.0
        for (i in 1 until coords.size) d += hav(coords[i - 1], coords[i])
        return d
    }

    fun nearestDistToRoute(p: LatLng, coords: List<LatLng>): Double {
        var m = Double.MAX_VALUE
        for (c in coords) { val d = hav(p, c); if (d < m) m = d }
        return m
    }

    // ---------- routing model ----------
    data class Step(val type: String, val modifier: String, val name: String, val lat: Double, val lng: Double)

    fun maneuverText(st: Step): String {
        val m = st.type
        val mod = st.modifier
        val onto = if (st.name.isNotEmpty()) " on " + st.name else ""
        if (m.contains("depart")) return "Start walking" + onto
        if (m.contains("roundabout")) return "Take the roundabout"
        if (m.contains("arrive")) return "You are arriving"
        if (m.startsWith("turn") || m.startsWith("new name")) {
            return when (mod) {
                "left" -> "Turn left" + onto
                "right" -> "Turn right" + onto
                "slight left" -> "Bear slight left"
                "slight right" -> "Bear slight right"
                "sharp left" -> "Turn sharp left"
                "sharp right" -> "Turn sharp right"
                "uturn" -> "Make a U-turn"
                else -> "Continue straight" + onto
            }
        }
        return "Continue straight" + onto
    }

    data class Destination(val lat: Double, val lng: Double, val label: String)

    class Route(val coords: List<LatLng>, val steps: List<Step>, val dest: Destination) {
        var stepIdx = 0
        var announcedPre = false
        var announcedNow = false
        var lastRecalc = 0L
        var recalcCount = 0
    }

    // ---------- network (internet required) ----------
    private fun <T> httpGet(url: String, parse: (HttpURLConnection) -> T?): GeoResult<T> {
        var lastErr: GeoError = GeoError.NETWORK
        repeat(2) { attempt ->
            var c: HttpURLConnection? = null
            try {
                c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", BuildConfig.NOMINATIM_UA)
                c.connectTimeout = 12000
                c.readTimeout = 12000
                when (c.responseCode) {
                    200 -> {
                        val r = parse(c)
                        return if (r != null) GeoResult.Ok(r) else GeoResult.Err(GeoError.NOT_FOUND)
                    }
                    429 -> { lastErr = GeoError.RATE_LIMITED; return GeoResult.Err(lastErr) }
                    404 -> { lastErr = GeoError.NOT_FOUND; return GeoResult.Err(lastErr) }
                    else -> lastErr = GeoError.NETWORK
                }
            } catch (e: IOException) {
                lastErr = GeoError.NETWORK
            } catch (e: Exception) {
                lastErr = GeoError.NETWORK
            } finally {
                try { c?.disconnect() } catch (ignored: Exception) { }
            }
        }
        return GeoResult.Err(lastErr)
    }

    suspend fun geocode(context: Context, query: String): GeoResult<Destination> = withContext(Dispatchers.IO) {
        if (!isOnline(context)) return@withContext GeoResult.Err(GeoError.NO_INTERNET)
        val u = BuildConfig.NOMINATIM_BASE + "/search?format=jsonv2&limit=1&accept-language=en&email=" +
                URLEncoder.encode(BuildConfig.NOMINATIM_CONTACT, "UTF-8") +
                "&q=" + URLEncoder.encode(query, "UTF-8")
        httpGet(u) { conn ->
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            if (arr.length() == 0) null
            else {
                val o = arr.getJSONObject(0)
                Destination(o.getDouble("lat"), o.getDouble("lon"),
                    o.getString("display_name").split(",")[0].trim())
            }
        }
    }

    suspend fun fetchRoute(context: Context, from: LatLng, to: Destination): GeoResult<Route> = withContext(Dispatchers.IO) {
        if (!isOnline(context)) return@withContext GeoResult.Err(GeoError.NO_INTERNET)
        val u = BuildConfig.OSRM_BASE + "/routed-foot/route/v1/foot/" +
                "${from.lng},${from.lat};${to.lng},${to.lat}?overview=full&steps=true&geometries=polyline"
        httpGet(u) { conn ->
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            if (j.optString("code") != "Ok") null
            else {
                val rr = j.getJSONArray("routes").getJSONObject(0)
                val steps = mutableListOf<Step>()
                val legs = rr.getJSONArray("legs")
                for (li in 0 until legs.length()) {
                    val arr = legs.getJSONObject(li).getJSONArray("steps")
                    for (si in 0 until arr.length()) {
                        val st = arr.getJSONObject(si)
                        val man = st.getJSONObject("maneuver")
                        val loc = man.getJSONArray("location")
                        steps.add(Step(
                            type = man.optString("type", ""),
                            modifier = man.optString("modifier", ""),
                            name = st.optString("name", ""),
                            lat = loc.getDouble(1),
                            lng = loc.getDouble(0)
                        ))
                    }
                }
                Route(decodePolyline(rr.getString("geometry")), steps, to)
            }
        }
    }

    sealed class WhereResult {
        data class Address(val text: String) : WhereResult()
        data class Coordinates(val lat: Double, val lng: Double) : WhereResult()
        object NoInternet : WhereResult()
        object Failed : WhereResult()
    }

    /**
     * Reverse-geocode a fix into a spoken address. SUSPEND — never call on the
     * main thread (Geocoder.getFromLocation is a blocking synchronous IPC that
     * can take seconds and previously caused ANRs). API 33+ uses the async API.
     */
    suspend fun whereAmIText(context: Context, p: Location): WhereResult = withContext(Dispatchers.IO) {
        if (!isOnline(context)) return@withContext WhereResult.NoInternet
        try {
            val addrs: List<Address>? =
                if (Build.VERSION.SDK_INT >= 33) {
                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        Geocoder(context, Locale.ENGLISH).getFromLocation(
                            p.latitude, p.longitude, 1,
                            android.location.Geocoder.GeocodeListener { list ->
                                cont.resumeWith(kotlin.Result.success(list))
                            })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.ENGLISH).getFromLocation(p.latitude, p.longitude, 1)
                }
            if (!addrs.isNullOrEmpty()) {
                val a = addrs[0]
                val parts = listOfNotNull(
                    a.subThoroughfare, a.thoroughfare, a.subLocality, a.locality
                ).filter { it.isNotBlank() }
                if (parts.isEmpty()) WhereResult.Coordinates(p.latitude, p.longitude)
                else WhereResult.Address(parts.joinToString(", "))
            } else WhereResult.Coordinates(p.latitude, p.longitude)
        } catch (e: IOException) {
            if (!isOnline(context)) WhereResult.NoInternet else WhereResult.Failed
        } catch (e: Exception) {
            WhereResult.Failed
        }
    }
}
