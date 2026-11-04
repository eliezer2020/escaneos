# Proyecto Android Nativo Kotlin: Control de Asistencia de Camiones en Obra Civil

Este proyecto implementa la solución completa solicitada:
1. **Lectura NFC en primer plano**: Captura automática del UID de la tarjeta en camiones de carga.
2. **GPS Automático**: Coordenadas y precisión registradas con `FusedLocationProviderClient`.
3. **Persistencia SQLite Interna**: Base de datos Room (`AppDatabase`).
4. **Respaldo en Micro SD Externa**: Escritura directa en tarjeta de memoria extraíble (`SdCardBackupManager`).
5. **Llamada HTTP PUT a www.registrardatos.php/create**:
   - Intento único e inmediato sin reintentos automáticos para no frenar la fila de obra.
   - Si no hay señal en el frente de obra, el registro queda con `sincronizado = false`.
6. **Barra de 3 Círculos de Estado en Pantalla de Operador**:
   - `sqlsaved`: Verde cuando SQLite local guardó exitosamente.
   - `sdcardsaved`: Verde cuando la Micro SD escribió el respaldo.
   - `sqlserversaved`: Verde si el servidor MySQL guardó, o Ámbar si quedó pendiente para fin de turno.
7. **Botón de Sincronización de Fin de Jornada**:
   - Envía por lote todos los registros pendientes cuando el operador regresa a campamento con internet.

### Cómo compilar el APK:
1. Descomprime este archivo .zip.
2. Abre la carpeta en **Android Studio Hedgehog o superior**.
3. Sincroniza Gradle (**Sync Project with Gradle Files**).
4. Ve al menú **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. El APK generado estará en: `app/build/outputs/apk/debug/app-debug.apk`.
