package com.mattl_nz.dex.core.security

import org.junit.Assert.assertTrue
import org.junit.Test

class QrPairingValidatorTest {
    @Test
    fun `accepts current pairing payload`() {
        val now = 1_710_000_000_000L
        val payload = """
            {"v":2,"relay":"ws://127.0.0.1:8765/relay","sessionId":"abc","macDeviceId":"mac-1","macIdentityPublicKey":"Zm9v","expiresAt":${now + 30_000}}
        """.trimIndent()
        val result = QrPairingValidator.validate(payload, now)
        assertTrue(result is QrPairingValidationResult.Success)
    }

    @Test
    fun `rejects expired pairing payload`() {
        val now = 1_710_000_000_000L
        val payload = """
            {"v":2,"relay":"ws://127.0.0.1:8765/relay","sessionId":"abc","macDeviceId":"mac-1","macIdentityPublicKey":"Zm9v","expiresAt":${now - 120_000}}
        """.trimIndent()
        val result = QrPairingValidator.validate(payload, now)
        assertTrue(result is QrPairingValidationResult.ScanError)
    }
}
