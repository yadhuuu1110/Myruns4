package com.yadhuChoudhary.MyRuns3

import com.google.android.gms.maps.model.LatLng
import java.io.*

object LocationUtils {

    /**
     * Serialize a list of LatLng coordinates to ByteArray for database storage
     */
    fun serializeLocationList(locations: List<LatLng>): ByteArray {
        return try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            val objectOutputStream = ObjectOutputStream(byteArrayOutputStream)

            // Convert LatLng to serializable format
            val serializableList = ArrayList<SerializableLatLng>()
            locations.forEach { latLng ->
                serializableList.add(SerializableLatLng(latLng.latitude, latLng.longitude))
            }

            objectOutputStream.writeObject(serializableList)
            objectOutputStream.close()
            byteArrayOutputStream.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            ByteArray(0)
        }
    }

    /**
     * Deserialize ByteArray back to list of LatLng coordinates
     */
    fun deserializeLocationList(byteArray: ByteArray): List<LatLng> {
        if (byteArray.isEmpty()) return emptyList()

        return try {
            val byteArrayInputStream = ByteArrayInputStream(byteArray)
            val objectInputStream = ObjectInputStream(byteArrayInputStream)

            @Suppress("UNCHECKED_CAST")
            val serializableList = objectInputStream.readObject() as ArrayList<SerializableLatLng>
            objectInputStream.close()

            // Convert back to LatLng
            serializableList.map { LatLng(it.latitude, it.longitude) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Calculate total distance from a list of coordinates
     * @return distance in miles
     */
    fun calculateTotalDistance(locations: List<LatLng>): Double {
        if (locations.size < 2) return 0.0

        var totalDistance = 0.0
        for (i in 0 until locations.size - 1) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                locations[i].latitude,
                locations[i].longitude,
                locations[i + 1].latitude,
                locations[i + 1].longitude,
                results
            )
            totalDistance += results[0] / 1609.34 // Convert meters to miles
        }

        return totalDistance
    }

    /**
     * Calculate elevation gain from a list of coordinates with altitude
     * @return climb in feet
     */
    fun calculateClimb(altitudes: List<Double>): Double {
        if (altitudes.size < 2) return 0.0

        var totalClimb = 0.0
        for (i in 0 until altitudes.size - 1) {
            val change = altitudes[i + 1] - altitudes[i]
            if (change > 0) {
                totalClimb += change * 3.28084 // Convert meters to feet
            }
        }

        return totalClimb
    }

    /**
     * Data class for serializable LatLng
     */
    data class SerializableLatLng(
        val latitude: Double,
        val longitude: Double
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}