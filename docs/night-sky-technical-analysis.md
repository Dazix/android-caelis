# Night Sky AR — Kompletní technická analýza vykreslovacího systému

## 1. Architektura systému

Noční obloha je plně 3D AR planetárium vykreslované v reálném čase při 60 FPS s **nulovou alokací na heap** v render smyčce. Systém se skládá z 5 vrstev:

1. **Senzory & Vstupní Data** — OrientationTracker, SolarLocationProvider, CameraFovCalculator
2. **Doménová Vrstva (Pure Kotlin)** — SiderealTime, EquatorialCoordinates, StarOptics, StarFrustumCulling, StarHitTester
3. **Datová Vrstva** — FlatBuffers Star/Constellation/StarMetadata catalog, NightSkyCatalogLoader
4. **Vykreslovací Vrstva** — NightSkyRenderer (batch star+constellation), SkyDomeRenderer (grid), AGSL GPU shadery
5. **UI Vrstva (Compose)** — NightSkyArViewModel (StateFlow orchestrátor), NightSkyArScreen (DrawScope + Canvas)

---

## 2. Senzorový Vstup & Orientace

### 2.1 OrientationTracker

**Soubor:** `ar/OrientationTracker.kt`

Sleduje 3D orientaci zařízení pomocí hardwarových senzorů s prioritním kaskádem:
1. `TYPE_ROTATION_VECTOR` (fúze gyroskopu + akcelerometru + magnetometru)
2. `TYPE_GEOMAGNETIC_ROTATION_VECTOR` (fallback bez gyroskopu)
3. `TYPE_GAME_ROTATION_VECTOR` (fallback bez magnetometru)

#### Adaptivní Quaternion SLERP

Místo přímého čtení surových quaternionů používá **adaptivní interpolaci**:

| Stav | Alpha | Efekt |
|------|-------|-------|
| Statický (< 0.002 rad Δ) | `0.30` | Filtruje mikro-chvění |
| Pohyb (> 0.025 rad Δ) | `0.85` | Eliminuje lag kamery |

```kotlin
// Detekce úhlové rychlosti a plynulá interpolace alpha:
val deltaAngleRad = 2.0 * acos(absDot)
val t = ((deltaAngleRad - 0.002) / (0.025 - 0.002)).coerceIn(0f, 1f)
val currentAlpha = 0.30f + t * (0.85f - 0.30f)
```

Po interpolaci se quaternion **renormalizuje** a převede zpět na garantovaně ortognormální 4×4 rotační matici přes `SensorManager.getRotationMatrixFromVector()`.

#### Kalibrace (True North + Uživatelská rotace)

Výstupní matice prochází dvěma korekcemi:

1. **Magnetická deklinace** — otočení ENU rámu kolem osy Z (nahoru):
   `R_true = R_z(declination) · R_sensor`

2. **Uživatelská quaternion rotace** — umožňuje ruční korekci (drag gesture):
   `R_adjusted = R_true · R_user`

Uživatelský quaternion podporuje plných 360° kolem všech tří os bez gimbal-locku díky post-multiplikaci v rámci kamery.

### 2.2 CameraFovCalculator

**Soubor:** `ar/CameraFovCalculator.kt`

Vypočítá skutečné zorné pole kamery ze tří fyzických parametrů:

```
FOV_H = 2 * arctan(d_sensor / (2 * f))
```

kde `d_sensor` je šířka snímače v mm a `f` je ohnisková vzdálenost objektivu.

#### Korekce FILL_CENTER cropu

CameraX `FILL_CENTER` ořezává senzorový snímek aby vyplnil vysoký displej (20:9). To zúží horizontální FOV:

```
cropRatio = viewportAspect / sensorAspect
effectiveFOV_H = 2 * arctan(tan(rawFOV_H / 2) * cropRatio)
```

