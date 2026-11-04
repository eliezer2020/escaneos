package com.obra.asistencia.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entidad de base de datos interna SQLite (Room).
 * Representa cada registro de asistencia de camión de carga en el punto de entrega.
 */
@Entity(
    tableName = "asistencia_camiones",
    indices = [
        Index(value = ["sincronizado"]),
        Index(value = ["timestampEpoch"])
    ]
)
data class TruckAttendanceEntity(
    @PrimaryKey
    val id: String,                    // Identificador único (UUID)
    val tagId: String,                 // UID de la tarjeta NFC del camión
    val truckCode: String,             // Número o código de camión (ej. CAM-104)
    val truckPlates: String,           // Placas del camión
    val driverName: String,            // Nombre del chofer
    val materialType: String,          // Tipo de material de carga
    val volumeM3: Double,              // Volumen o tonelaje
    val deliveryPoint: String,         // Punto de entrega en la obra civil
    val timestamp: String,             // Timestamp formateado ISO-8601
    val timestampEpoch: Long,          // Timestamp en milisegundos Unix
    val latitude: Double,              // Latitud GPS
    val longitude: Double,             // Longitud GPS
    val accuracyMeters: Float,         // Precisión del GPS en metros
    val sincronizado: Boolean = false  // Bandera booleana: false si no se ha persistido en MySQL
) {
    /**
     * Convierte la entidad a un Map compatible con JSON para el endpoint PUT.
     */
    fun toJsonMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "tag_id" to tagId,
            "truck_code" to truckCode,
            "truck_plates" to truckPlates,
            "driver_name" to driverName,
            "material_type" to materialType,
            "volume_m3" to volumeM3,
            "delivery_point" to deliveryPoint,
            "timestamp" to timestamp,
            "timestamp_epoch" to timestampEpoch,
            "latitude" to latitude,
            "longitude" to longitude,
            "accuracy_meters" to accuracyMeters,
            "sincronizado" to sincronizado
        )
    }
}
