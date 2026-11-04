package com.obra.asistencia.data.backup

import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import com.obra.asistencia.data.local.AppDatabase
import com.obra.asistencia.data.local.TruckAttendanceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Administra el respaldo de los registros en la memoria MicroSD externa del teléfono.
 * 
 * Android monta el almacenamiento extraíble secundario bajo getExternalFilesDirs().
 * Si hay una MicroSD insertada, el índice [1] corresponde a dicha tarjeta externa.
 */
class SdCardBackupManager(private val context: Context) {

    /**
     * Localiza la ruta física del directorio de trabajo en la MicroSD extraíble.
     */
    private fun getMicroSdDirectory(): File? {
        val dirs = ContextCompat.getExternalFilesDirs(context, "backup_obra")
        // dirs[0] es la memoria interna; dirs[1] es la tarjeta Micro SD externa si está presente
        val sdDir = if (dirs.size > 1 && dirs[1] != null && Environment.getExternalStorageState(dirs[1]) == Environment.MEDIA_MOUNTED) {
            dirs[1]
        } else {
            // Fallback a almacenamiento externo principal si no hay MicroSD física insertada
            dirs.getOrNull(0)
        }

        sdDir?.mkdirs()
        return sdDir
    }

    /**
     * Agrega el registro inmediatamente al archivo de respaldo en la Micro SD (JSON Lines).
     * Se invoca en cada escaneo NFC para garantizar respaldo físico instantáneo.
     */
    suspend fun appendRecordToSdCard(record: TruckAttendanceEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = getMicroSdDirectory() ?: return@withContext false
            val backupFile = File(dir, "asistencia_camiones_backup.jsonl")

            val json = JSONObject(record.toJsonMap()).toString()

            FileWriter(backupFile, true).use { writer ->
                writer.append(json)
                writer.append("\n")
                writer.flush()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Copia completa del archivo .db de SQLite interno hacia la tarjeta Micro SD.
     */
    suspend fun backupFullDatabase(db: AppDatabase): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = getMicroSdDirectory() ?: return@withContext false
            val dbPath = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            if (!dbPath.exists()) return@withContext false

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val destFile = File(dir, "asistencia_db_backup_$timeStamp.sqlite")

            FileInputStream(dbPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
