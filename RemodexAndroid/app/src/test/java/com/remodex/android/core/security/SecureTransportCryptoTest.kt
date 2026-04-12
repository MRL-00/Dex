package com.remodex.android.core.security

import com.remodex.android.core.model.SECURE_PROTOCOL_VERSION
import com.remodex.android.core.model.SecureHandshakeMode
import com.remodex.android.core.model.SecureSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureTransportCryptoTest {
    @Test
    fun `ed25519 signatures round trip`() {
        val phone = SecureTransportCrypto.generatePhoneIdentity()
        val transcript = "hello".toByteArray()
        val signature = SecureTransportCrypto.signEd25519(phone.phoneIdentityPrivateKey, transcript)
        assertTrue(
            SecureTransportCrypto.verifyEd25519(
                publicKeyBase64 = phone.phoneIdentityPublicKey,
                transcript = transcript,
                signatureBase64 = signature,
            ),
        )
    }

    @Test
    fun `envelope encrypt decrypt round trip`() {
        val phone = SecureTransportCrypto.generatePhoneIdentity()
        val transcript = SecureTransportCrypto.buildTranscriptBytes(
            sessionId = "session",
            protocolVersion = SECURE_PROTOCOL_VERSION,
            handshakeMode = SecureHandshakeMode.QR_BOOTSTRAP,
            keyEpoch = 1,
            macDeviceId = "mac",
            phoneDeviceId = phone.phoneDeviceId,
            macIdentityPublicKey = phone.phoneIdentityPublicKey,
            phoneIdentityPublicKey = phone.phoneIdentityPublicKey,
            macEphemeralPublicKey = SecureTransportCrypto.generateEphemeralX25519().publicKeyBase64,
            phoneEphemeralPublicKey = SecureTransportCrypto.generateEphemeralX25519().publicKeyBase64,
            clientNonce = SecureTransportCrypto.randomNonce(),
            serverNonce = SecureTransportCrypto.randomNonce(),
            expiresAtForTranscript = 0,
        )
        val keys = SecureTransportCrypto.deriveSessionKeys(
            sharedSecret = ByteArray(32) { it.toByte() },
            transcriptBytes = transcript,
            sessionId = "session",
            macDeviceId = "mac",
            phoneDeviceId = phone.phoneDeviceId,
            keyEpoch = 1,
        )
        val session = SecureSession(
            sessionId = "session",
            keyEpoch = 1,
            macDeviceId = "mac",
            macIdentityPublicKey = phone.phoneIdentityPublicKey,
            phoneToMacKey = keys.phoneToMacKey,
            macToPhoneKey = keys.macToPhoneKey,
            lastInboundBridgeOutboundSeq = 0,
            lastInboundCounter = -1,
            nextOutboundCounter = 0,
        )
        val envelope = SecureTransportCrypto.encryptEnvelope(
            session = session,
            sender = "iphone",
            counter = 0,
            payloadText = """{"method":"ping"}""",
        )
        val payload = SecureTransportCrypto.decryptEnvelope(session, envelope)
        assertEquals("""{"method":"ping"}""", payload?.payloadText)
    }
}
