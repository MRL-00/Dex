package com.remodex.android.core.security

import com.remodex.android.core.model.CLIENT_AUTH_LABEL
import com.remodex.android.core.model.SECURE_HANDSHAKE_TAG
import com.remodex.android.core.model.SecureApplicationPayload
import com.remodex.android.core.model.SecureEnvelope
import com.remodex.android.core.model.SecureHandshakeMode
import com.remodex.android.core.model.SecureSession
import com.remodex.android.core.model.TRUSTED_SESSION_RESOLVE_TAG
import com.remodex.android.core.model.PhoneIdentityState
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

data class X25519KeyMaterial(
    val privateKey: X25519PrivateKeyParameters,
    val publicKeyBase64: String,
)

data class DerivedSessionKeys(
    val phoneToMacKey: ByteArray,
    val macToPhoneKey: ByteArray,
)

object SecureTransportCrypto {
    private val random = SecureRandom()
    private val base64 = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()

    fun generatePhoneIdentity(): PhoneIdentityState {
        val privateKey = Ed25519PrivateKeyParameters(random)
        val publicKey = privateKey.generatePublicKey().encoded
        return PhoneIdentityState(
            phoneDeviceId = UUID.randomUUID().toString(),
            phoneIdentityPrivateKey = base64.encodeToString(privateKey.encoded),
            phoneIdentityPublicKey = base64.encodeToString(publicKey),
        )
    }

    fun generateEphemeralX25519(): X25519KeyMaterial {
        val privateKey = X25519PrivateKeyParameters(random)
        return X25519KeyMaterial(
            privateKey = privateKey,
            publicKeyBase64 = base64.encodeToString(privateKey.generatePublicKey().encoded),
        )
    }

    fun randomNonce(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun buildTranscriptBytes(
        sessionId: String,
        protocolVersion: Int,
        handshakeMode: SecureHandshakeMode,
        keyEpoch: Int,
        macDeviceId: String,
        phoneDeviceId: String,
        macIdentityPublicKey: String,
        phoneIdentityPublicKey: String,
        macEphemeralPublicKey: String,
        phoneEphemeralPublicKey: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        expiresAtForTranscript: Long,
    ): ByteArray {
        return concat(
            lpUtf8(SECURE_HANDSHAKE_TAG),
            lpUtf8(sessionId),
            lpUtf8(protocolVersion.toString()),
            lpUtf8(
                when (handshakeMode) {
                    SecureHandshakeMode.QR_BOOTSTRAP -> "qr_bootstrap"
                    SecureHandshakeMode.TRUSTED_RECONNECT -> "trusted_reconnect"
                }
            ),
            lpUtf8(keyEpoch.toString()),
            lpUtf8(macDeviceId),
            lpUtf8(phoneDeviceId),
            lp(base64Decoder.decode(macIdentityPublicKey)),
            lp(base64Decoder.decode(phoneIdentityPublicKey)),
            lp(base64Decoder.decode(macEphemeralPublicKey)),
            lp(base64Decoder.decode(phoneEphemeralPublicKey)),
            lp(clientNonce),
            lp(serverNonce),
            lpUtf8(expiresAtForTranscript.toString()),
        )
    }

    fun buildClientAuthTranscript(transcriptBytes: ByteArray): ByteArray {
        return concat(transcriptBytes, lpUtf8(CLIENT_AUTH_LABEL))
    }

    fun buildTrustedSessionResolveTranscript(
        macDeviceId: String,
        phoneDeviceId: String,
        phoneIdentityPublicKey: String,
        nonce: String,
        timestamp: Long,
    ): ByteArray {
        return concat(
            lpUtf8(TRUSTED_SESSION_RESOLVE_TAG),
            lpUtf8(macDeviceId),
            lpUtf8(phoneDeviceId),
            lp(base64Decoder.decode(phoneIdentityPublicKey)),
            lpUtf8(nonce),
            lpUtf8(timestamp.toString()),
        )
    }

    fun signEd25519(privateKeyBase64: String, transcript: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(base64Decoder.decode(privateKeyBase64), 0))
        signer.update(transcript, 0, transcript.size)
        return base64.encodeToString(signer.generateSignature())
    }

