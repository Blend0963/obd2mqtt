package com.frodrigues.obdmqtt.obd

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.frodrigues.obdmqtt.bluetooth.FakeTransport
import com.frodrigues.obdmqtt.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PidScannerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val settings by lazy {
        AppSettings(
            PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                File(tmp.root, "test.preferences_pb")
            }
        )
    }

    @After
    fun tearDown() = dataStoreScope.cancel()

    @Test
    fun `support bitmasks give the pids in a few requests`() = runTest {
        // 0100: PIDs 04, 05 (byte 0 = 0x18) and 0C, 0D (byte 1 = 0x18); 0x20 not announced
        val transport = FakeTransport(
            responses = mapOf(
                "0100" to "41 00 18 18 00 00",
                "ATRV" to "12.4V"
            )
        )
        val supported = PidScanner(ObdCommandExecutor(transport), settings).scan()

        assertEquals(setOf(0x04, 0x05, 0x0C, 0x0D, PidRegistry.ADAPTER_VOLTAGE), supported)
        assertEquals(listOf("0100", "ATRV"), transport.sentCommands)
        assertEquals(supported, settings.cachedPids.first())
    }

    @Test
    fun `next bitmask is read only when announced`() = runTest {
        // 0100 announces 0x20 (last bit); 0120 announces 0x2F (fuel level) only
        val transport = FakeTransport(
            responses = mapOf(
                "0100" to "41 00 00 10 00 01",
                "0120" to "41 20 00 02 00 00"
            )
        )
        val supported = PidScanner(ObdCommandExecutor(transport), settings).scan()

        assertEquals(setOf(0x0C, 0x2F), supported)
        assertFalse("0140" in transport.sentCommands)
    }

    @Test
    fun `falls back to probing each pid without bitmask`() = runTest {
        val transport = FakeTransport(
            responses = mapOf(
                "010C" to "41 0C 0F A0",
                "010D" to "41 0D 3C",
                "0105" to "41 05 7B",
                "ATRV" to "12.4V"
            )
        )
        val scanner = PidScanner(ObdCommandExecutor(transport), settings)

        val supported = scanner.scan()

        assertEquals(setOf(0x05, 0x0C, 0x0D, PidRegistry.ADAPTER_VOLTAGE), supported)
        assertEquals(supported, settings.cachedPids.first())
    }

    @Test
    fun `cached pids are returned without probing the car`() = runTest {
        settings.savePidCache(setOf(0x0C, 0x0D))
        val transport = FakeTransport()

        val supported = PidScanner(ObdCommandExecutor(transport), settings).scan()

        assertEquals(setOf(0x0C, 0x0D), supported)
        assertTrue(transport.sentCommands.isEmpty())
    }

    @Test
    fun `slow pids default to every discovered pid that is not fast`() = runTest {
        settings.savePidCache(setOf(0x0C, 0x0D, 0x05, 0x0F, 0x33))

        val fast = settings.fastPids.first()
        val slow = settings.slowPids.first()

        assertEquals(AppSettings.DEFAULT_FAST_PIDS, fast)
        assertEquals(setOf(0x0F, 0x33), slow)
    }

    @Test
    fun `status pids are not kept as measurements`() = runTest {
        // 0100: 01 (monitor status), 04, 05 (byte 0 = 0x98); 12, 13 (byte 2 = 0x60); 1C (byte 3 = 0x10)
        val transport = FakeTransport(responses = mapOf("0100" to "41 00 98 00 60 10"))

        val supported = PidScanner(ObdCommandExecutor(transport), settings).scan()

        assertEquals(setOf(0x04, 0x05), supported)
    }

    @Test
    fun `status pids saved by older versions are dropped from the cache`() = runTest {
        settings.update { this[AppSettings.CACHED_PIDS] = "1,4,C,12,13,1C,24,4F" }

        assertEquals(setOf(0x04, 0x0C, 0x24), settings.cachedPids.first())
    }
}
