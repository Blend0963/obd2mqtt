package com.frodrigues.obdmqtt.obd

object PidRegistry {

    /**
     * Pseudo-PID for the battery voltage measured by the ELM327 itself (AT RV).
     * Works on every car, even when the ECU doesn't support PID 0x42.
     */
    const val ADAPTER_VOLTAGE = 0x100

    /**
     * Announced by the ECU but not measurements (monitor status, OBD standard, sensors
     * present, scaling limits…): never polled nor sent to Home Assistant.
     */
    val STATUS_PIDS: Set<Int> = setOf(0x01, 0x02, 0x03, 0x12, 0x13, 0x1C, 0x1D, 0x1E, 0x41, 0x4F, 0x50)

    val definitions: Map<Int, PidDefinition> = mapOf(
        // ── Engine / Load ─────────────────────────────────────────────────────
        0x04 to PidDefinition(0x04, "Charge moteur", "%", null) { b ->
            b.u(0) / 2.55
        },
        0x43 to PidDefinition(0x43, "Charge absolue", "%", null) { b ->
            100.0 * (b.u(0) * 256 + b.u(1)) / 255.0
        },

        // ── Temperatures ──────────────────────────────────────────────────────
        0x05 to PidDefinition(0x05, "Température liquide de refroidissement", "°C", "temperature") { b ->
            b.u(0) - 40.0
        },
        0x0F to PidDefinition(0x0F, "Température air admission", "°C", "temperature") { b ->
            b.u(0) - 40.0
        },
        0x46 to PidDefinition(0x46, "Température extérieure", "°C", "temperature") { b ->
            b.u(0) - 40.0
        },
        0x5C to PidDefinition(0x5C, "Température huile", "°C", "temperature") { b ->
            b.u(0) - 40.0
        },

        // ── Fuel Trims ────────────────────────────────────────────────────────
        0x06 to PidDefinition(0x06, "Correction carburant court terme B1", "%", null) { b ->
            b.u(0) / 1.28 - 100.0
        },
        0x07 to PidDefinition(0x07, "Correction carburant long terme B1", "%", null) { b ->
            b.u(0) / 1.28 - 100.0
        },
        0x08 to PidDefinition(0x08, "Correction carburant court terme B2", "%", null) { b ->
            b.u(0) / 1.28 - 100.0
        },
        0x09 to PidDefinition(0x09, "Correction carburant long terme B2", "%", null) { b ->
            b.u(0) / 1.28 - 100.0
        },

        // ── Pressure ──────────────────────────────────────────────────────────
        0x0A to PidDefinition(0x0A, "Pression carburant", "kPa", "pressure") { b ->
            3.0 * b.u(0)
        },
        0x0B to PidDefinition(0x0B, "Pression collecteur admission", "kPa", "pressure") { b ->
            b.u(0).toDouble()
        },
        0x22 to PidDefinition(0x22, "Pression rampe relative", "kPa", "pressure") { b ->
            0.079 * (b.u(0) * 256 + b.u(1))
        },
        0x23 to PidDefinition(0x23, "Pression rampe injection", "kPa", "pressure") { b ->
            10.0 * (b.u(0) * 256 + b.u(1))
        },
        0x33 to PidDefinition(0x33, "Pression atmosphérique", "kPa", "pressure") { b ->
            b.u(0).toDouble()
        },
        0x59 to PidDefinition(0x59, "Pression rampe absolue", "kPa", "pressure") { b ->
            10.0 * (b.u(0) * 256 + b.u(1))
        },

        // ── Speed / RPM ───────────────────────────────────────────────────────
        0x0C to PidDefinition(0x0C, "Régime moteur", "rpm", null) { b ->
            (b.u(0) * 256 + b.u(1)) / 4.0
        },
        0x0D to PidDefinition(0x0D, "Vitesse", "km/h", "speed") { b ->
            b.u(0).toDouble()
        },

        // ── Timing / Ignition ─────────────────────────────────────────────────
        0x0E to PidDefinition(0x0E, "Avance allumage", "°", null) { b ->
            b.u(0) / 2.0 - 64.0
        },
        0x5D to PidDefinition(0x5D, "Calage injection", "°", null) { b ->
            (b.u(0) * 256 + b.u(1)) / 128.0 - 210.0
        },

        // ── Air Flow ──────────────────────────────────────────────────────────
        0x10 to PidDefinition(0x10, "Débit air", "g/s", null) { b ->
            (b.u(0) * 256 + b.u(1)) / 100.0
        },
        0x50 to PidDefinition(0x50, "Débit air maximum", "g/s", null) { b ->
            b.u(0) * 10.0
        },

        // ── Throttle ──────────────────────────────────────────────────────────
        0x11 to PidDefinition(0x11, "Position papillon", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },
        0x45 to PidDefinition(0x45, "Papillon relatif", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },
        0x47 to PidDefinition(0x47, "Papillon absolu B", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },
        0x48 to PidDefinition(0x48, "Papillon absolu C", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },
        0x4C to PidDefinition(0x4C, "Commande papillon", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },

        // ── Accelerator Pedal ─────────────────────────────────────────────────
        0x49 to PidDefinition(0x49, "Pédale accélérateur D", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },
        0x4A to PidDefinition(0x4A, "Pédale accélérateur E", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },
        0x4B to PidDefinition(0x4B, "Pédale accélérateur F", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },
        0x5A to PidDefinition(0x5A, "Pédale accélérateur relative", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },

        // ── Fuel ──────────────────────────────────────────────────────────────
        0x2F to PidDefinition(0x2F, "Niveau carburant", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },
        0x5E to PidDefinition(0x5E, "Consommation instantanée", "L/h", null) { b ->
            (b.u(0) * 256 + b.u(1)) / 20.0
        },
        0x51 to PidDefinition(0x51, "Type de carburant", "", null) { b ->
            b.u(0).toDouble()
        },
        0x52 to PidDefinition(0x52, "Taux éthanol", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },

        // ── O2 Sensors ────────────────────────────────────────────────────────
        0x14 to PidDefinition(0x14, "Tension sonde lambda 1", "V", "voltage") { b ->
            b.u(0) / 200.0
        },
        0x15 to PidDefinition(0x15, "Tension sonde lambda 2", "V", "voltage") { b ->
            b.u(0) / 200.0
        },

        // ── Air-Fuel Ratio ────────────────────────────────────────────────────
        // Wide-band sensor: equivalence ratio from A,B (C,D = voltage). Capped at 2 by the
        // OBD scaling, so a diesel at idle (very lean) reads 2
        0x24 to PidDefinition(0x24, "Lambda sonde 1", "λ", null) { b ->
            2.0 * (b.u(0) * 256 + b.u(1)) / 65536.0
        },
        0x44 to PidDefinition(0x44, "Richesse commandée", "ratio", null) { b ->
            2.0 * (b.u(0) * 256 + b.u(1)) / 65536.0
        },

        // ── EGR / Evap ────────────────────────────────────────────────────────
        0x2C to PidDefinition(0x2C, "Commande EGR", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },
        0x2D to PidDefinition(0x2D, "Erreur EGR", "%", null) { b ->
            (100.0 * b.u(0) / 128.0) - 100.0
        },
        0x2E to PidDefinition(0x2E, "Commande purge canister", "%", null) { b ->
            100.0 * b.u(0) / 255.0
        },

        // ── Voltage ───────────────────────────────────────────────────────────
        0x42 to PidDefinition(0x42, "Tension calculateur", "V", "voltage") { b ->
            (b.u(0) * 256 + b.u(1)) / 1000.0
        },

        // ── Counters / Distance ───────────────────────────────────────────────
        0x1F to PidDefinition(0x1F, "Temps depuis démarrage", "s", "duration") { b ->
            (b.u(0) * 256 + b.u(1)).toDouble()
        },
        0x21 to PidDefinition(0x21, "Distance avec voyant défaut", "km", "distance") { b ->
            (b.u(0) * 256 + b.u(1)).toDouble()
        },
        0x30 to PidDefinition(0x30, "Démarrages à chaud depuis effacement", "", null) { b ->
            b.u(0).toDouble()
        },
        0x31 to PidDefinition(0x31, "Distance depuis effacement défauts", "km", "distance") { b ->
            (b.u(0) * 256 + b.u(1)).toDouble()
        },
        0x4D to PidDefinition(0x4D, "Temps avec voyant défaut", "min", "duration") { b ->
            (b.u(0) * 256 + b.u(1)).toDouble()
        },
        0x4E to PidDefinition(0x4E, "Temps depuis effacement défauts", "min", "duration") { b ->
            (b.u(0) * 256 + b.u(1)).toDouble()
        },
        0xA6 to PidDefinition(0xA6, "Kilométrage", "km", "distance") { b ->
            (b.u(0) * 16777216L + b.u(1) * 65536L + b.u(2) * 256L + b.u(3)) / 10.0
        },

        // ── Torque ────────────────────────────────────────────────────────────
        0x61 to PidDefinition(0x61, "Couple demandé", "%", null) { b ->
            b.u(0) - 125.0
        },
        0x62 to PidDefinition(0x62, "Couple réel", "%", null) { b ->
            b.u(0) - 125.0
        },
        0x63 to PidDefinition(0x63, "Couple de référence", "N·m", null) { b ->
            (b.u(0) * 256 + b.u(1)).toDouble()
        },

        // ── O2 Lambda / AFR ──────────────────────────────────────────────────
        // 0x34: 4 bytes — bytes A/B = lambda ratio, C/D = sensor current
        0x34 to PidDefinition(0x34, "Lambda sonde 1", "ratio", null) { b ->
            2.0 * (b.u(0) * 256 + b.u(1)) / 65536.0
        },

        // ── Catalyst Temperature ──────────────────────────────────────────────
        // 0x3C: Bank 1 Sensor 1 — (256A+B)/10 - 40
        0x3C to PidDefinition(0x3C, "Température catalyseur B1 S1", "°C", "temperature") { b ->
            (b.u(0) * 256 + b.u(1)) / 10.0 - 40.0
        },
        0x3D to PidDefinition(0x3D, "Température catalyseur B2 S1", "°C", "temperature") { b ->
            (b.u(0) * 256 + b.u(1)) / 10.0 - 40.0
        },

        // ── Secondary O2 Trim ────────────────────────────────────────────────
        0x56 to PidDefinition(0x56, "Correction lambda long terme B1", "%", null) { b ->
            (100.0 * b.u(0) / 128.0) - 100.0
        },

        // ── Dual Coolant Temp (0x67 byte A = sensor count, B = sensor1, C = sensor2)
        0x67 to PidDefinition(0x67, "Température liquide capteur 1", "°C", "temperature") { b ->
            b.u(1) - 40.0
        },

        // ── Hybrid / Battery ──────────────────────────────────────────────────
        0x5B to PidDefinition(0x5B, "Batterie hybride", "%", "battery") { b ->
            100.0 * b.u(0) / 255.0
        },

        // ── Transmission ──────────────────────────────────────────────────────
        0xA4 to PidDefinition(0xA4, "Rapport de boîte", "ratio", null) { b ->
            (b.u(2) * 256 + b.u(3)) / 1000.0
        },

        // ── Adaptateur ────────────────────────────────────────────────────────
        // Value is parsed from the "12.6V" text reply in PidParser, not from data bytes
        ADAPTER_VOLTAGE to PidDefinition(ADAPTER_VOLTAGE, "Tension batterie", "V", "voltage") { 0.0 }
    )

    /** ELM327 command that reads [pid]. */
    fun command(pid: Int): String =
        if (pid == ADAPTER_VOLTAGE) "ATRV" else "01${pid.toString(16).padStart(2, '0').uppercase()}"

    fun getOrUnknown(pid: Int): PidDefinition = definitions[pid] ?: PidDefinition(
        pid = pid,
        name = "PID 0x${pid.toString(16).padStart(2, '0').uppercase()}",
        unit = "",
        haDeviceClass = null,
        formula = { b ->
            when {
                b.size >= 2 -> (b.u(0) * 256 + b.u(1)).toDouble()
                b.isNotEmpty() -> b.u(0).toDouble()
                else -> 0.0
            }
        }
    )
}