    fun verifyEd25519(publicKeyBase64: String, transcript: ByteArray, signatureBase64: String): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(base64Decoder.decode(publicKeyBase64), 0))
        signer.update(transcript, 0, transcript.size)
        return signer.verifySignature(base64Decoder.decode(signatureBase64))
    }

    fun deriveSessionKeys(
        sharedSecret: ByteArray,
        transcriptBytes: ByteArray,
        sessionId: String,
        macDeviceId: String,
        phoneDeviceId: String,
        keyEpoch: Int,
    ): DerivedSessionKeys {
        val salt = sha256(transcriptBytes)
        val prefix = "$SECURE_HANDSHAKE_TAG|$sessionId|$macDeviceId|$phoneDeviceId|$keyEpoch"
        return DerivedSessionKeys(
            phoneToMacKey = hkdf(sharedSecret, salt, "$prefix|phoneToMac"),
            macToPhoneKey = hkdf(sharedSecret, salt, "$prefix|macToPhone"),
        )
    }

    fun sharedSecret(privateKey: X25519PrivateKeyParameters, publicKeyBase64: String): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val output = ByteArray(32)
        agreement.calculateAgreement(X25519PublicKeyParameters(base64Decoder.decode(publicKeyBase64), 0), output, 0)
        return output
    }

    fun nonce(sender: String, counter: Int): ByteArray {
        val value = ByteArray(12)
        value[0] = if (sender == "mac") 1 else 2
        var remaining = counter.toLong()
        for (index in 11 downTo 1) {
            value[index] = (remaining and 0xff).toByte()
            remaining = remaining ushr 8
        }
        return value
    }

    fun encryptEnvelope(
        session: SecureSession,
        sender: String,
        counter: Int,
        payloadText: String,
        bridgeOutboundSeq: Int? = null,
    ): SecureEnvelope {
        val payload = SecureApplicationPayload(
            bridgeOutboundSeq = bridgeOutboundSeq,
            payloadText = payloadText,
        )
        val plaintext = kotlinx.serialization.json.Json.encodeToString(SecureApplicationPayload.serializer(), payload)
            .toByteArray(StandardCharsets.UTF_8)
        val key = if (sender == "mac") session.macToPhoneKey else session.phoneToMacKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(sender, counter)))
        val encrypted = cipher.doFinal(plaintext)
        val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)
        val ciphertext = encrypted.copyOfRange(0, encrypted.size - 16)
        return SecureEnvelope(
            kind = "encryptedEnvelope",
            v = 1,
            sessionId = session.sessionId,
            keyEpoch = session.keyEpoch,
            sender = sender,
            counter = counter,
            ciphertext = base64.encodeToString(ciphertext),
            tag = base64.encodeToString(tag),
        )
    }

    fun decryptEnvelope(session: SecureSession, envelope: SecureEnvelope): SecureApplicationPayload? {
        return try {
            val key = if (envelope.sender == "mac") session.macToPhoneKey else session.phoneToMacKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val ciphertext = base64Decoder.decode(envelope.ciphertext)
            val tag = base64Decoder.decode(envelope.tag)
            val combined = ciphertext + tag
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce(envelope.sender, envelope.counter)),
            )
            val plaintext = cipher.doFinal(combined)
            kotlinx.serialization.json.Json.decodeFromString(
                SecureApplicationPayload.serializer(),
                plaintext.toString(StandardCharsets.UTF_8),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun fingerprint(publicKeyBase64: String): String {
        return sha256(base64Decoder.decode(publicKeyBase64))
            .joinToString("") { "%02x".format(it) }
            .take(12)
            .uppercase()
    }

    private fun hkdf(secret: ByteArray, salt: ByteArray, info: String): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(secret, salt, info.toByteArray(StandardCharsets.UTF_8)))
        return ByteArray(32).also { generator.generateBytes(it, 0, it.size) }
    }

    private fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    private fun lpUtf8(value: String): ByteArray = lp(value.toByteArray(StandardCharsets.UTF_8))

    private fun lp(value: ByteArray): ByteArray {
        val prefix = ByteBuffer.allocate(4).putInt(value.size).array()
        return prefix + value
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val size = parts.sumOf { it.size }
        val out = ByteArray(size)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }
}
