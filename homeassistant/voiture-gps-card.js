/*
 * Voiture GPS — carte de navigation pour un tableau de bord Home Assistant.
 *
 * - flèche orientée dans le sens de la marche, carte qui tourne avec la route (« cap en haut »)
 * - suit la voiture et la place dans le bas de l'écran pour voir la route devant
 * - mouvement fluide entre deux positions GPS : la position est prolongée le long du cap
 *   avec la vitesse (celle de l'OBD si `speed_entity` est donné, sinon celle du GPS)
 * - trace du trajet, vitesse, panneau de limitation (facultatif)
 *
 * Installation : copier ce fichier dans /config/www/, puis Paramètres → Tableaux de bord →
 * ⋮ → Ressources → Ajouter : URL /local/voiture-gps-card.js, type « Module JavaScript ».
 *
 * type: custom:voiture-gps-card
 * entity: device_tracker.mycar                    # requis : position (appli compagnon), ou un capteur
 *                                                 # avec un attribut location: [lat, lon] (adresse géocodée)
 * speed_entity: sensor.peugeot_407_vitesse        # facultatif : vitesse OBD en km/h
 * speed_limit_entity: sensor.vitesse_limite       # facultatif : panneau de limitation
 * zoom: 16                                        # facultatif
 * pitch: 45                                       # inclinaison 3D (0 = vue du dessus)
 * heading_up: true                                # carte orientée dans le sens de la marche
 * follow_pause: 15                                # secondes avant de recentrer après un geste
 * layout: coin                                   # « coin » : panneau en haut à droite, vitesse en bas
 *                                                 # « groupe » : panneau et vitesse côte à côte en bas
 * hide_attribution: false                         # masquer la mention OpenStreetMap / CARTO
 * theme: auto                                    # « auto » : clair le jour, sombre la nuit (soleil) ;
 *                                                 # « sombre » (défaut) ou « clair »
 * style: <URL d'un style MapLibre>                # facultatif : fond de carte imposé
 */

const MAPLIBRE_VERSION = "4.7.1";
const MAPLIBRE_URL = `https://unpkg.com/maplibre-gl@${MAPLIBRE_VERSION}/dist/maplibre-gl`;
const DEFAULT_STYLE = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json";
const DAY_STYLE = "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json";
/** Condensed road-sign-like typeface; @font-face only works at document level, not in a shadow root. */
const FONT_URL = "https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@600;700&display=swap";
const FONT = "'Barlow Condensed', 'Roboto Condensed', 'Arial Narrow', sans-serif";

/** No GPS fix for this long: stop extrapolating instead of driving off the road. */
const MAX_PREDICT_S = 8;
/** Below this speed (m/s) the car is considered stopped: no extrapolation, heading kept. */
const MIN_MOVING_MPS = 0.8;
const TRAIL_POINTS = 600;
const EARTH_RADIUS_M = 6378137;

function loadMapLibre() {
  if (window.maplibregl) return Promise.resolve(window.maplibregl);
  if (!window.__voitureGpsMapLibre) {
    window.__voitureGpsMapLibre = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = `${MAPLIBRE_URL}.js`;
      script.onload = () => resolve(window.maplibregl);
      script.onerror = () => reject(new Error("Impossible de charger MapLibre (pas d'internet ?)"));
      document.head.appendChild(script);
    });
  }
  return window.__voitureGpsMapLibre;
}

function loadFont() {
  if (document.getElementById("voiture-gps-font")) return;
  const link = document.createElement("link");
  link.id = "voiture-gps-font";
  link.rel = "stylesheet";
  link.href = FONT_URL;
  document.head.appendChild(link);
}

const toRad = (d) => (d * Math.PI) / 180;
const toDeg = (r) => (r * 180) / Math.PI;

/** Point at `dist` metres from [lon, lat] along `bearing` (flat-earth, fine for a few hundred metres). */
function project([lon, lat], bearing, dist) {
  const b = toRad(bearing);
  const dLat = (dist * Math.cos(b)) / EARTH_RADIUS_M;
  const dLon = (dist * Math.sin(b)) / (EARTH_RADIUS_M * Math.cos(toRad(lat)));
  return [lon + toDeg(dLon), lat + toDeg(dLat)];
}

