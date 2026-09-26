package dev.lumora.ble.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is what makes vendor protocols opt-in, so its guarantees are
 * what a consumer's legal exposure rests on: a protocol they did not install
 * must be absent, not merely inert.
 */
class ProtocolRegistryTest {

    private fun protocol(k: DeviceKind) = object : DeviceProtocol {
        override val kind = k
        override fun create(context: ProtocolContext): DeviceConnection =
            throw UnsupportedOperationException("not needed for this test")
    }

    @Test
    fun `an empty registry reports nothing installed`() {
        val registry = ProtocolRegistry()

        assertTrue(registry.isEmpty())
        assertTrue(registry.installed.isEmpty())
        assertNull(registry[DeviceKind.DEXCOM_SENSOR])
    }

    @Test
    fun `installing one protocol does not install the others`() {
        val registry = ProtocolRegistry()
        registry.install(protocol(DeviceKind.HEART_RATE_MONITOR))

        // The point of the whole design: a build wanting standard heart rate
        // must not end up carrying the vendor protocols' ToS exposure.
        assertEquals(setOf(DeviceKind.HEART_RATE_MONITOR), registry.installed)
        assertNull(registry[DeviceKind.DEXCOM_SENSOR])
        assertNull(registry[DeviceKind.OURA_RING])
        assertNull(registry[DeviceKind.LIBRE_SENSOR])
    }

    @Test
    fun `installing two protocols for one kind fails loudly`() {
        val registry = ProtocolRegistry()
        registry.install(protocol(DeviceKind.OURA_RING))

        // Silently picking one would make behaviour depend on install order.
        val error = runCatching { registry.install(protocol(DeviceKind.OURA_RING)) }
            .exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $error",
            error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("OURA_RING"))
    }

    @Test
    fun `installation order is preserved`() {
        val registry = ProtocolRegistry()
        registry.install(protocol(DeviceKind.HEART_RATE_MONITOR))
        registry.install(protocol(DeviceKind.OURA_RING))

        assertEquals(
            listOf(DeviceKind.HEART_RATE_MONITOR, DeviceKind.OURA_RING),
            registry.installed.toList(),
        )
    }

    @Test
    fun `the registry holds only what was installed, by identity`() {
        // The legal boundary rests on this: a consumer who installs one
        // protocol must not find another reachable through the SDK. Verified
        // empirically too — a release APK installing only the standard
        // profile contains no dev/lumora/ble/{dexcom,oura,libre} classes at
        // all, so the vendor code is absent rather than merely unreachable.
        val standard = protocol(DeviceKind.HEART_RATE_MONITOR)
        val registry = ProtocolRegistry()
        registry.install(standard)

        assertSame(standard, registry[DeviceKind.HEART_RATE_MONITOR])
        DeviceKind.entries
            .filter { it != DeviceKind.HEART_RATE_MONITOR }
            .forEach { assertNull("$it must not be reachable", registry[it]) }
    }

    @Test
    fun `a protocol not discovered by scanning contributes no services`() {
        // Libre is tapped over NFC; the default must be empty rather than
        // forcing every protocol to opt out of scanning.
        val nfcOnly = protocol(DeviceKind.LIBRE_SENSOR)

        assertTrue(nfcOnly.scanServices.isEmpty())
        assertNull(nfcOnly.scanNamePrefix())
    }
}
