package com.obra.asistencia.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.PUT
import java.util.concurrent.TimeUnit

/**
 * Servicio Retrofit para la persistencia externa en el servidor MySQL central.
 * 
 * Requisito del usuario:
 * - Se asume que el endpoint externo se llama www.registrardatos.php/create
 * - Método HTTP PUT con el JSON de la tabla.
 */
interface ApiService {

    /**
     * Intento único de inserción remota.
     * No debe tener reintentos automáticos en el escaneo para no bloquear al usuario.
     */
    @PUT("create")
    suspend fun enviarRegistro(
        @Body payload: Map<String, Any>
    ): Response<Unit>
}

object NetworkClient {

    // URL base del servicio especificado en el requerimiento
    private const val BASE_URL = "https://www.registrardatos.php/"

    private val okHttpClient = OkHttpClient.Builder()
        // Timeout corto (4 segundos) para no frenar la fila de camiones si no hay internet en obra
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // REGLA: SIN REINTENTOS en el llamado individual
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
