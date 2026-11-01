# gpxto3d: Methode der Kachelaufbereitung

gpxto3d macht aus einer GPX-Strecke ein druckfertiges Landschaftsmodell. Gebäude, Wasser, Straßen und Wald kommen aus
zwei eigenen Vektorkachelsätzen. Dieses Repository beschreibt nach ODbL 4.6, wie sie aus OpenStreetMap und Overture
Maps entstehen: Es enthält die Profile, das Extraktions-SQL und das Build-Skript, mit denen die Kacheln gebaut werden.

## Quellen

| Quelle | Lizenz | Verwendung |
|---|---|---|
| [Overture Maps Foundation](https://overturemaps.org), Thema `buildings` (Typen `building`, `building_part`), Release laut Kachelversion | ODbL; enthält OpenStreetMap und weitere Quellen, siehe [Attribution](https://docs.overturemaps.org/attribution) | Kachelsatz `buildings` |
| © [OpenStreetMap-Mitwirkende](https://www.openstreetmap.org/copyright), Auszug von [Geofabrik](https://download.geofabrik.de), Datum laut Kachelversion | ODbL | Kachelsatz `osm` |
| Wasserpolygone von [osmdata.openstreetmap.de](https://osmdata.openstreetmap.de/data/water-polygons.html) (aus OpenStreetMap) | ODbL | Ozean bzw. Nord- und Ostsee im Kachelsatz `osm` |

Die Kachelversion ist die Overture-Release-ID (etwa `2026-10-21.0`) bzw. das Datum des OSM-Auszugs (etwa
`2026-10-20`). Ein Neubau derselben Daten bekommt eine Endung (`2026-10-20-2`).

## Ergebnis

**`buildings.pmtiles`**, nur Zoomstufe 14:

| Ebene | Felder |
|---|---|
| `building` | `id` (GERS-ID), `height`, `num_floors`, `min_height`, `min_floor`, `roof_shape`, `roof_height`, `class`, `subtype`, `has_parts` |
| `building_part` | `height`, `num_floors`, `min_height`, `min_floor`, `roof_shape`, `roof_height`, `building_id` |

- Unterirdische Gebäude (`is_underground`) fehlen, ebenso Namen, Adressen, Quellenlisten und MVT-Feature-IDs.
- Die Geometrie bleibt unverändert bis auf das Einrasten in das Kachelraster (etwa 0,4 m). Flächen, die schmaler sind
  als eine Rastereinheit, fallen dabei weg, wie in den offiziellen Overture-Kacheln.

**`osm.pmtiles`**, Zoomstufen 8 bis 14, Feldnamen wie im [OpenMapTiles-Schema](https://openmaptiles.org/schema/):

| Ebene | Zoom | Inhalt und Felder |
|---|---|---|
| `water` | 8–13 | Wasserflächen nach OpenMapTiles (`class`: lake, river, pond, dock, swimming_pool, ocean; `intermittent=1` nur falls gesetzt), ohne Tunnel und ohne `natural=bay`; Ozean aus den Wasserpolygonen |
| `landcover` | 8–13 | nur Wald: `class=wood` aus `landuse=forest` und `natural=wood`, `leaf_type` (broadleaved, needleleaved, mixed, leafless) falls gesetzt |
| `waterway` | 14 | `class` (river, stream, canal, drain, ditch), `name`, `brunnel`, `intermittent`, `id` (OSM-Weg), `width` (Meter, aus dem OSM-Tag `width`) |
| `waterway` | 8–13 | generalisiert wie im OpenMapTiles-Profil von Planetiler (lange benannte Flüsse in niedrigen Stufen, Bäche ab z13); `class`, `name`, `brunnel`, `intermittent` |
| `transportation` | 8–14 | Straßen und Wege aus dem OpenMapTiles-Profil von Planetiler, gekürzt auf `class`, `subclass`, `brunnel`; in z8–z12 nur motorway, trunk, primary, secondary und tertiary |
| `trees` | 14 | Einzelbäume (`natural=tree`, Punkt, `class=tree`) und Baumreihen (`natural=tree_row`, Linie, `class=tree_row`) mit `leaf_type` |

**Glättung von `water` und `landcover`** für den Druck, je Zoomstufe in Kachelpixeln (256 px je Kachel; in z13 ist
1 px bei 48° N etwa 12,8 m, in z8 das 32-Fache):

| Schritt | Wasser | Wald |
|---|---|---|
| 1. Je OSM-Fläche vor dem Kacheln: Visvalingam-Whyatt-Vereinfachung | 0,3 px | 0,3 px |
| 2. Mindestgröße je OSM-Fläche (Kantenlänge) | 0,5 px | 0,5 px |
| 3. Je Kachel: Flächen gleicher Felder, die näher als 1 px liegen, vereinigen | 1 px | 1 px |
| 4. Schließen (Puffer +r, dann −r): füllt Buchten und Lücken schmaler als 2 r | 0,5 px | 0,5 px |
| 5. Öffnen (Puffer −r, dann +r): entfernt Zipfel und Streifen schmaler als 2 r | 0,2 px | 0,25 px |
| 6. Chaikin-Glättung, Abweichung höchstens | 0,5 px | 0,5 px |
| 7. Mindestfläche und kleinste Insel nach dem Vereinigen | 2 px² | 4 px² |

Die Schritte 4–6 wirken höchstens gut 1 px weit; der Kachelpuffer ist 4 px breit. Benachbarte Kacheln passen daher
nahtlos aufeinander. Alle Werte sind Argumente des Profils (`--water_close_px` usw.).

## Bauen

```sh
node build.mts --region dach --work <arbeitsordner>
```

Voraussetzungen: Node.js ab 22.18 (führt TypeScript direkt aus), Windows oder Linux auf x64, Internet, für D-A-CH
etwa 30 GB freier Platz und 16 GB RAM. Das Skript lädt JDK (Temurin 21), Planetiler 0.10.2 und DuckDB 1.5.6 in den
Arbeitsordner und prüft sie gegen die SHA-256 in [`tools.json`](tools.json); systemweit wird nichts installiert.

Ablauf:

1. Overture `building` und `building_part` der Region per DuckDB aus dem öffentlichen S3-Bucket extrahieren
   ([`sql/extract-buildings.sql`](sql/extract-buildings.sql)); den Geofabrik-Auszug laden und gegen seine MD5 prüfen;
   die Wasserpolygone laden.
2. Die Profile übersetzen und mit Planetiler bauen: [`profiles/GpxBuildings.java`](profiles/GpxBuildings.java) ergibt
   `staging/buildings.pmtiles`, [`profiles/GpxOsm.java`](profiles/GpxOsm.java) ergibt `staging/osm.pmtiles`.
3. Versionen und Quellen stehen in `staging/build.json`.

Regionen stehen in [`regions/`](regions): Bbox, Geofabrik-Auszug und ob der Ozean dazugehört. Weitere Optionen:

| Option | Bedeutung |
|---|---|
| `--only buildings` bzw. `--only osm` | nur einen Kachelsatz bauen |
| `--overture-release <id>` | bestimmter Overture-Release statt des neuesten laut STAC-Katalog |
| `--building-parquet`, `--building-part-parquet` | vorhandene Extraktion verwenden (mit `--overture-release`) |
| `--osm-pbf <datei>` | vorhandenen OSM-Auszug verwenden |
| `--ocean-shapefile <zip oder shp>` | vorhandene Wasserpolygone verwenden (EPSG:3857) |
| `--tools <ordner>` | Werkzeugordner (Standard: `<arbeitsordner>/tools`) |
| `--threads <n>`, `--heap <größe>` | Threads und Java-Heap für Planetiler (Standard: alle Kerne, `8g`) |
