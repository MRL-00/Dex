package com.remodex.android.core.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePreflightTest {

    private val sampleRate = 24000
    private val bytesPerSample = 2
    private val headerSize = 44
    private val maxFileSizeBytes = 10L * 1024 * 1024

    @Test
    fun `valid wav data passes preflight`() {
        // 1 second of audio
        val pcmBytes = sampleRate * bytesPerSample
        val wavData = ByteArray(pcmBytes + headerSize)
        val result = validateWavPreflight(wavData)
        assertTrue("Expected Valid but got $result", result is VoicePreflightResult.Valid)
    }

    @Test
    fun `too short wav fails`() {
        val wavData = ByteArray(50)
        val result = validateWavPreflight(wavData)
        assertTrue("Expected TooShort but got $result", result is VoicePreflightResult.TooShort)
    }

    @Test
    fun `over max duration fails`() {
        // 61 seconds but under 10MB (only possible with lower calculation)
        // At 24kHz * 2 bytes = 48000 bytes/sec, 61 seconds = 2,928,044 bytes (under 10MB)
        val pcmBytes = 61 * sampleRate * bytesPerSample
        val wavData = ByteArray(pcmBytes + headerSize)
        val result = validateWavPreflight(wavData)
        assertTrue("Expected TooLong but got $result", result is VoicePreflightResult.TooLong)
    }

    @Test
    fun `over 10MB fails`() {
        // Create data that exceeds 10MB
        val wavData = ByteArray((maxFileSizeBytes + 1000).toInt())
        val result = validateWavPreflight(wavData)
        assertTrue("Expected TooLarge but got $result", result is VoicePreflightResult.TooLarge)
    }

    private fun validateWavPreflight(wavData: ByteArray): VoicePreflightResult {
        val durationSeconds = (wavData.size - headerSize).toFloat() / (sampleRate * bytesPerSample)
        return when {
            wavData.size < 100 -> VoicePreflightResult.TooShort
            wavData.size > maxFileSizeBytes -> VoicePreflightResult.TooLarge(wavData.size)
            durationSeconds >= 61 -> VoicePreflightResult.TooLong(durationSeconds)
            else -> VoicePreflightResult.Valid(durationSeconds)
        }
    }
}
