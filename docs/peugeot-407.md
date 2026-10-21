# Peugeot 407 + Vgate iCar Pro → Home Assistant

Guide d'installation pour envoyer en direct les données OBD2 de la Peugeot 407 vers Home Assistant et les afficher dans un tableau de bord.

## Ce qu'il faut

- Un téléphone Android 13 ou plus récent, qui reste dans la voiture (connexion 4G)
- Un adaptateur **Vgate iCar Pro**, en version Bluetooth 3.0 ou BLE 4.0 (les deux sont gérées)
- Home Assistant joignable depuis Internet **en HTTPS** avec un certificat valide : Nabu Casa (`https://xxxx.ui.nabu.casa`), ou un nom de domaine avec Let's Encrypt / reverse proxy

## 1. Brancher et appairer l'adaptateur

1. La prise OBD de la 407 est sous le volant, derrière un petit cache à gauche de la colonne de direction.
2. Branchez le Vgate et mettez le contact (position « marche », le moteur peut rester éteint).
3. Sur le téléphone, ouvrez **Réglages Android → Bluetooth** et appairez l'adaptateur (« Vgate iCar Pro » ou « V-LINK » ; code **1234** si demandé).

> L'iCar Pro se met en veille quand la voiture est coupée : la collecte reprend toute seule au prochain contact.

## 2. Configurer l'application

Dans l'application, ouvrez **Réglages** :

1. **Adaptateur OBD2** : choisissez le Vgate dans la liste.
2. **Connexion à Home Assistant** : laissez **Webhook HTTPS**.
3. **URL de Home Assistant** : votre adresse externe, par exemple `https://maison.exemple.fr`.
4. **ID du webhook** : il est généré automatiquement. C'est le seul secret, ne le partagez pas.
5. **Intervalle d'envoi** : 1 seconde pour du temps réel.
6. Appuyez sur **Tester la connexion** : le message « ✓ Serveur joignable » doit s'afficher.
7. **Scanner OBD2 → Protocole** : laissez **Automatique**. Selon la motorisation, la 407 utilise le CAN (ISO 15765-4) ou le KWP2000 (ISO 14230-4) ; l'application trouve le bon.
8. Activez **Démarrage automatique** si le téléphone reste dans la voiture.

Revenez à l'écran principal et appuyez sur **Démarrer**, contact mis. Au premier lancement, l'application interroge tous les PIDs pour savoir lesquels la 407 gère (de 30 secondes à quelques minutes ; l'avancement s'affiche dans la notification). Le résultat est mémorisé.

L'écran principal affiche ensuite le protocole détecté et les mesures en direct.

## 3. Créer les capteurs dans Home Assistant

1. Dans l'application : **Réglages → Webhook HTTPS → 1. Copier la configuration Home Assistant**.
2. Collez le texte dans `configuration.yaml` (avec l'add-on *File editor* ou *Studio Code Server*).
3. **Outils de développement → YAML → Entités de modèle** : cliquez sur **Recharger**.

Un capteur est créé par mesure, par exemple `sensor.peugeot_407_regime_moteur` ou `sensor.peugeot_407_vitesse`. Ils se mettent à jour à chaque envoi et passent à « indisponible » quand la collecte s'arrête.

> Recopiez la configuration après avoir changé la sélection des PIDs ou l'ID du webhook.

## 4. Créer le tableau de bord

1. Dans l'application : **2. Copier le tableau de bord**.
2. Dans Home Assistant : **Paramètres → Tableaux de bord → Ajouter un tableau de bord → Nouveau tableau de bord à partir de zéro**, nommez-le « Peugeot 407 ».
3. Ouvrez-le, puis **⋮ → Modifier le tableau de bord → ⋮ → Éditeur de configuration brute**, remplacez tout par le texte copié et enregistrez.

Vous obtenez des jauges (régime, vitesse, température moteur, batterie, charge, papillon…), des courbes sur la dernière heure et la liste de toutes les mesures. Les cartes se modifient ensuite librement depuis l'éditeur visuel.

> Si vous renommez un capteur dans Home Assistant, son `entity_id` ne change pas, et le tableau de bord continue de fonctionner.

## Choisir les mesures et leur fréquence

**Réglages → Sélection des PIDs** :

- **Rapide** : lu chaque seconde (10 maximum). Par défaut : régime, vitesse, charge, papillon, température moteur, pression d'admission, débit d'air et tension batterie, s'ils sont gérés par la voiture.
- **Lent** : lu toutes les 10 secondes. Par défaut, **toutes les autres mesures détectées**.
- **Off** : ni lu ni envoyé.

## Quelles données attendre d'une 407 ?

L'OBD2 « standard » (EOBD) donne accès aux données **moteur** : régime, vitesse, charge, températures (liquide de refroidissement, air d'admission), pression d'admission, débit d'air, papillon, corrections de richesse, sondes lambda, avance, temps de fonctionnement, distance depuis l'effacement des défauts…, plus la **tension batterie**, mesurée par l'adaptateur.

Le niveau de carburant, le kilométrage, la température d'huile ou la consommation instantanée **ne sont généralement pas disponibles** en EOBD sur une 407 (calculateurs 2004-2010). Ces informations passent par les protocoles propriétaires PSA (Diagbox) sur les autres calculateurs et le réseau confort, que l'ELM327 ne lit pas en mode standard. L'application n'affiche que ce que la voiture renvoie vraiment.

## En cas de problème

| Symptôme | Piste |
|---|---|
| « Adaptateur ELM327 non détecté » | Adaptateur mal enfoncé, ou mauvais appareil Bluetooth sélectionné |
| « Le calculateur ne répond pas (contact mis ?) » | Mettez le contact. Sinon, essayez un protocole forcé : CAN 11 bits 500 kbit/s, puis KWP2000 (init rapide) |
| « Service OBD BLE inconnu » | Version BLE non reconnue : signalez le modèle exact de l'adaptateur |
| Home Assistant « Hors ligne », mais OBD2 « En ligne » | Pas de réseau, ou URL incorrecte. Les mesures reprennent dès le retour du réseau, sans couper le Bluetooth |
| Capteurs « indisponible » dans HA | La collecte est arrêtée, ou la configuration n'a pas été rechargée (étape 3) |
| Peu de PIDs détectés | Normal sur les calculateurs anciens. Pour relancer une détection : **Vider le cache Mode 01**, puis redémarrer la collecte contact mis |
