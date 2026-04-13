package com.mattl_nz.dex.core.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures audio in WAV format (24kHz mono 16-bit PCM) matching iOS voice spec.
 * Max duration: 60 seconds. Max file size: 10 MB.
 */
class VoiceRecordingManager(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 24000
        const val MAX_DURATION_SECONDS = 60
        const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024L // 10 MB
        private const val TAG = "VoiceRecording"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var pcmBuffer: ByteArrayOutputStream? = null

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    fun startRecording(): Boolean {
        if (!hasPermission) {
            Log.w(TAG, "Missing RECORD_AUDIO permission")
            return false
        }
        if (isRecording) return false

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufferSize <= 0) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord", e)
            return false
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        pcmBuffer = ByteArrayOutputStream()
        isRecording = true
        audioRecord?.startRecording()

        Thread {
            val buffer = ShortArray(bufferSize / 2)
            val maxSamples = SAMPLE_RATE * MAX_DURATION_SECONDS
            var totalSamples = 0

            while (isRecording && totalSamples < maxSamples) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val byteBuffer = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) {
                        byteBuffer.putShort(buffer[i])
                    }
                    synchronized(this) {
                        pcmBuffer?.write(byteBuffer.array(), 0, read * 2)
                    }
                    totalSamples += read

                    // Check file size limit
                    val currentSize = synchronized(this) { pcmBuffer?.size() ?: 0 }
                    if (currentSize + 44 > MAX_FILE_SIZE_BYTES) {
                        Log.d(TAG, "Max file size reached, stopping")
                        break
                    }
                }
            }

            if (isRecording) {
                isRecording = false
            }
        }.start()

        return true
    }

    fun stopRecording(): ByteArray? {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcmData = synchronized(this) {
            val data = pcmBuffer?.toByteArray()
            pcmBuffer = null
            data
        } ?: return null

        if (pcmData.isEmpty()) return null

        return buildWavFile(pcmData)
    }

    fun cancelRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        synchronized(this) { pcmBuffer = null }
    }

    private fun buildWavFile(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = SAMPLE_RATE * 1 * 16 / 8
        val blockAlign = 1 * 16 / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray())
        // fmt chunk
        header.put("fmt ".toByteArray())
        header.putInt(16) // chunk size
        header.putShort(1) // PCM format
        header.putShort(1) // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16) // bits per sample
        // data chunk
        header.put("data".toByteArray())
        header.putInt(pcmData.size)

        return header.array() + pcmData
    }

    suspend fun validateRecording(wavData: ByteArray): VoicePreflightResult {
        return withContext(Dispatchers.Default) {
            val durationSeconds = (wavData.size - 44).toFloat() / (SAMPLE_RATE * 2)
            when {
                wavData.size < 100 -> VoicePreflightResult.TooShort
                durationSeconds > MAX_DURATION_SECONDS + 1 -> VoicePreflightResult.TooLong(durationSeconds)
                wavData.size > MAX_FILE_SIZE_BYTES -> VoicePreflightResult.TooLarge(wavData.size)
                else -> VoicePreflightResult.Valid(durationSeconds)
            }
        }
    }
}

sealed class VoicePreflightResult {
    data class Valid(val durationSeconds: Float) : VoicePreflightResult()
    data object TooShort : VoicePreflightResult()
    data class TooLong(val durationSeconds: Float) : VoicePreflightResult()
    data class TooLarge(val sizeBytes: Int) : VoicePreflightResult()
}
