# Peugeot 407 + Vgate iCar Pro → Home Assistant

Guide d'installation pour envoyer en direct les données OBD2 de la Peugeot 407 vers Home Assistant et les afficher dans un tableau de bord.

## Ce qu'il faut

- Un téléphone ou une tablette de voiture sous Android 8 ou plus récent, qui reste dans la voiture (connexion 4G). Les tablettes et autoradios qui affichent « Android 13 » mais tournent en réalité sous Android 10 ou 11 sont pris en charge
- Un adaptateur **Vgate iCar Pro**, en version Bluetooth 3.0 ou BLE 4.0 (les deux sont gérées)
- Home Assistant joignable depuis Internet **en HTTPS** avec un certificat valide : Nabu Casa (`https://xxxx.ui.nabu.casa`), ou un nom de domaine avec Let's Encrypt / reverse proxy

## 1. Brancher et appairer l'adaptateur

1. La prise OBD de la 407 est sous le volant, derrière un petit cache à gauche de la colonne de direction.
2. Branchez le Vgate et mettez le contact (position « marche », le moteur peut rester éteint).
3. Sur le téléphone, ouvrez **Réglages Android → Bluetooth** et appairez l'adaptateur (« Vgate iCar Pro » ou « V-LINK » ; code **1234** si demandé).

> L'iCar Pro se met en veille quand la voiture est coupée : la collecte reprend toute seule au prochain contact.

## 2. Configurer l'application

Dans l'application, ouvrez **Réglages** :

1. **Adaptateur OBD2** : choisissez le Vgate dans la liste. S'il n'y est pas (par exemple sur une tablette de voiture dont l'écran Bluetooth n'affiche que les téléphones), appuyez sur **Rechercher les adaptateurs** : l'application active le Bluetooth, cherche elle-même l'adaptateur (Bluetooth classique et BLE) et l'appaire avec le code 1234. Sur Android 8 à 11, la recherche demande l'autorisation de localisation et que la localisation soit activée.
2. **Connexion à Home Assistant** : laissez **Webhook HTTPS**.
3. **URL de Home Assistant** : votre adresse externe, par exemple `https://maison.exemple.fr`.
4. **ID du webhook** : il est généré automatiquement. C'est le seul secret, ne le partagez pas.
5. **Intervalle d'envoi** : 1 seconde pour du temps réel.
6. Appuyez sur **Tester la connexion** : le message « ✓ Serveur joignable » doit s'afficher.
7. **Scanner OBD2 → Protocole** : laissez **Automatique**, ou choisissez directement **ISO 14230-4 KWP2000 (init rapide)**, le protocole confirmé sur une 407 avec le Vgate iCar Pro. La recherche automatique de l'adaptateur échoue sur cette voiture ; l'application essaie alors elle-même chaque protocole et retient celui qui répond. Selon la motorisation, la 407 utilise le CAN (ISO 15765-4) ou le KWP2000 (ISO 14230-4) ; l'application trouve le bon.
8. **Démarrage automatique** est activé par défaut : la collecte démarre toute seule à chaque démarrage de l'appareil (démarrage complet, « démarrage rapide » des autoradios) et après chaque mise à jour, sans ouvrir l'application. Une tâche de secours, conservée par Android d'un redémarrage à l'autre, relance aussi la collecte si l'autoradio ne prévient pas les applications de son démarrage. **Ouvrez l'application une fois après l'installation** : Android ne lance pas une application jamais ouverte. Le **Journal** indique ce qui a démarré la collecte (« Démarrage automatique : démarrage de l'appareil »).
9. **Lire seulement moteur tournant** est désactivé par défaut : l'application se connecte dès que l'appareil démarre, contact mis. Activez-le seulement si l'appareil reste allumé voiture garée (voir « Batterie »).
10. *Facultatif* — **Jeton d'accès Home Assistant** : uniquement pour pouvoir effacer les défauts depuis Home Assistant (voir « Diagnostic »).

Revenez à l'écran principal et appuyez sur **Démarrer**, **moteur tournant**. Au premier lancement, l'application interroge tous les PIDs pour savoir lesquels la 407 gère (de 30 secondes à quelques minutes ; l'avancement s'affiche dans la notification). Le résultat est mémorisé.

