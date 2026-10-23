package com.frodrigues.obdmqtt.webhook

import com.frodrigues.obdmqtt.obd.PidRegistry
import com.frodrigues.obdmqtt.settings.AppConfig
import java.text.Normalizer

/**
 * Names and entity IDs of everything the app creates in Home Assistant, shared by the
 * sensor YAML, the dashboard and the clear-faults button poller so they always agree.
 * HA derives entity IDs from names: "Peugeot 407 Régime moteur" → sensor.peugeot_407_regime_moteur.
 */
class HaEntities(private val config: AppConfig) {

    private val device = config.deviceName

    val mac: String = config.btDeviceMac.replace(":", "").ifBlank { "car" }

    fun pidName(pid: Int) = "$device ${PidRegistry.getOrUnknown(pid).name}"
    fun pidEntityId(pid: Int) = sensorId(pidName(pid))

    val milName = "$device Voyant moteur"
    val milEntityId = "binary_sensor." + slugify(milName)
    val faultCountName = "$device Nombre de défauts"
    val faultCountEntityId = sensorId(faultCountName)
    val faultCodesName = "$device Codes défaut"
    val faultCodesEntityId = sensorId(faultCodesName)
    val pendingName = "$device Défauts en attente"
    val pendingEntityId = sensorId(pendingName)
    val readinessName = "$device Tests antipollution incomplets"
    val readinessEntityId = sensorId(readinessName)

    /** Current or last trip: state = distance, attribute "trajet" = the whole trip. */
    val tripName = "$device Trajet"
    val tripEntityId = sensorId(tripName)
    val fuelRateName = "$device Consommation instantanée"
    val fuelL100Name = "$device Consommation aux 100"

    val clearButtonName = "$device Effacer les défauts"
    /** Key under `input_button:` in YAML — the entity ID is `input_button.<key>`. */
    val clearButtonKey = slugify(clearButtonName)
    val clearButtonEntityId = "input_button.$clearButtonKey"

    private fun sensorId(name: String) = "sensor." + slugify(name)

    companion object {
        /** Same rules as Home Assistant's slugify: strip accents, lowercase, non-alphanumerics → "_". */
        fun slugify(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
    }
}
