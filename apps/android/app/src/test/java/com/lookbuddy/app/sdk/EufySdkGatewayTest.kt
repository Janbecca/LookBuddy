package com.lookbuddy.app.sdk

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class EufySdkGatewayTest {
    private fun validConfig() = EufyAccountConfig(
        appName = "lookbuddy",
        clientId = "client",
        userId = "user",
        authorization = "secret-token",
        appDomain = "https://example.com",
        deviceDomain = "https://device.example.com",
        presetKey = "US",
    )

    @Test
    fun configAcceptsHttpsDomains() {
        validConfig().requireValid()
    }

    @Test
    fun configRejectsBlankTokenAndCleartextDomains() {
        listOf(
            validConfig().copy(authorization = ""),
            validConfig().copy(appDomain = "http://example.com"),
            validConfig().copy(deviceDomain = "http://device.example.com"),
        ).forEach { config ->
            try {
                config.requireValid()
                fail("invalid config was accepted")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun mockReturnsExplicitEmptyRealWorldState() = runTest {
        val gateway = MockEufySdkGateway()
        gateway.initialize(validConfig())
        assertTrue(gateway.refreshDevices().isEmpty())
        assertTrue(gateway.fetchRecentEvents("T0000").isEmpty())
        assertTrue(!gateway.supportsPrivacyMode("T0000"))
    }

    @Test
    fun deviceEventMatchesLookBuddyV1ContractShape() {
        val event = DeviceEvent(
            eventId = "eufy_0123456789abcdef0123456789abcdef",
            deviceId = "T8214-test",
            occurredAt = "2026-10-13T00:00:00Z",
            source = DeviceEventSource(rawType = "1"),
            payload = DeviceEventPayload("provider-id", "1", true),
        )
        assertEquals("lookbuddy.event", event.schema)
        assertEquals(1, event.version)
        assertEquals("device.security.event", event.type)
        assertEquals("eufy", event.source.provider)
        assertEquals("2.2.7", event.source.sdkVersion)
        assertTrue(event.payload.hasThumbnail)
        assertTrue(!event.privacy.containsImage)
    }
}