L'écran principal affiche ensuite le protocole détecté et les mesures en direct.

## 3. Créer les capteurs dans Home Assistant

La configuration est un *package* Home Assistant : un fichier autonome, qui n'entre pas en conflit avec votre `configuration.yaml`.

1. Une seule fois, ajoutez ceci à `configuration.yaml` (avec l'add-on *File editor* ou *Studio Code Server*) :
   ```yaml
   homeassistant:
     packages: !include_dir_named packages
   ```
   Si une ligne `homeassistant:` existe déjà, ajoutez seulement `packages: …` en dessous.
2. Dans l'application : **Réglages → Webhook HTTPS → 1. Copier la configuration Home Assistant**.
3. Créez le fichier `/config/packages/peugeot_407.yaml` (et le dossier `packages` s'il n'existe pas), collez le texte et enregistrez.
4. **Paramètres → Système → Redémarrer** Home Assistant.

Vous obtenez :
- un capteur par mesure, par exemple `sensor.peugeot_407_regime_moteur` ou `sensor.peugeot_407_vitesse`, mis à jour à chaque envoi, et « indisponible » quand la collecte s'arrête ;
- les capteurs de diagnostic (voyant moteur, codes défaut…), qui gardent la dernière valeur connue voiture garée ;
- le bouton `input_button.peugeot_407_effacer_les_defauts` ;
- une automatisation qui crée une notification quand un nouveau défaut apparaît.

> Recopiez la configuration après avoir changé la sélection des PIDs, l'ID du webhook ou le jeton.

## 4. Créer le tableau de bord

1. Dans l'application : **2. Copier le tableau de bord**.
2. Dans Home Assistant : **Paramètres → Tableaux de bord → Ajouter un tableau de bord → Nouveau tableau de bord à partir de zéro**, nommez-le « Peugeot 407 ».
3. Ouvrez-le, puis **⋮ → Modifier le tableau de bord → ⋮ → Éditeur de configuration brute**, remplacez tout par le texte copié et enregistrez.

Vous obtenez des jauges (régime, vitesse, température moteur, batterie, charge, papillon…), un bloc **Diagnostic moteur** (voyant, codes défaut avec leur description, bouton d'effacement si un jeton est configuré), des courbes sur la dernière heure et la liste de toutes les mesures. Les cartes se modifient ensuite librement depuis l'éditeur visuel.

### Version avancée (jauges modernes)

Le bouton **2 bis. Copier le tableau de bord avancé (HACS)** produit le même tableau de bord avec des cartes plus jolies : jauges rondes avec aiguille et zones colorées dégradées, et courbes lissées et remplies. Il faut d'abord installer, dans **HACS** (rubrique *Interface* / *Frontend*), les cartes **Modern Circular Gauge** et **mini-graph-card**, puis recharger la page. La partie Diagnostic reste en cartes standard.

> Si vous renommez un capteur dans Home Assistant, son `entity_id` ne change pas, et le tableau de bord continue de fonctionner.

## Diagnostic : défauts moteur

L'application lit au démarrage puis chaque minute :
- le **voyant moteur** (allumé / éteint) ;
- les **codes défaut mémorisés**, par exemple `P0301` (« Ratés d'allumage cylindre 1 »), avec une description en français pour les codes génériques ;
- les **codes en attente** : défauts détectés une fois mais pas encore confirmés, utiles pour voir venir une panne ;
- les **tests antipollution** (catalyseur, sondes lambda, EGR…), terminés ou non : utile avant le contrôle technique.

Tout est visible dans l'application (carte **Diagnostic moteur** de l'écran principal) et dans Home Assistant.

**Effacer les défauts** (éteint le voyant) :
- *Depuis l'application* : écran **Diagnostic moteur → Effacer les défauts**.
- *Depuis Home Assistant* : bouton **Effacer les défauts** du tableau de bord. Il faut avoir renseigné un **jeton d'accès** dans l'application : dans HA, **Profil → Sécurité → Jetons d'accès longue durée → Créer un jeton**. Créez-le de préférence avec un utilisateur dédié, non administrateur : le jeton donne accès à l'API de Home Assistant. L'application ne s'en sert que pour lire l'état du bouton, toutes les 15 secondes.

Dans les deux cas, **mettez le contact, moteur arrêté** : beaucoup de calculateurs refusent l'effacement moteur tournant. L'effacement passe outre la protection de la batterie pour cette seule demande. Une demande de plus de 2 minutes est ignorée, pour ne jamais effacer par surprise au démarrage suivant.

> L'effacement ne répare rien : si le défaut est toujours là, le voyant se rallume. Il remet aussi les tests antipollution à zéro : évitez-le juste avant un contrôle technique.

Seuls les défauts du **calculateur moteur** sont accessibles en OBD2. ABS/ESP, airbag, boîte automatique, BSI, FAP… nécessitent l'outil Peugeot (Diagbox). Pour les codes `P1xxx` (propres à Peugeot), l'application affiche le code, mais pas sa signification exacte.

## Batterie : adaptateur laissé branché

La prise OBD est alimentée en permanence. Avec **Lire seulement moteur tournant** (désactivé par défaut, utile seulement si l'appareil reste allumé voiture garée), l'application lit d'abord la tension batterie mesurée par l'adaptateur, ce qui ne réveille aucun calculateur :
- **au-dessus de 13,3 V**, l'alternateur charge, donc le moteur tourne : l'application interroge la voiture ;
- **en dessous**, elle ne l'interroge pas, affiche « Moteur arrêté » et revérifie toutes les 20 secondes pendant les 10 minutes qui suivent le démarrage de l'appareil (l'autoradio s'allume avant le moteur), puis toutes les 2 minutes, et aussitôt que l'écran se rallume ou que le Bluetooth revient (réveil de l'autoradio au démarrage).

Les calculateurs (dont le BSI) peuvent ainsi se mettre en veille normalement voiture garée. Pour une immobilisation de plusieurs semaines, débranchez quand même l'adaptateur.

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
| « Le calculateur ne répond sur aucun protocole » | Moteur tournant ? En mode Automatique, l'application essaie déjà chaque protocole. Sinon, regardez le **Journal** : des lignes `UNABLE TO CONNECT` sur tous les protocoles indiquent un calculateur qui ne répond pas en OBD2 standard |
| Reste sur « Moteur arrêté » alors que le moteur tourne | Batterie ou alternateur faibles (moins de 13,3 V moteur tournant) : désactivez **Lire seulement moteur tournant** et faites contrôler la charge |
| « Refusé par le calculateur » à l'effacement | Coupez le moteur en laissant le contact, puis recommencez |
| Le bouton HA n'efface rien | Jeton absent ou invalide dans l'application, ou bouton pressé voiture coupée depuis plus de 2 minutes |
| Reste sur « Connexion… » | L'étape en cours s'affiche sous les cartes de l'écran principal. Touchez **Journal** pour voir chaque échange avec l'adaptateur, puis **Copier le journal** pour le transmettre |
| « Code incorrect » à l'appairage, sans pouvoir taper le code | La tablette répond toute seule avec un mauvais code. Appairez depuis l'application (**Rechercher les adaptateurs**, puis toucher le Vgate) : elle répond 1234, puis 0000. Si l'appairage échoue quand même, choisissez **Utiliser … sans appairage** |
| La recherche dans l'application ne trouve pas le Vgate | Adaptateur branché, contact mis, localisation activée (Android 8 à 11). Si rien n'apparaît, même pas un téléphone, la puce Bluetooth de la tablette est réservée au téléphone : utilisez le téléphone pour la collecte |
| « Service OBD BLE inconnu » | Version BLE non reconnue : signalez le modèle exact de l'adaptateur |
| Home Assistant « Hors ligne », mais OBD2 « En ligne » | Pas de réseau, ou URL incorrecte. Les mesures reprennent dès le retour du réseau, sans couper le Bluetooth |
| Capteurs « indisponible » dans HA | La collecte est arrêtée, ou la configuration n'a pas été rechargée (étape 3) |
| Peu de PIDs détectés | Normal sur les calculateurs anciens. Pour relancer une détection : **Vider le cache Mode 01**, puis redémarrer la collecte contact mis |
