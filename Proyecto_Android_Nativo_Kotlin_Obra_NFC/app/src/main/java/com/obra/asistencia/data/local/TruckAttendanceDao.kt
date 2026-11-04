package com.obra.asistencia.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Operaciones directas sobre la base de datos interna SQLite.
 */
@Dao
interface TruckAttendanceDao {

    /**
     * Inserción del registro tomado al escanear la tarjeta NFC.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TruckAttendanceEntity): Long

    /**
     * Consulta todos los registros que aún no se han persistido en el servidor MySQL central.
     * Estos son los que se enviarán al final de la jornada.
     */
    @Query("SELECT * FROM asistencia_camiones WHERE sincronizado = 0 ORDER BY timestampEpoch ASC")
    suspend fun getPendingSyncRecords(): List<TruckAttendanceEntity>

    /**
     * Marca un registro como sincronizado una vez que el servidor central responde exitosamente.
     */
    @Query("UPDATE asistencia_camiones SET sincronizado = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String)

    /**
     * Flujo reactivo del número de registros pendientes para la insignia en la pantalla del operador.
     */
    @Query("SELECT COUNT(*) FROM asistencia_camiones WHERE sincronizado = 0")
    fun getPendingCountFlow(): Flow<Int>

    /**
     * Historial completo de la jornada para auditoría del operador.
     */
    @Query("SELECT * FROM asistencia_camiones ORDER BY timestampEpoch DESC")
    fun getAllRecordsFlow(): Flow<List<TruckAttendanceEntity>>

    /**
     * Obtiene todos los registros en lista única (para respaldo en MicroSD).
     */
    @Query("SELECT * FROM asistencia_camiones ORDER BY timestampEpoch ASC")
    suspend fun getAllRecordsDirect(): List<TruckAttendanceEntity>
}
