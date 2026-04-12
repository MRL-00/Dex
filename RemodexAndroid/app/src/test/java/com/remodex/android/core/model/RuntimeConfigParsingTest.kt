package com.remodex.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeConfigParsingTest {

    @Test
    fun `ReasoningEffort fromString maps correctly`() {
        assertEquals(ReasoningEffort.LOW, ReasoningEffort.fromString("low"))
        assertEquals(ReasoningEffort.MEDIUM, ReasoningEffort.fromString("medium"))
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.fromString("HIGH"))
        assertNull(ReasoningEffort.fromString("unknown"))
        assertNull(ReasoningEffort.fromString(null))
    }

    @Test
    fun `ServiceTier fromString defaults to DEFAULT`() {
        assertEquals(ServiceTier.FAST, ServiceTier.fromString("fast"))
        assertEquals(ServiceTier.DEFAULT, ServiceTier.fromString("default"))
        assertEquals(ServiceTier.DEFAULT, ServiceTier.fromString("anything"))
        assertEquals(ServiceTier.DEFAULT, ServiceTier.fromString(null))
    }

    @Test
    fun `AccessMode fromString handles variants`() {
        assertEquals(AccessMode.FULL_ACCESS, AccessMode.fromString("never"))
        assertEquals(AccessMode.FULL_ACCESS, AccessMode.fromString("fullaccess"))
        assertEquals(AccessMode.ON_REQUEST, AccessMode.fromString("on-request"))
        assertEquals(AccessMode.ON_REQUEST, AccessMode.fromString("anything"))
        assertEquals(AccessMode.ON_REQUEST, AccessMode.fromString(null))
    }

    @Test
    fun `AccessMode policyValue matches protocol`() {
        assertEquals("on-request", AccessMode.ON_REQUEST.policyValue)
        assertEquals("never", AccessMode.FULL_ACCESS.policyValue)
    }
}
