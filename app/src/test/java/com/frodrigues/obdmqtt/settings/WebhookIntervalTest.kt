package com.frodrigues.obdmqtt.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.frodrigues.obdmqtt.ui.formatSeconds
import com.frodrigues.obdmqtt.ui.parseIntervalMs
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

class WebhookIntervalTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val settings by lazy {
        AppSettings(PreferenceDataStoreFactory.create(scope = dataStoreScope) { File(tmp.root, "test.preferences_pb") })
    }

    @After
    fun tearDown() = dataStoreScope.cancel()

    @Test
    fun `old one second default becomes the faster default`() = runTest {
        settings.update { this[AppSettings.WEBHOOK_INTERVAL] = 1 }
        assertEquals(AppSettings.DEFAULT_WEBHOOK_INTERVAL_MS, settings.webhookIntervalMs.first())
    }

    @Test
    fun `old slower custom interval is kept`() = runTest {
        settings.update { this[AppSettings.WEBHOOK_INTERVAL] = 5 }
        assertEquals(5_000, settings.webhookIntervalMs.first())
    }

    @Test
    fun `interval text uses seconds with a french comma`() {
        assertEquals(300, parseIntervalMs("0,3"))
        assertEquals(500, parseIntervalMs("0.5"))
        assertEquals(1000, parseIntervalMs("1"))
        assertNull(parseIntervalMs("0,1"))  // below the minimum
        assertNull(parseIntervalMs("vite"))
        assertEquals("0,3", formatSeconds(300))
        assertEquals("1", formatSeconds(1000))
    }
}