Fallback je `56°` pro portrét (typický smartphone 4.2mm objektiv, 1/1.7" senzor).

---

## 3. Astronomická Časová Osa

### 3.1 SiderealTime (Hvězdný čas)

**Soubor:** `domain/nightsky/SiderealTime.kt`

Implementuje IAU/Meeus formuli pro Greenwich Mean Sidereal Time:

```
GMST(°) = 280.46061837 + 360.98564736629·d + 0.000387933·T² − T³/38710000
```

kde `d = JD − 2451545.0` (J2000.0 epocha) a `T = d / 36525` (Juliánská století).

Local Sidereal Time se získá přičtením zeměpisné délky: `LST = GMST + λ`

LST se aktualizuje každou sekundu v ViewModel tickeru a říká, která rektascenze je právě na místním meridiánu.

---

## 4. Hvězdný Katalog (FlatBuffers Binary)

### 4.1 Schéma

**Soubory:** `data/nightsky/Star.java`, `data/nightsky/Constellation.java`, `data/nightsky/NightSkyCatalog.java`

```
NightSkyCatalog (root table)
├── stars: [Star]              ← contiguous struct vector
│   └── Star (inline struct, 12 bytes)
│       ├── ra:  float32       (Right Ascension, degrees [0, 360))
│       ├── dec: float32       (Declination, degrees [-90, +90])
│       └── mag: float32       (Apparent V-band magnitude)
│
├── metadata: [StarMetadata]   ← sparse named star entries
│   └── StarMetadata (table)
│       ├── starIndex: int
│       ├── name: string       ("Sirius", "Polaris", ...)
│       ├── bodyType: string   ("Binary Star", "Red Supergiant", ...)
│       ├── constellation: string
│       └── distanceLy: float
│
└── constellations: [Constellation]  ← IAU 88 + asterisms
    └── Constellation (table)
        ├── name: string       ("Ursa Major")
        ├── localizedName: string ("Velká medvědice")
        ├── centroidRa: float
        ├── centroidDec: float
        ├── lines: [int]       (star index pairs: [i0, i1, i2, i3, ...])
        ├── brightestStar: string
        ├── family: string
        ├── isAsterism: bool
        ├── artResourceName: string  ("art_uma")
        ├── artCenterRa: float
        ├── artCenterDec: float
        ├── artWidthDeg: float
        └── artRotationDeg: float
```

### 4.2 Zero-Copy Přístup

`Star` je **inline struct** (ne table) — 12 bajtů uložených kontiguálně v paměti. Renderer čte přímo z `ByteBuffer` bez vytváření objektů:

```kotlin
val offset = starsVectorOffset + i * 12
val ra  = bb.getFloat(offset)       // 0 alokací
val dec = bb.getFloat(offset + 4)   // 0 alokací
val mag = bb.getFloat(offset + 8)   // 0 alokací
```

---

## 5. Souřadnicové Transformace

### 5.1 EquatorialCoordinates — RA/Dec → ENU World Vector

**Soubor:** `domain/nightsky/EquatorialCoordinates.kt`

Převádí ekvatoriální souřadnice hvězdy na 3D jednotkový vektor v Earth ENU (East, North, Up) přes hodinový úhel `H = LST - RA`:

```
vx(East)  = -cos(δ)·sin(H)
vy(North) = cos(φ)·sin(δ) − sin(φ)·cos(δ)·cos(H)
vz(Up)    = sin(φ)·sin(δ) + cos(φ)·cos(δ)·cos(H)
```

kde `δ` = deklinace, `φ` = zeměpisná šířka pozorovatele. Výsledek se zapisuje in-place do předalokovaného `FloatArray`.

### 5.2 World → Device → Screen (Gnomická Projekce)

**Soubor:** `ar/CameraProjection.kt`

Třístupňový transformační řetězec:

#### Krok 1: World → Device (V_dev = Rᵀ · V_world)

Android `SensorManager` matice R mapuje z device do world, takže inverzní transformace (world→device) je transpozice:

```kotlin
xDev = R[0]*vx + R[4]*vy + R[8]*vz
yDev = R[1]*vx + R[5]*vy + R[9]*vz
zDev = R[2]*vx + R[6]*vy + R[10]*vz
```

#### Krok 2: Device → Camera Screen (Display Rotation)

Mapuje fyzické osy zařízení na osy obrazovky podle orientace displeje:

| Rotace | xs | ys |
|--------|----|----|
| 0° (Portrét) | xDev | yDev |
| 90° (Landscape vlevo) | yDev | −xDev |
| 180° (Obrácený portrét) | −xDev | −yDev |
| 270° (Landscape vpravo) | −yDev | xDev |

Optická osa kamery: `zs = -zDev` (kamera míří v opačném směru osy Z).

#### Krok 3: Gnomická Perspektivní Projekce

```
X_screen = W/2 + f_px · (xs / zs)
Y_screen = H/2 − f_px · (ys / zs)
```

kde ohnisková vzdálenost v pixelech:

```
f_px = (W/2) / tan(FOV_H / 2)
```

> **Invariant:** Y-osa je invertovaná (znaménko mínus), protože Android Canvas má +Y dolů, zatímco astronomická konvence má +Y nahoru.

### 5.3 Optická osa kamery

Směr zadní kamery ve World ENU:

```
v_cam = [-R[2], -R[6], -R[10]]ᵀ
```

Používá se pro dot-product frustum culling.

---

## 6. GPU Vykreslovací Vrstvy (AGSL Hardware Shadery)

Tři nezávislé GPU shadery vykreslují pozadí oblohy metodou **per-pixel 3D ray-castingu**. Každý pixel obrazovky se stává 3D paprskem, který se otočí do světového souřadnicového systému a vzorkuje sférický obraz.

### 6.1 MilkyWayShader (Mléčná dráha)

**Soubor:** `ui/nightsky/MilkyWayShader.kt`

| Uniform | Typ | Popis |
|---------|-----|-------|
| `milkyWayTexture` | `shader` | Ekvírektangulární WebP textura (2048×1024) |
| `resolution` | `float2` | Rozlišení viewportu (px) |
| `textureSize` | `float2` | Rozměry textury (px) |
| `viewMatrix` | `mat3` | `M_enu2cel · R_sensor · M_disp` |
| `fovFactor` | `float` | `1 / tan(FOV_H / 2)` |
| `isRedVision` | `float` | 1.0 = červený noční režim |

**Algoritmus:**
1. Normalizace fragCoord → NDC `[-1, 1]` s aspect ratio korekcí
2. Konstrukce 3D ray vektoru: `normalize(uv.x, -uv.y, -fovFactor)`
3. Rotace paprsku do ekvatoriálních souřadnic: `worldDir = viewMatrix * rayDir`
4. Kartézský → sférický: `θ = atan2(z, x)`, `φ = arcsin(y)`
5. Sférický → UV textura: `u = θ/2π + 0.5`, `v = 0.5 − φ/π`
6. Vzorkování textury + volitelný Red Vision filter (luminance → red)

**ViewMatrix** pro Mléčnou dráhu se počítá v `CelestialMatrixCalculator`:

```
M_view = M_enu2cel · R_sensor · M_disp
```

kde `M_enu2cel` převádí z ENU (East, North, Up) do J2000.0 ekvatoriálního rámu pomocí LST a zeměpisné šířky:

```
// M_enu2cel (3x3 row-major):
// Row 0: [-sinLst,  -sinLat·cosLst,  cosLat·cosLst]
// Row 1: [0,         cosLat,          sinLat        ]
// Row 2: [cosLst,   -sinLat·sinLst,  cosLat·sinLst ]
```

### 6.2 AtmosphereShader (Atmosférická záře)

**Soubor:** `ui/nightsky/AtmosphereShader.kt`

Simuluje atmosférický rozptyl světla u horizontu. Paprsek se otočí jen do ENU (bez LST rotace), extrahuje se elevace ze Z komponenty:

```
factor = clamp(1 − sinElevation / sin(15°))³
```

Kubická mocnina vytváří realistický exponenciální útlum. Pixely pod horizontem (z < 0) se zahazují. Barva: přechod od průhledné (zenit) k hluboce modré (horizont).

### 6.3 LandscapeShader (Krajina)

**Soubor:** `ui/nightsky/LandscapeShader.kt`

Identický ray-casting jako MilkyWayShader, ale fixovaný na ENU zemský rám (bez LST rotace). Vzorkuje fotorealistickou 360° panoramatickou texturu krajiny. Vykresluje se přes hvězdy pod horizontem.

---

## 7. Hlavní Renderer (NightSkyRenderer)

**Soubor:** `ui/nightsky/NightSkyRenderer.kt` (1338 řádků)

### 7.1 Předalokované Buffery

| Buffer | Velikost | Účel |
|--------|----------|------|
| `bucketPoints[6]` | 6 × 20480 floatů | Screen XY pro každý brightness bucket |
| `bucketCounts[6]` | 6 intů | Počet viditelných hvězd v bucketu |
| `starScreenX/Y` | 10240 floatů | Projektované souřadnice pro constellation resolution |
| `starInFront` | 10240 boolů | Zda je hvězda před kamerou |
| `forceRenderStar` | 10240 boolů | O(1) lookup tabulka pro force-render hvězd souhvězdí |
| `constellationProgress` | 256 floatů | Animační progress [0.0, 1.0] |
| `constellationLinesBuffer` | 16384 floatů | Segmenty čar pro batch drawLines |
| `tempWorldVec` | 3 floaty | Opakovaně použitelný world vektor |

### 7.2 Vykreslovací Pipeline (drawNightSky)

Pipeline probíhá v 8 krocích, všechny s **nulovou alokací na heap**:

#### Krok 1: Výpočet kamery

```kotlin
val camFwdX = -matrix[2]   // Optická osa kamery v ENU
val camFwdY = -matrix[6]
val camFwdZ = -matrix[10]
val focalLengthPixels = (w * 0.5f) / tan(halfFovHRad)
```

#### Krok 2: Frustum Dot Threshold

`StarFrustumCulling` počítá kosinus maximálního diagonálního poloúhlu frustum + 12° bezpečnostní margin:

```
θ_diag = arctan(√(tan²(FOV_H/2) + tan²(FOV_V/2))) + 12°
threshold = cos(θ_diag)
```

Test: `v_cam · v_star ≥ threshold`

#### Krok 3: Constellation Centroid Focus & Animation

Pro každé souhvězdí (max 256) se projektuje centroid. Progress se animuje smooth delta-time:
- **In focus** (uvnitř 48% šířky obrazovky): progress += `dt × 2.0` (0→1 za 0.5s)
- **Out of focus**: progress -= `dt × 2.0`

#### Krok 4: Force Render Override

O(1) lookup tabulka `forceRenderStar[]`: pro každé souhvězdí s progress > 0 se všechny jeho hvězdy nastaví na `true`. Tím se vynutí vykreslení i slabých hvězd, které by jinak neprošly magnitudovým limitem.

#### Krok 5: Batch Star Processing (Hot Loop)

Pro každou hvězdu (max 10240):

1. **Magnitude gate**: `rawMag <= faintestVisibleMag || isForced`
2. **RA/Dec → ENU World Vector** (`EquatorialCoordinates.computeWorldVector`)
3. **World → Device → Screen** (inline Gnomická projekce)
4. **Frustum culling** (dot-product)
5. **Atmospheric extinction** (hvězdy 0°–10° nad horizontem se ztlumí):
   `m_eff = m₀ + (1 − h/10°) × 1.5 mag`
6. **Bucket classification** (`StarOptics.computeBucketIndex`):

| Bucket | Rozsah mag | Příklady |
|--------|-----------|----------|
| 0 | < 1.0 | Sirius, Vega, Rigel, Betelgeuse |
| 1 | 1.0–2.0 | Polaris, hvězdy Orionu |
| 2 | 2.0–3.0 | Střední hvězdy |
| 3 | 3.0–4.0 | Slabší hvězdy |
| 4 | 4.0–5.0 | Těžko viditelné okem |
| 5 | ≥ 5.0 | Teleskopické pozadí |

7. **Zápis do bucket bufferu** (`bucketPoints[b][count*2]`, `bucketPoints[b][count*2+1]`)

#### Krok 5b: Dynamický Magnitude Limit (Zoom)

`StarOptics.computeFaintestVisibleMagnitude` simuluje fyzické zvětšení dalekohledu:

```
M_max = 5.8 + 2.5 · log₁₀(z)
```

| Zoom | Limit mag | Vizuální efekt |
|------|-----------|----------------|
| 1.0x | 5.8 | Běžné oko |
| 2.0x | 6.6 | Malý dalekohled |
| 4.0x | 7.3 | Střední dalekohled |
| 10.0x | 8.3 | Velký dalekohled |

---

## 8. Vykreslení Souhvězdí

### 8.1 "Meet-in-the-Middle" Čárová Animace

Během animace (progress < 1.0) se každý segment souhvězdí vykresluje jako **dvě čáry rostoucí ze svých hvězdných koncových bodů směrem ke středu**:

```
Star A ------>      <------ Star B    (progress = 0.5)
Star A -----------><----------- Star B (progress = 0.9)
Star A ======================== Star B (progress = 1.0, jednolitý segment)
```

Při progress ≥ 0.99 se přepne na **jeden kontinuální segment** — to eliminuje artefakt překrývajícího se bodu uprostřed.

Každá čára má gap `7dp × density` od hvězdného bodu, aby se vizuálně oddělila od hvězdného jádra.

### 8.2 Třívrstvé Vykreslení Čar

Čáry souhvězdí mají tři vizuální vrstvy (pro vybraná souhvězdí):

1. **Halo** (měkký vnější obrys): `strokeWidth 5.4dp`, alpha 42
2. **Core** (jádro čáry): `strokeWidth 2.16dp`, alpha 240
3. **Standard** (nevybraná): `strokeWidth 1.45dp`

### 8.3 Asterismy vs. IAU Souhvězdí

| Vlastnost | IAU Souhvězdí | Asterismus |
|-----------|---------------|------------|
| Label font | Bold | Italic |
| Čára šířka | 1.45dp | 1.15dp |
| Label velikost | 13dp | 10.5dp |
| Text Hit area | ANO | ANO |
| `isAsterism` | `false` | `true` |

---

## 9. Grafika Souhvězdí (Constellation Artwork)

### 9.1 2-Bodová Projekce s Gimbal Lock Ochranou

Každá grafika souhvězdí se pozicuje, škáluje a rotuje na základě dvou 3D→2D projekcí:

#### Bod 1: Střed grafiky
`(artCenterRa, artCenterDec)` → `(cScreenX, cScreenY, czs)`

#### Bod 2: Lokální Sever (meridiánový referenční bod)
`(artCenterRa, artCenterDec + 1°)` → `(nScreenX, nScreenY)`

**Gimbal Lock Ochrana:** Pokud `artCenterDec > 88°`, přidání 1° by přeteklo přes pól. V tom případě se offsetuje -1° (jižním směrem) a výsledný delta vektor se invertuje (`northSign = -1`).

#### Rotace na obrazovce

Úhel "Celestial North" na obrazovce:

```
gridAngle = atan2(ΔY, ΔX) · northSign
finalRotation = gridAngle + 90° − artRotDeg + debugOffset
```

Přičtení 90° zajistí, že obrázek nakreslený „hlavou nahoru" bude zarovnán se severním vektorem.

### 9.2 Perspektivní Škálování (Z-Divide)

Šířka grafiky v pixelech musí respektovat Gnomickou perspektivní deformaci:

```
perspectiveFocalLength = f_px / zs
widthPx = θ_rad × perspectiveFocalLength
scale = widthPx / bitmapWidth
```

Dělení `f_px / zs` zajišťuje, že se bitmap roztahuje spolu s hvězdným polem při pohybu ke krajům obrazovky (kde `zs < 1`). U optické osy (`zs ≈ 1`) je dělení no-op.

> **Bez tohoto dělení grafika "driftuje" od podkladových hvězd při panorámování — klasický parallax bug.**

### 9.3 Maticová Transformace (Zero-Alloc)

Předalokovaná `artMatrix` se sestavuje v 5 krocích:

```kotlin
artMatrix.reset()
artMatrix.postTranslate(-bitmapW/2, -bitmapH/2)  // A. Pivot na střed bitmapy
artMatrix.postScale(finalScale, finalScale)        // B. Škálování na nebeský FOV
artMatrix.postRotate(finalRotation)                // C. Rotace k celestiálnímu meridiánu
artMatrix.postTranslate(cScreenX + debugX,         // D. Pozice na obrazovce
                        cScreenY + debugY)
```

Průhlednost: max 25% (alpha 64/255) při plném progress, lineárně interpolováno.

### 9.4 LRU Cache

Bitmapové grafiky se cachují v `LruCache<String, Bitmap>(16)` — max 16 současně načtených souhvězdí.

---

## 10. Hybridní Optické Vykreslení Hvězd

### 10.1 Pass 1: Radiální Gradient Sprite (Buckety 0–2, mag < 3.0)

Pro jasné hvězdy se vykresluje **předgenerovaný 64×64 px radiální gradient sprite** s 5-bodovým gradientem:

| Pozice | Alpha | Popis |
|--------|-------|-------|
| 0% | 100% | Čistě bílé jádro |
| 15% | 85% | Jasné vnitřní jádro |
| 38% | 38% | Měkká optická záře |
| 70% | 8% | Slabá vnější korona |
| 100% | 0% | Průhledný okraj |

Sprite se škáluje podle bucketu:

| Bucket | Průměr (dp) | Příklad |
|--------|-------------|---------|
| 0 (mag < 1) | 30 dp | Sirius: velká halo záře |
| 1 (mag 1–2) | 20 dp | Polaris: střední záře |
| 2 (mag 2–3) | 13 dp | Slabá záře |

### 10.2 Pass 2: Pinpoint Cores (Buckety 0–5, všechny hvězdy)

Všechny viditelné hvězdy se vykreslují přes `Canvas.drawPoints()` v batch módu — jeden volání drawPoints pro každý bucket. Stroke šířka podle bucketu:

| Bucket | Stroke (dp) |
|--------|-------------|
| 0 | 3.2 |
| 1 | 2.5 |
| 2 | 2.0 |
| 3 | 1.6 |
| 4 | 1.2 |
| 5 | 1.0 |

### 10.3 Optický Zoom Point-Spread

`StarOptics.computeOpticScale` škáluje stroke widths pomocí odmocniny ze zoomu:

```
opticScale = √z
```

Odmocnina napodobuje fyzické difrakční vzory bez balónového efektu při vysokém zvětšení.

---

## 11. Sky Dome Grid (Koordinátní Mřížka)

**Soubor:** `ar/SkyDomeRenderer.kt`

Vykresluje 3D astronomickou mřížku pomocí `CameraProjection.projectFast`:

| Prvek | Parametry | Projektované body |
|-------|-----------|-------------------|
| **Horizontální kruh** (0° elevace) | 180 vertexů po 2° | 180 × 3 floatů |
| **Výškové paralely** | −30°, −15°, +15°, +30°, +45°, +60°, +75° | 72 vertexů po 5° × 7 |
| **Azimutální meridiány** | Po 30° (0°–330°) | 35 vertexů od −85° do +85° × 12 |
| **Kardinální body** | N, NE, E, SE, S, SW, W, NW | 8 bodů + textové labely |
| **Zenit** (+90°) | Křížek + kružnice | 1 bod |
| **Nadir** (−90°) | Křížek + kružnice | 1 bod |

Všechny body jsou projektovány do předalokovaných bufferů (`horizonBuffer`, `altitudeGridBuffer`, `meridianBuffer`).

---

## 12. Hit Testing (Výběr Celestiálních Objektů)

**Soubor:** `domain/nightsky/StarHitTester.kt`

Dvoustupňový prioritizovaný systém:

### Priorita 1: Souhvězdí (Text Label Hit)

Eliptický hit test kolem animovaného text labelu centroidu:

```
(x − cx)²/rx² + (y − cy)²/ry² ≤ 1.0
```

s dynamickým Y-slide: `cy = centroid_y − 40dp × (1 − progress)`

| Parametr | Hodnota |
|----------|---------|
| `halfWidthPx` | 75 dp × density |
| `halfHeightPx` | 30 dp × density |
| `minProgress` | 0.3 (30% revealed) |

### Priorita 2: Hvězdy (Nearest Visible Star)

O(N) lineární sweep přes všechny viditelné hvězdy, hledá nejbližší v rámci touch radius:

```
d² = (x − sx)² + (y − sy)² < r_max²
```

Vrací index nejbližší hvězdy, nebo -1 při miss.

---

## 13. Off-Screen Indikátor

**Soubor:** `NightSkyRenderer.computeOffscreenIndicator`

Když je vybraný objekt mimo viewport (nebo za pozorovatelem), zobrazí se šipka na okraji obrazovky:

1. Výpočet směrového vektoru k objektu (screen delta pro visible, raw xs/ys pro behind)
2. Průsečík paprsku s obdélníkovým rámcem viewportu (minus edge padding 22px)
3. Vykreslení chevron šipky otočené o vypočítaný úhel

---

## 14. ViewModel & Reaktivní Stav

**Soubor:** `ui/nightsky/NightSkyArViewModel.kt`

Orchestruje 26 StateFlows kombinovaných do jednoho `NightSkyArUiState`:

### Vstupní StateFlows
- `location`, `hasGpsFix`, `orientation` — senzorová data
- `cameraFovHorizontal` = `combine(baseFov, zoom)` → `CameraFovCalculator.computeZoomedFov`
- `zoomRatio` — pinch-to-zoom (0.4x–10.0x)
- `lstDegrees` — ticker 1 Hz
- `catalog` — async loaded FlatBuffers binary

### Přepínače Vrstev (DataStore persisted)
- `isSkyBackgroundOpaque`, `isMilkyWayVisible`, `isAtmosphereVisible`
- `isLandscapeVisible`, `isGridVisible`
- `isConstellationsVisible`, `isConstellationArtVisible`

### Debug Kalibrace
- `debugArtOffsetX/Y`, `debugArtScaleMultiplier`, `debugArtRotationOffset`

### Klíčové Procesy:
- **Catalog loading**: async I/O z `R.raw.catalog` → `NightSkyCatalogLoader.loadFromStream`
- **LST ticker**: každou sekundu aktualizuje hvězdný čas
- **Day/Night**: `SolarPositionCalculator.isNight()` → automatické přepínání Red Vision
- **Quaternion persistence**: debounce 300ms → `SettingsStore.saveNightSkyQuaternion`

---

## 15. Red Vision (Astrofotografický Noční Režim)

Celý systém podporuje dva barevné režimy:

| Prvek | Normal | Red Vision |
|-------|--------|------------|
| Pozadí oblohy | `#050810` | `#080000` |
| Hvězdná záře | Bílá | Červená (`#FF5252`) |
| Souhvězdí čáry | Cyan `#4DD0E1` | Červená `#FF1744` |
| Mléčná dráha | Plný color | Luminance → Red channel |
| Atmosféra | Modrý gradient | `luminance × 0.4` → Red |
| Krajina | Plný color | `luminance × 0.4` → Red |
| Grafika souhvězdí | Normální | `PorterDuff.MULTIPLY(RED)` |
| Reticle/Šipky | Bílá/Zlatá | Červená |

---

## 16. Pořadí Vykreslování (Z-Order)

```
 1. Opaque Sky Background       (#050810 / #080000)
 2. Milky Way Shader             (AGSL GPU ray-cast)
 3. Atmosphere Shader            (AGSL GPU ray-cast)
 4. Constellation Artwork        (bitmap overlay, α ≤ 25%)
 5. Constellation Lines          (Canvas.drawLines batch)
 6. Constellation Labels         (Canvas.drawText)
 7. Star Sprite Halos            (Canvas.drawBitmap × 3 buckets)
 8. Star Pinpoint Cores          (Canvas.drawPoints × 6 buckets)
 9. Sky Dome Grid                (Compose drawLine + nativeCanvas)
10. Landscape Shader             (AGSL GPU ray-cast, over horizon)
11. Selection Reticle/Arrow      (Canvas.drawLines + drawPath)
12. UI Overlays                  (Compose)
```

---

## 17. Výkon & Garance

| Metrika | Hodnota |
|---------|---------|
| Cílový framerate | 60 FPS |
| Heap alokace per frame | **0** (v render smyčce) |
| Max hvězd | 10 240 |
| Max souhvězdí | 256 |
| Max constellation segmentů | 2 048 |
| Star struct velikost | 12 B (kontiguální) |
| Sprite cache | 1 × 64×64 ARGB_8888 |
| Artwork LRU cache | 16 bitmap slotů |
| Sensor polling rate | `SENSOR_DELAY_GAME` (~20ms) |
| LST refresh | 1 Hz |
| Quaternion debounce save | 300 ms |
