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
    fun `brute-force scan keeps responding pids and adapter voltage`() = runTest {
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
        assertFalse("support bitmask PIDs are not probed", "0100" in transport.sentCommands)
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
}
