package com.wilmak.skyhookevalapp

import com.google.android.gms.maps.model.LatLng
import com.skyhookwireless.wps.WPSGeoFence
import com.skyhookwireless.wps.WPSLocation
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.*

data class GeofenceDefinition(val name: String, val lat: Double, val lng: Double, val radius: Int)
data class GeoFenceName(val name: String, val definedFence: WPSGeoFence)

enum class DistanceUnit {
    Miles, Kilometers
}

fun getHaversineDistance(pos1: LatLng, pos2: LatLng, unit: DistanceUnit): Double {
    val R = (if (unit === DistanceUnit.Miles) 3960 else 6371).toDouble()
    val lat = Math.toRadians(pos2.latitude - pos1.latitude)
    val lng = Math.toRadians(pos2.longitude - pos1.longitude)
    val h1 =
        Math.sin(lat / 2) * Math.sin(lat / 2) +
                Math.cos(Math.toRadians(pos1.latitude)) * Math.cos(Math.toRadians(pos2.latitude)) *
                Math.sin(lng / 2) * Math.sin(lng / 2)
    val h2 = 2 * Math.asin(Math.min(1.0, Math.sqrt(h1)))
    return R * h2
}

class LocationPoint(val time: String, val lat: Double, val lng: Double, val hpe: Int, val alt: Double, val speed: Double, val isPeriodic: Boolean) {

    override fun toString(): String {
        val timeStr = SimpleDateFormat("hh:mm:ss").format(Date(System.currentTimeMillis()))
        return if (isPeriodic) "P:" else "" + "Time: $timeStr " +
                "Lat:${if (lat != -1.0) lat else "N/A"} " +
                "Lng:${if (lng != -1.0) lng else "N/A"} " +
                "HPE:${if (hpe != -1) "+/-" + hpe.toString() else "N/A"} " +
                "Alt:${if (alt != -1.0) alt else "N/A"} " +
                "Speed:${if (speed != -1.0) speed else "N/A"}\n"
    }

    fun toCSString(): String {
        val timeStr = SimpleDateFormat("MM-dd hh:mm:ss").format(Date(System.currentTimeMillis()))
        return "$timeStr," +
                "${if (lat != -1.0) lat else -1.0}," +
                "${if (lng != -1.0) lng else -1.0}," +
                "${if (hpe != -1) hpe else -1}," +
                "${if (alt != -1.0) alt else -1.0}," +
                "${if (speed != -1.0) speed else -1.0}," +
                "${if (isPeriodic) "T" else "F"}"
    }
}

fun WPSLocation.toLocationPoint(isPeriodic: Boolean = false): LocationPoint {
    return LocationPoint(
        SimpleDateFormat("hh:mm:ss").format(Date(System.currentTimeMillis())),
        if (this.hasLatitude()) this.latitude else -1.0,
        if (this.hasLongitude()) this.longitude else -1.0,
        if (this.hasHPE()) this.hpe else -1,
        if (this.hasAltitude()) this.altitude else -1.0,
        if (this.hasSpeed()) this.speed else -1.0,
        isPeriodic
    )
}