function distance([lon1, lat1], [lon2, lat2]) {
  const x = toRad(lon2 - lon1) * Math.cos(toRad((lat1 + lat2) / 2));
  const y = toRad(lat2 - lat1);
  return Math.hypot(x, y) * EARTH_RADIUS_M;
}

function bearing([lon1, lat1], [lon2, lat2]) {
  const y = Math.sin(toRad(lon2 - lon1)) * Math.cos(toRad(lat2));
  const x = Math.cos(toRad(lat1)) * Math.sin(toRad(lat2)) -
    Math.sin(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.cos(toRad(lon2 - lon1));
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

/** Interpolates angles the short way round (350° → 10° goes through 0°). */
function lerpAngle(a, b, t) {
  const diff = ((b - a + 540) % 360) - 180;
  return (a + diff * t + 360) % 360;
}

const ARROW_SVG = `
<svg viewBox="0 0 40 40" width="44" height="44" xmlns="http://www.w3.org/2000/svg">
  <circle cx="20" cy="20" r="18" fill="rgba(33,150,243,0.25)"/>
  <path d="M20 4 L32 33 L20 26 L8 33 Z" fill="#2196f3" stroke="white" stroke-width="2.5" stroke-linejoin="round"/>
</svg>`;

/**
 * French speed-limit sign (B14): white disc with a thin white rim, red ring of ~1/10 of the
 * diameter, black condensed digits.
 */
const SIGN_SVG = `
<svg viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg">
  <circle cx="50" cy="50" r="49.5" fill="#fff" stroke="rgba(0,0,0,0.35)" stroke-width="1"/>
  <circle cx="50" cy="50" r="42.5" fill="none" stroke="#c8102e" stroke-width="11"/>
  <text x="50" y="53" text-anchor="middle" dominant-baseline="middle" fill="#1a1a1a"
        font-family="${FONT}" font-weight="700" font-size="44" letter-spacing="-1"></text>
</svg>`;

class VoitureGpsCard extends HTMLElement {
  setConfig(config) {
    if (!config || !config.entity) {
      throw new Error("Indiquez « entity: » (le device_tracker de l'autoradio)");
    }
    this._config = {
      zoom: 16,
      pitch: 45,
      heading_up: true,
      follow_pause: 15,
      layout: "coin",
      hide_attribution: false,
      theme: "sombre",
      ...config,
    };
    this._headingUp = this._config.heading_up;
  }

  set hass(hass) {
    this._hass = hass;
    if (!this.shadowRoot) this._build();
    this._readStates();
  }

  getCardSize() {
    return 6;
  }

  getGridOptions() {
    return { columns: 12, rows: 4, min_rows: 3 };
  }

  connectedCallback() {
    if (this._map && !this._raf) this._raf = requestAnimationFrame((t) => this._tick(t));
  }

  disconnectedCallback() {
    cancelAnimationFrame(this._raf);
    this._raf = null;
  }

  _build() {
    const root = this.attachShadow({ mode: "open" });
    root.innerHTML = `
      <link rel="stylesheet" href="${MAPLIBRE_URL}.css">
      <style>
        :host { display: block; height: 100%; }
        ha-card { display: block; position: relative; height: 100%; min-height: 240px; overflow: hidden; }
        #map { position: absolute; inset: 0; }
        .message { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
                   padding: 16px; text-align: center; color: var(--secondary-text-color); }
        .buttons { position: absolute; top: 10px; left: 10px; display: flex; flex-direction: column; gap: 8px; z-index: 2; }
        .buttons button { width: 40px; height: 40px; border-radius: 50%; border: none; cursor: pointer;
                          background: rgba(30,30,30,0.85); color: white; display: flex; align-items: center;
                          justify-content: center; box-shadow: 0 2px 6px rgba(0,0,0,0.4); }
        .buttons button[hidden] { display: none; }
        .hud { position: absolute; z-index: 2; display: flex; align-items: center; gap: 10px; pointer-events: none; }
        .hud.speed-hud { left: 12px; bottom: 12px; }
        .hud.sign-hud { right: 12px; top: 12px; }
        .groupe .hud.speed-hud { bottom: 14px; left: 14px; }
        .speed { min-width: 74px; height: 74px; padding: 0 12px; box-sizing: border-box; border-radius: 18px;
                 background: rgba(18,18,22,0.82); backdrop-filter: blur(6px); color: #fff;
                 display: flex; flex-direction: column; align-items: center; justify-content: center;
                 font-family: ${FONT}; line-height: 0.9; box-shadow: 0 4px 14px rgba(0,0,0,0.45);
                 border: 1px solid rgba(255,255,255,0.08); transition: color 0.3s; }
        .speed .value { font-size: 40px; font-weight: 700; letter-spacing: -0.5px; }
        .speed .unit { font-size: 13px; font-weight: 600; opacity: 0.6; margin-top: 2px; letter-spacing: 0.5px; }
        .speed.over { color: #ff5252; }
        .sign { width: 78px; height: 78px; border-radius: 50%; filter: drop-shadow(0 4px 10px rgba(0,0,0,0.5)); }
        .sign svg { width: 100%; height: 100%; display: block; }
        .sign.over { animation: alerte 1s ease-in-out infinite; }
        .sign[hidden], .speed[hidden] { display: none; }
        @keyframes alerte {
          0%, 100% { filter: drop-shadow(0 0 0 rgba(255,40,40,0)) drop-shadow(0 4px 10px rgba(0,0,0,0.5)); transform: scale(1); }
          50% { filter: drop-shadow(0 0 14px rgba(255,40,40,0.95)) drop-shadow(0 4px 10px rgba(0,0,0,0.5)); transform: scale(1.06); }
        }
        .no-attribution .maplibregl-ctrl-attrib, .no-attribution .maplibregl-ctrl-bottom-right { display: none !important; }
      </style>
      <ha-card id="card">
        <div id="map"></div>
        <div class="message" id="message">Chargement de la carte…</div>
        <div class="buttons">
          <button id="recenter" title="Recentrer" hidden><ha-icon icon="mdi:crosshairs-gps"></ha-icon></button>
          <button id="orientation" title="Orientation"><ha-icon icon="mdi:navigation-variant"></ha-icon></button>
        </div>
        <div class="hud speed-hud" id="speedHud">
          <div class="speed" id="speed" hidden><div class="value">0</div><div class="unit">km/h</div></div>
        </div>
        <div class="hud sign-hud" id="signHud">
          <div class="sign" id="limit" hidden>${SIGN_SVG}</div>
        </div>
      </ha-card>`;
    this._el = (id) => root.getElementById(id);
    loadFont();
    const card = this._el("card");
    card.classList.toggle("no-attribution", !!this._config.hide_attribution);
    if (this._config.layout === "groupe") {
      // Waze-like cluster: the sign next to the speed, bottom left
      card.classList.add("groupe");
      this._el("speedHud").appendChild(this._el("limit"));
    }
    this._el("recenter").addEventListener("click", () => this._setFollow(true));
    this._el("orientation").addEventListener("click", () => {
      this._headingUp = !this._headingUp;
      this._updateOrientationIcon();
      if (!this._headingUp && this._map) this._map.easeTo({ bearing: 0 });
      this._setFollow(true);
    });
    this._updateOrientationIcon();
    this._follow = true;
    this._trail = [];
    this._heading = 0;
    this._shownHeading = 0;
  }

  _updateOrientationIcon() {
    this._el("orientation").firstElementChild.setAttribute(
      "icon", this._headingUp ? "mdi:navigation-variant" : "mdi:compass-outline");
  }

  _setFollow(follow) {
    this._follow = follow;
    clearTimeout(this._resumeTimer);
    this._el("recenter").hidden = follow;
    if (!follow && this._config.follow_pause > 0) {
      this._resumeTimer = setTimeout(() => this._setFollow(true), this._config.follow_pause * 1000);
    }
  }

  /** Map style: an explicit `style`, else day/night following the sun with `theme: auto`. */
  _styleUrl() {
    if (this._config.style) return this._config.style;
    const theme = this._config.theme;
    if (theme === "clair") return DAY_STYLE;
    if (theme === "auto") {
      const sun = this._hass && this._hass.states["sun.sun"];
      return sun && sun.state === "above_horizon" ? DAY_STYLE : DEFAULT_STYLE;
    }
    return DEFAULT_STYLE;
  }

  _readStates() {
    const state = this._hass.states[this._config.entity];
    if (!state) {
      this._showMessage(`Entité introuvable : ${this._config.entity}`);
      return;
    }
    const { course, speed } = state.attributes;
    let { latitude, longitude } = state.attributes;
    // Geocoded-location sensors carry the position as "location: [lat, lon]"
    if (typeof latitude !== "number" && Array.isArray(state.attributes.location)) {
      [latitude, longitude] = state.attributes.location.map(Number);
    }
    // Some integrations send the coordinates as text
    latitude = typeof latitude === "string" ? parseFloat(latitude) : latitude;
    longitude = typeof longitude === "string" ? parseFloat(longitude) : longitude;
    if (typeof latitude !== "number" || typeof longitude !== "number" || isNaN(latitude) || isNaN(longitude)) {
      // Say exactly what was received, so a wrong entity or a missing attribute is obvious
      const keys = Object.keys(state.attributes).join(", ") || "aucun";
      this._showMessage(`Pas de position dans ${this._config.entity} (état : ${state.state}). Attributs reçus : ${keys}`);
      return;
    }
    const point = [longitude, latitude];
    const fix = this._fix;
    if (!fix || fix.updated !== state.last_updated) {
      this._onFix(point, course, speed, state.last_updated);
    }
    this._updateOverlays();
    if (!this._mapLoading) this._initMap(point);
    else if (this._map && this._style !== this._styleUrl()) {
      this._style = this._styleUrl();
      this._map.setStyle(this._style, { diff: false });  // full reload, so "style.load" re-adds the trail
    }
  }

  _onFix(point, course, gpsSpeed, updated) {
    const previous = this._fix && this._fix.point;
    // Heading: the phone's course when moving, otherwise derived from the last two fixes
    if (typeof course === "number" && course > 0 && (gpsSpeed || 0) > MIN_MOVING_MPS) {
      this._heading = course;
    } else if (previous && distance(previous, point) > 8) {
      this._heading = bearing(previous, point);
    }
    this._fix = { point, time: performance.now(), gpsSpeed: typeof gpsSpeed === "number" ? gpsSpeed : 0, updated };
    if (!previous || distance(previous, point) > 3) {
      this._trail.push(point);
      if (this._trail.length > TRAIL_POINTS) this._trail.shift();
      this._updateTrail();
    }
  }

  /** Speed in m/s: the OBD speed (km/h, refreshed ~2×/s) beats the GPS one (every few seconds). */
  _speedMps() {
    const entity = this._config.speed_entity && this._hass.states[this._config.speed_entity];
    const kmh = entity ? parseFloat(entity.state) : NaN;
    if (!isNaN(kmh)) return kmh / 3.6;
    return this._fix ? this._fix.gpsSpeed : 0;
  }

  _updateOverlays() {
    const kmh = Math.round(this._speedMps() * 3.6);
    const limitEntity = this._config.speed_limit_entity && this._hass.states[this._config.speed_limit_entity];
    const limit = limitEntity ? parseFloat(limitEntity.state) : NaN;
    const over = !isNaN(limit) && kmh > limit + 3;

    const speedBox = this._el("speed");
    speedBox.hidden = false;
    speedBox.firstElementChild.textContent = kmh;
    speedBox.classList.toggle("over", over);

    const sign = this._el("limit");
    sign.hidden = isNaN(limit);
    if (!isNaN(limit)) {
      const text = sign.querySelector("text");
      const digits = String(Math.round(limit));
      if (text.textContent !== digits) {
        text.textContent = digits;
        text.setAttribute("font-size", digits.length > 2 ? "39" : "46");
        text.setAttribute("letter-spacing", digits.length > 2 ? "-2" : "-1");
      }
      sign.classList.toggle("over", over);
    }
  }

  _showMessage(text) {
    const message = this._el("message");
    message.textContent = text;
    message.hidden = !text;
  }

  async _initMap(point) {
    this._mapLoading = true;
    let maplibregl;
    try {
      maplibregl = await loadMapLibre();
    } catch (e) {
      this._showMessage(e.message);
      this._mapLoading = false;
      return;
    }
    const map = new maplibregl.Map({
      container: this._el("map"),
      style: (this._style = this._styleUrl()),
      center: point,
      zoom: this._config.zoom,
      pitch: this._config.pitch,
      bearing: this._headingUp ? this._heading : 0,
      attributionControl: this._config.hide_attribution ? false : { compact: true },
    });
    this._map = map;

    const arrow = document.createElement("div");
    arrow.innerHTML = ARROW_SVG;
    this._marker = new maplibregl.Marker({ element: arrow, rotationAlignment: "map", pitchAlignment: "map" })
      .setLngLat(point)
      .addTo(map);

    // A finger on the map pauses the follow mode (programmatic moves have no originalEvent)
    for (const event of ["dragstart", "zoomstart", "rotatestart", "pitchstart"]) {
      map.on(event, (e) => {
        if (e.originalEvent) this._setFollow(false);
      });
    }
    // Also after a day/night style change, which drops the custom layers
    map.on("style.load", () => {
      if (map.getSource("trail")) return;
      map.addSource("trail", { type: "geojson", data: this._trailGeoJson() });
      map.addLayer({
        id: "trail",
        type: "line",
        source: "trail",
        layout: { "line-cap": "round", "line-join": "round" },
        paint: { "line-color": "#2196f3", "line-width": 5, "line-opacity": 0.6 },
      });
      this._showMessage("");
    });
    map.on("error", (e) => console.warn("voiture-gps-card:", e && e.error));
    new ResizeObserver(() => map.resize()).observe(this._el("map"));

    this._pos = point;
    this._shownHeading = this._heading;
    if (!this._raf) this._raf = requestAnimationFrame((t) => this._tick(t));
  }

  _trailGeoJson() {
    return { type: "Feature", geometry: { type: "LineString", coordinates: this._trail } };
  }

  _updateTrail() {
    const source = this._map && this._map.getSource("trail");
    if (source) source.setData(this._trailGeoJson());
  }

  _tick() {
    this._raf = requestAnimationFrame((t) => this._tick(t));
    if (!this._map || !this._fix) return;

    // Dead reckoning: extend the last fix along the heading at the current speed
    const elapsed = Math.min((performance.now() - this._fix.time) / 1000, MAX_PREDICT_S);
    const speed = this._speedMps();
    const target = speed > MIN_MOVING_MPS
      ? project(this._fix.point, this._heading, speed * elapsed)
      : this._fix.point;

    // Glide towards the target instead of jumping when a new fix corrects the estimate
    const jump = distance(this._pos, target);
    const k = jump > 200 ? 1 : 0.15;
    this._pos = [this._pos[0] + (target[0] - this._pos[0]) * k, this._pos[1] + (target[1] - this._pos[1]) * k];
    this._shownHeading = lerpAngle(this._shownHeading, this._heading, 0.1);

    this._marker.setLngLat(this._pos).setRotation(this._shownHeading);
    if (this._follow) {
      const height = this._el("map").clientHeight;
      this._map.jumpTo({
        center: this._pos,
        bearing: this._headingUp ? this._shownHeading : 0,
        // Heading up: the car sits in the lower third to show the road ahead
        padding: { top: this._headingUp ? height * 0.35 : 0, bottom: 0, left: 0, right: 0 },
      });
    }
  }
}

customElements.define("voiture-gps-card", VoitureGpsCard);
window.customCards = window.customCards || [];
window.customCards.push({
  type: "voiture-gps-card",
  name: "Voiture GPS",
  description: "Carte de navigation : flèche orientée, carte qui tourne avec la route, mouvement fluide.",
});
