package com.yadhuChoudhary.MyRuns4
import com.google.android.gms.maps.model.LatLng
import java.io.*

object LocationUtils {

    /**
     * Serializes a list of LatLng to ByteArray
     */
    fun serializeLocationList(locations: List<LatLng>): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        val objectOutputStream = ObjectOutputStream(byteArrayOutputStream)

        // Write the size of the list
        objectOutputStream.writeInt(locations.size)

        // Write each LatLng
        for (location in locations) {
            objectOutputStream.writeDouble(location.latitude)
            objectOutputStream.writeDouble(location.longitude)
        }

        objectOutputStream.close()
        return byteArrayOutputStream.toByteArray()
    }

    /**
     * Deserializes ByteArray to a list of LatLng
     */
    fun deserializeLocationList(data: ByteArray): List<LatLng> {
        if (data.isEmpty()) return emptyList()

        val byteArrayInputStream = ByteArrayInputStream(data)
        val objectInputStream = ObjectInputStream(byteArrayInputStream)

        val locations = mutableListOf<LatLng>()

        try {
            // Read the size of the list
            val size = objectInputStream.readInt()

            // Read each LatLng
            for (i in 0 until size) {
                val latitude = objectInputStream.readDouble()
                val longitude = objectInputStream.readDouble()
                locations.add(LatLng(latitude, longitude))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            objectInputStream.close()
        }

        return locations
    }
}