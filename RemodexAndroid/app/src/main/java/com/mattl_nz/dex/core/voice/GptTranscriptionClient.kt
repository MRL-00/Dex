package com.mattl_nz.dex.core.voice

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Posts WAV audio to ChatGPT transcription API using an ephemeral token
 * obtained via voice/resolveAuth RPC.
 */
class GptTranscriptionClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        private const val TRANSCRIPTION_URL = "https://chatgpt.com/backend-api/transcribe"
        private const val TAG = "GptTranscription"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Transcribes WAV audio data to text.
     * @param wavData Raw WAV file bytes (24kHz mono 16-bit PCM)
     * @param authToken Ephemeral bearer token from voice/resolveAuth RPC
     * @return Transcribed text
     * @throws TranscriptionException on failure
     */
    suspend fun transcribe(wavData: ByteArray, authToken: String): String = withContext(Dispatchers.IO) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "voice.wav",
                wavData.toRequestBody("audio/wav".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(TRANSCRIPTION_URL)
            .header("Authorization", "Bearer $authToken")
            .post(requestBody)
            .build()

        Log.d(TAG, "Sending transcription request (${wavData.size} bytes)")

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (response.code in 401..403) {
            throw TranscriptionException.AuthExpired("Token expired or unauthorized")
        }

        if (!response.isSuccessful) {
            val errorMessage = try {
                val obj = json.parseToJsonElement(body).jsonObject
                obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    ?: obj["message"]?.jsonPrimitive?.content
                    ?: "HTTP ${response.code}"
            } catch (_: Exception) {
                "HTTP ${response.code}: $body"
            }
            throw TranscriptionException.ApiError(errorMessage)
        }

        try {
            val obj = json.parseToJsonElement(body).jsonObject
            val text = obj["text"]?.jsonPrimitive?.content
                ?: obj["transcript"]?.jsonPrimitive?.content
            text ?: throw TranscriptionException.ApiError("No text in response")
        } catch (e: TranscriptionException) {
            throw e
        } catch (e: Exception) {
            throw TranscriptionException.ParseError("Failed to parse response: ${e.message}")
        }
    }
}

sealed class TranscriptionException(message: String) : Exception(message) {
    class AuthExpired(message: String) : TranscriptionException(message)
    class ApiError(message: String) : TranscriptionException(message)
    class ParseError(message: String) : TranscriptionException(message)
}
