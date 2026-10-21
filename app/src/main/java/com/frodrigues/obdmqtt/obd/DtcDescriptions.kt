package com.frodrigues.obdmqtt.obd

/**
 * French descriptions of generic (SAE J2012) fault codes most often seen on petrol and
 * diesel engines. Codes not listed fall back to the subsystem their range belongs to;
 * manufacturer codes (P1xxx…) need Peugeot's own tables (Diagbox).
 */
object DtcDescriptions {

    private val CIRCUIT_SUFFIXES = listOf("circuit", "plage / performance", "signal bas", "signal haut")

    private val EXACT: Map<String, String> = buildMap {
        fun sensor(start: Int, name: String) =
            CIRCUIT_SUFFIXES.forEachIndexed { i, s -> put("P0%03d".format(start + i), "$name — $s") }

        put("P0010", "Actionneur arbre à cames A (banc 1) — circuit")
        put("P0016", "Corrélation vilebrequin / arbre à cames (banc 1, capteur A)")
        put("P0045", "Commande de turbocompresseur — circuit")
        put("P0069", "Corrélation pression collecteur / pression atmosphérique")
        put("P0087", "Pression rampe carburant trop basse")
        put("P0088", "Pression rampe carburant trop haute")
        put("P0093", "Fuite de carburant importante détectée")
        put("P0100", "Débitmètre d'air — circuit")
        put("P0101", "Débitmètre d'air — plage / performance")
        put("P0102", "Débitmètre d'air — signal bas")
        put("P0103", "Débitmètre d'air — signal haut")
        put("P0105", "Capteur pression collecteur — circuit")
        put("P0106", "Capteur pression collecteur — plage / performance")
        put("P0107", "Capteur pression collecteur — signal bas")
        put("P0108", "Capteur pression collecteur — signal haut")
        put("P0110", "Sonde température air d'admission — circuit")
        put("P0111", "Sonde température air d'admission — plage / performance")
        put("P0112", "Sonde température air d'admission — signal bas")
        put("P0113", "Sonde température air d'admission — signal haut")
        put("P0115", "Sonde température liquide de refroidissement — circuit")
        put("P0116", "Sonde température liquide de refroidissement — plage / performance")
        put("P0117", "Sonde température liquide de refroidissement — signal bas")
        put("P0118", "Sonde température liquide de refroidissement — signal haut")
        put("P0120", "Capteur position papillon / pédale A — circuit")
        put("P0121", "Capteur position papillon / pédale A — plage / performance")
        put("P0122", "Capteur position papillon / pédale A — signal bas")
        put("P0123", "Capteur position papillon / pédale A — signal haut")
        put("P0125", "Température moteur insuffisante pour la régulation de richesse")
        put("P0128", "Thermostat : température moteur sous le seuil de régulation")
        listOf(130 to "banc 1, sonde 1", 136 to "banc 1, sonde 2", 150 to "banc 2, sonde 1", 156 to "banc 2, sonde 2")
            .forEach { (start, where) ->
                listOf("circuit", "tension basse", "tension haute", "réponse lente", "pas d'activité", "chauffage — circuit")
                    .forEachIndexed { i, s -> put("P0%03d".format(start + i), "Sonde lambda ($where) — $s") }
            }
        put("P0170", "Correction de richesse (banc 1)")
        put("P0171", "Mélange trop pauvre (banc 1)")
        put("P0172", "Mélange trop riche (banc 1)")
        put("P0173", "Correction de richesse (banc 2)")
        put("P0174", "Mélange trop pauvre (banc 2)")
        put("P0175", "Mélange trop riche (banc 2)")
        sensor(190, "Capteur pression rampe carburant")
        put("P0200", "Injecteurs — circuit")
        for (cyl in 1..12) put("P02%02d".format(cyl), "Injecteur cylindre $cyl — circuit")
        put("P0219", "Surrégime moteur")
        put("P0230", "Relais pompe à carburant — circuit")
        put("P0234", "Suralimentation excessive (turbo)")
        sensor(235, "Capteur pression de suralimentation A")
        put("P0299", "Suralimentation insuffisante (turbo)")
        put("P0300", "Ratés d'allumage aléatoires / multiples")
        for (cyl in 1..12) put("P03%02d".format(cyl), "Ratés d'allumage cylindre $cyl")
        put("P0325", "Capteur de cliquetis 1 — circuit")
        put("P0335", "Capteur position vilebrequin — circuit")
        put("P0336", "Capteur position vilebrequin — plage / performance")
        put("P0340", "Capteur position arbre à cames — circuit")
        put("P0341", "Capteur position arbre à cames — plage / performance")
        for (coil in 1..6) put("P035$coil", "Bobine d'allumage ${"ABCDEF"[coil - 1]} — circuit")
        put("P0380", "Bougies de préchauffage — circuit")
        put("P0381", "Témoin de préchauffage — circuit")
        put("P0400", "Recirculation des gaz d'échappement (EGR) — débit")
        put("P0401", "Débit EGR insuffisant")
        put("P0402", "Débit EGR excessif")
        put("P0403", "Commande vanne EGR — circuit")
        put("P0404", "Vanne EGR — plage / performance")
        put("P0405", "Capteur position vanne EGR — signal bas")
        put("P0406", "Capteur position vanne EGR — signal haut")
        put("P0420", "Efficacité du catalyseur sous le seuil (banc 1)")
        put("P0430", "Efficacité du catalyseur sous le seuil (banc 2)")
        put("P0440", "Système d'évaporation (canister) — défaut")
        put("P0441", "Système d'évaporation — débit de purge incorrect")
        put("P0442", "Système d'évaporation — petite fuite")
        put("P0443", "Électrovanne de purge canister — circuit")
        put("P0455", "Système d'évaporation — fuite importante")
        put("P0456", "Système d'évaporation — très petite fuite")
        put("P0480", "Commande ventilateur de refroidissement 1 — circuit")
        put("P0500", "Capteur de vitesse véhicule")
        put("P0501", "Capteur de vitesse véhicule — plage / performance")
        put("P0505", "Régulation du ralenti")
        put("P0506", "Régime de ralenti trop bas")
        put("P0507", "Régime de ralenti trop haut")
        put("P0560", "Tension système (batterie)")
        put("P0562", "Tension système trop basse")
        put("P0563", "Tension système trop haute")
        put("P0571", "Contacteur de frein A — circuit")
        put("P0600", "Liaison de communication série")
        put("P0601", "Calculateur — erreur mémoire (somme de contrôle)")
        put("P0602", "Calculateur — erreur de programmation")
        put("P0603", "Calculateur — erreur mémoire permanente (KAM)")
        put("P0604", "Calculateur — erreur mémoire vive (RAM)")
        put("P0605", "Calculateur — erreur mémoire morte (ROM)")
        put("P0606", "Calculateur — défaut processeur")
        put("P0645", "Relais embrayage compresseur de climatisation — circuit")
        put("P0650", "Commande du voyant moteur — circuit")
        put("P0685", "Relais d'alimentation calculateur — circuit")
        put("P0700", "Boîte de vitesses : défaut signalé par le calculateur de boîte")
        put("P2002", "Efficacité du filtre à particules sous le seuil (banc 1)")
        put("P242F", "Filtre à particules — accumulation de cendres")
        put("P2463", "Filtre à particules — accumulation de suie")
    }

    fun describe(code: String): String {
        EXACT[code]?.let { return it }
        if (code.length != 5) return "Code inconnu"
        val family = code.substring(0, 2)
        return when {
            code[0] == 'C' -> "Châssis (ABS, ESP, direction…)"
            code[0] == 'B' -> "Carrosserie (airbag, confort, éclairage…)"
            code[0] == 'U' -> "Réseau de communication entre calculateurs"
            family == "P1" || family == "P3" -> "Code constructeur Peugeot (détail dans Diagbox)"
            family == "P2" -> "Moteur / dépollution (code générique étendu)"
            else -> when (code[2]) {
                '0', '1', '2' -> "Mesure air / carburant"
                '3' -> "Allumage ou ratés d'allumage"
                '4' -> "Dispositifs antipollution"
                '5' -> "Vitesse, ralenti ou entrées du calculateur"
                '6' -> "Calculateur moteur ou sorties"
                '7', '8', '9' -> "Boîte de vitesses"
                'A' -> "Propulsion hybride"
                else -> "Code générique"
            }
        }
    }
}
