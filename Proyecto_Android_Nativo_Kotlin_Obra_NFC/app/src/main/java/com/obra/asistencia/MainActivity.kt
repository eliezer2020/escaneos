package com.obra.asistencia

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.obra.asistencia.data.backup.SdCardBackupManager
import com.obra.asistencia.data.local.AppDatabase
import com.obra.asistencia.data.local.TruckAttendanceEntity
import com.obra.asistencia.data.remote.NetworkClient
import com.obra.asistencia.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Pantalla principal del Operador de Obra Civil.
 * Diseñada para ser ultra minimalista y de alta legibilidad en campo.
 * 
 * Flujo por cada escaneo NFC:
 *  1. Captura UID de la tarjeta NFC del camión.
 *  2. Obtiene coordenadas GPS (latitud, longitud, precisión) y timestamp actual.
 *  3. Guarda registro en SQLite interno (Room) -> Actualiza círculo [sqlsaved].
 *  4. Realiza respaldo físico en la memoria MicroSD externa -> Actualiza círculo [sdcardsaved].
 *  5. Intenta UN SOLO llamado HTTP PUT al servicio central -> Actualiza círculo [sqlserversaved].
 *     IMPORTANTE: Sin reintentos para no retrasar el paso de los camiones.
 *  6. Al final de la jornada, el botón "Sincronizar Jornada" envía todos los pendientes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var db: AppDatabase
    private lateinit var sdBackupManager: SdCardBackupManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicialización de componentes locales
        db = AppDatabase.getInstance(applicationContext)
        sdBackupManager = SdCardBackupManager(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            Toast.makeText(this, "Este dispositivo no cuenta con sensor NFC", Toast.LENGTH_LONG).show()
        }

        setupUI()
        observeDatabaseStats()
    }

    private fun setupUI() {
        // Estado inicial de los 3 círculos: Neutro / Espera
        resetStatusCircles()

        // Botón de sincronización de fin de jornada (en campamento con internet estable)
        binding.btnSyncEndOfDay.setOnClickListener {
            syncPendingRecordsEndOfDay()
        }

        // Acceso rápido a exportación manual de respaldo Micro SD
        binding.btnManualBackupSd.setOnClickListener {
            lifecycleScope.launch {
                val success = sdBackupManager.backupFullDatabase(db)
                val msg = if (success) "Respaldo en Micro SD completado con éxito" else "Error al escribir en Micro SD"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Habilitar despacho en primer plano (Foreground Dispatch) para capturar NFC instantáneamente
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        // Deshabilitar despacho de NFC cuando la app pasa a segundo plano
        nfcAdapter?.disableForegroundDispatch(this)
    }

    /**
     * Configuración del Foreground Dispatch de NFC para que MainActivity intercepte
     * las tarjetas sin mostrar el diálogo del sistema operativo de Android.
     */
    private fun enableNfcForegroundDispatch() {
        nfcAdapter?.let { adapter ->
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
            val filters = arrayOf(
                IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
                IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
                IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
            )
            adapter.enableForegroundDispatch(this, pendingIntent, filters, null)
        }
    }

    /**
     * Intercepción de la tarjeta NFC leída en campo.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val action = intent.action
        if (NfcAdapter.ACTION_TAG_DISCOVERED == action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == action) {

            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            tag?.let {
                val tagUidHex = bytesToHexString(it.id)
                vibrateDevice()
                processTruckAttendance(tagUidHex)
            }
        }
    }

    /**
     * Procesa la asistencia del camión con lectura GPS y los 3 pasos de persistencia.
     */
    private fun processTruckAttendance(tagUid: String) {
        // 1. Mostrar estado de proceso en los círculos
        setCircleStatus(binding.circleSqlSaved, Status.SAVING)
        setCircleStatus(binding.circleSdCardSaved, Status.SAVING)
        setCircleStatus(binding.circleSqlServerSaved, Status.SAVING)

        binding.tvStatusMessage.text = "Tarjeta detectada: $tagUid\nObteniendo ubicación GPS..."

        // 2. Obtener GPS en tiempo real
        val cts = CancellationTokenSource()
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location: Location? ->
                    val lat = location?.latitude ?: 0.0
                    val lng = location?.longitude ?: 0.0
                    val accuracy = location?.accuracy ?: 0.0f
                    saveAndTransmitRecord(tagUid, lat, lng, accuracy)
                }
                .addOnFailureListener {
                    // Si falla el GPS, guardar con 0,0 para no perder el registro de la tarjeta
                    saveAndTransmitRecord(tagUid, 0.0, 0.0, 0.0f)
                }
        } catch (se: SecurityException) {
            saveAndTransmitRecord(tagUid, 0.0, 0.0, 0.0f)
        }
    }

    private fun saveAndTransmitRecord(tagUid: String, lat: Double, lng: Double, accuracy: Float) {
        lifecycleScope.launch(Dispatchers.IO) {
            val recordId = UUID.randomUUID().toString()
            val nowEpoch = System.currentTimeMillis()
            val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(nowEpoch))

            // Datos predeterminados de la unidad (en producción se leen de la tarjeta o catálogo)
            val entity = TruckAttendanceEntity(
                id = recordId,
                tagId = tagUid,
                truckCode = "CAM-" + tagUid.takeLast(4).uppercase(),
                truckPlates = "OBRA-" + tagUid.take(4).uppercase(),
                driverName = "Operador Registrado",
                materialType = "Material para Obra Civil",
                volumeM3 = 14.0,
                deliveryPoint = "Frente Principal de Colado",
                timestamp = isoDate,
                timestampEpoch = nowEpoch,
                latitude = lat,
                longitude = lng,
                accuracyMeters = accuracy,
                sincronizado = false // Inicialmente falso hasta confirmar el PUT
            )

            // PASO 1: Guardado en SQLite interno (Room)
            var sqlOk = false
            try {
                db.truckAttendanceDao().insert(entity)
                sqlOk = true
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                setCircleStatus(binding.circleSqlSaved, if (sqlOk) Status.SUCCESS else Status.ERROR)
            }

            // PASO 2: Respaldo en la Micro SD Externa
            var sdOk = false
            try {
                sdOk = sdBackupManager.appendRecordToSdCard(entity)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                setCircleStatus(binding.circleSdCardSaved, if (sdOk) Status.SUCCESS else Status.ERROR)
            }

            // PASO 3: Intento ÚNICO de llamada HTTP PUT al servidor externo
            // Regla del usuario: NO debe tener reintentos para no demorar al camión en fila
            var serverOk = false
            try {
                val response = NetworkClient.apiService.enviarRegistro(entity.toJsonMap())
                if (response.isSuccessful) {
                    serverOk = true
                    // Marcar en SQLite que este registro sí se sincronizó exitosamente
                    db.truckAttendanceDao().markAsSynced(recordId)
                }
            } catch (e: Exception) {
                // Sin reintentos: el registro permanece con sincronizado = false
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                // Si tuvo éxito -> verde; Si falló por falta de internet en obra -> ámbar/advertencia
                setCircleStatus(binding.circleSqlServerSaved, if (serverOk) Status.SUCCESS else Status.PENDING_SYNC)
                
                binding.tvLastScannedTruck.text = "Unidad: ${entity.truckCode} | Tag: $tagUid"
                binding.tvStatusMessage.text = if (serverOk) {
                    "✓ Registrado y persistido en Servidor MySQL"
                } else {
                    "✓ Guardado en SQLite y Micro SD (Pendiente de enviar a MySQL al final del día)"
                }
            }
        }
    }

    /**
     * Sincronización masiva al final de la jornada con internet estable en campamento.
     */
    private fun syncPendingRecordsEndOfDay() {
        binding.btnSyncEndOfDay.isEnabled = false
        binding.tvStatusMessage.text = "Sincronizando registros de la jornada..."

        lifecycleScope.launch(Dispatchers.IO) {
            val pending = db.truckAttendanceDao().getPendingSyncRecords()
            var syncedCount = 0
            var errorCount = 0

            for (record in pending) {
                try {
                    val res = NetworkClient.apiService.enviarRegistro(record.toJsonMap())
                    if (res.isSuccessful) {
                        db.truckAttendanceDao().markAsSynced(record.id)
                        syncedCount++
                    } else {
                        errorCount++
                    }
                } catch (e: Exception) {
                    errorCount++
                }
            }

            withContext(Dispatchers.Main) {
                binding.btnSyncEndOfDay.isEnabled = true
                binding.tvStatusMessage.text = "Fin de jornada: $syncedCount sincronizados, $errorCount errores."
                Toast.makeText(
                    this@MainActivity,
                    "Sincronización terminada: $syncedCount sincronizados a MySQL",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun observeDatabaseStats() {
        lifecycleScope.launch {
            db.truckAttendanceDao().getPendingCountFlow().collect { pendingCount ->
                binding.tvPendingCountBadge.text = "Pendientes: $pendingCount"
                binding.btnSyncEndOfDay.isEnabled = pendingCount > 0
            }
        }
    }

    private fun resetStatusCircles() {
        setCircleStatus(binding.circleSqlSaved, Status.IDLE)
        setCircleStatus(binding.circleSdCardSaved, Status.IDLE)
        setCircleStatus(binding.circleSqlServerSaved, Status.IDLE)
    }

    enum class Status { IDLE, SAVING, SUCCESS, PENDING_SYNC, ERROR }

    private fun setCircleStatus(view: View, status: Status) {
        val colorRes = when (status) {
            Status.IDLE -> R.color.circle_idle
            Status.SAVING -> R.color.circle_saving
            Status.SUCCESS -> R.color.circle_success
            Status.PENDING_SYNC -> R.color.circle_pending
            Status.ERROR -> R.color.circle_error
        }
        view.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
    }

    private fun vibrateDevice() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(100)
        }
    }

    private fun bytesToHexString(src: ByteArray): String {
        val sb = StringBuilder()
        for (b in src) {
            sb.append(String.format("%02X:", b))
        }
        return if (sb.isNotEmpty()) sb.substring(0, sb.length - 1) else ""
    }
}
