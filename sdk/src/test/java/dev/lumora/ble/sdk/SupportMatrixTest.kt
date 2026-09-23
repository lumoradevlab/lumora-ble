package dev.lumora.ble.sdk

import dev.lumora.ble.core.DeviceKind
import dev.lumora.ble.core.SupportMatrix
import dev.lumora.ble.core.SupportStatus
import dev.lumora.ble.core.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportMatrixTest {

    @Test
    fun `every device kind is described`() {
        assertEquals(DeviceKind.entries.size, SupportMatrix.all.size)
        DeviceKind.entries.forEach { assertNotNull(SupportMatrix.forKind(it)) }
    }

    @Test
    fun `the implemented devices are usable`() {
        listOf(
            DeviceKind.OURA_RING,
            DeviceKind.LIBRE_SENSOR,
            DeviceKind.DEXCOM_SENSOR,
            DeviceKind.HEART_RATE_MONITOR,
        ).forEach { kind ->
            assertTrue("$kind should be usable", SupportMatrix.isUsable(kind))
        }
    }

    @Test
    fun `vendor-locked kinds are reported blocked, not quietly usable`() {
        listOf(DeviceKind.FITBIT_TRACKER, DeviceKind.PIXEL_WATCH).forEach { kind ->
            assertEquals(SupportStatus.BLOCKED, SupportMatrix.forKind(kind).status)
            assertFalse("$kind must not be offered", SupportMatrix.isUsable(kind))
        }
    }

    @Test
    fun `blocked vendors point at the standard-profile route that does work`() {
        // The value of blocking these is naming the alternative. An integrator
        // reading "Fitbit: blocked" should learn live HR is still reachable.
        listOf(DeviceKind.FITBIT_TRACKER, DeviceKind.PIXEL_WATCH).forEach { kind ->
            assertTrue(
                "$kind must name the HEART_RATE_MONITOR route",
                SupportMatrix.forKind(kind).limitation!!.contains("HEART_RATE_MONITOR"),
            )
        }
    }

    @Test
    fun `heart rate monitor states its broadcast prerequisite and history gap`() {
        val hr = SupportMatrix.forKind(DeviceKind.HEART_RATE_MONITOR)

        // Broadcast is user-initiated on a Fitbit or Pixel Watch, so this
        // cannot be SUPPORTED without misleading integrators.
        assertEquals(SupportStatus.REQUIRES_SETUP, hr.status)
        assertEquals(Transport.BLE, hr.transport)
        assertTrue("must name the on-device step",
            hr.prerequisite!!.contains("Connected Fitness"))
        assertTrue("must warn there is no stored history",
            hr.limitation!!.contains("backfill"))
    }

    @Test
    fun `libre is reported as NFC rather than BLE`() {
        val libre = SupportMatrix.forKind(DeviceKind.LIBRE_SENSOR)

        // The transport drives the UX: NFC is a tap, BLE is a connection.
        assertEquals(Transport.NFC, libre.transport)
        assertEquals(SupportStatus.SUPPORTED, libre.status)
    }

    @Test
    fun `BLE devices are reported as BLE`() {
        assertEquals(Transport.BLE, SupportMatrix.forKind(DeviceKind.DEXCOM_SENSOR).transport)
        assertEquals(Transport.BLE, SupportMatrix.forKind(DeviceKind.OURA_RING).transport)
    }

    @Test
    fun `dexcom states its G6-only limitation`() {
        val dexcom = SupportMatrix.forKind(DeviceKind.DEXCOM_SENSOR)

        assertEquals(SupportStatus.REQUIRES_SETUP, dexcom.status)
        assertTrue("must say it needs the serial",
            dexcom.prerequisite!!.contains("serial"))
        assertTrue("must name the unsupported generation",
            dexcom.limitation!!.contains("G7"))
    }

    @Test
    fun `libre states its generation limitation`() {
        val libre = SupportMatrix.forKind(DeviceKind.LIBRE_SENSOR)

        assertTrue("must name the unsupported generation",
            libre.limitation!!.contains("Libre 3"))
        assertTrue("must warn readings are uncalibrated",
            libre.limitation!!.contains("uncalibrated"))
    }

    @Test
    fun `oura warns about the factory reset before the user commits`() {
        val oura = SupportMatrix.forKind(DeviceKind.OURA_RING)

        assertEquals(SupportStatus.REQUIRES_SETUP, oura.status)
        assertTrue(oura.prerequisite!!.contains("factory-reset"))
    }

    @Test
    fun `every device explains its prerequisite or limitation`() {
        SupportMatrix.all.forEach { support ->
            assertTrue(
                "${support.kind} must explain itself",
                support.prerequisite != null || support.limitation != null,
            )
        }
    }
}
