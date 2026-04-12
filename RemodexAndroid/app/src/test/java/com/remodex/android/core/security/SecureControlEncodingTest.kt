package com.remodex.android.core.security

import com.remodex.android.core.model.SecureClientAuth
import com.remodex.android.core.model.SecureClientHello
import com.remodex.android.core.model.SecureHandshakeMode
import com.remodex.android.core.model.SecureResumeState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureControlEncodingTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun clientHelloIncludesKind() {
        val encoded = json.encodeToString(
            SecureClientHello(
                protocolVersion = 1,
                sessionId = "session-1",
                handshakeMode = SecureHandshakeMode.QR_BOOTSTRAP,
                phoneDeviceId = "phone-1",
                phoneIdentityPublicKey = "pub",
                phoneEphemeralPublicKey = "eph",
                clientNonce = "nonce",
            ),
        )

        assertTrue(encoded, encoded.contains("\"kind\":\"clientHello\""))
    }

    @Test
    fun clientAuthIncludesKind() {
        val encoded = json.encodeToString(
            SecureClientAuth(
                sessionId = "session-1",
                phoneDeviceId = "phone-1",
                keyEpoch = 1,
                phoneSignature = "sig",
            ),
        )

        assertTrue(encoded, encoded.contains("\"kind\":\"clientAuth\""))
    }

    @Test
    fun resumeStateIncludesKind() {
        val encoded = json.encodeToString(
            SecureResumeState(
                sessionId = "session-1",
                keyEpoch = 1,
                lastAppliedBridgeOutboundSeq = 0,
            ),
        )

        assertTrue(encoded, encoded.contains("\"kind\":\"resumeState\""))
    }
}
