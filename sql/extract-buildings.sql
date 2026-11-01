-- gpxto3d: Overture-Gebaeude einer Region als lokales GeoParquet (Eingabe von profiles/GpxBuildings.java).
-- Ausgaben landen relativ zum Arbeitsverzeichnis: building.parquet und building_part.parquet.
-- build.mts setzt vorher Release und Bbox; von Hand etwa so (Werte der Region D-A-CH):
--   SET VARIABLE release = '2026-10-21.0';
--   SET VARIABLE xmin = 5.8; SET VARIABLE ymin = 45.8; SET VARIABLE xmax = 17.2; SET VARIABLE ymax = 55.1;
--   .read extract-buildings.sql
-- Braucht die Extensions httpfs und spatial (INSTALL httpfs; INSTALL spatial;).

LOAD httpfs;
LOAD spatial;
SET s3_region = 'us-west-2';          -- Overture-Bucket, anonymer Zugriff
SET preserve_insertion_order = false; -- weniger Speicher beim parallelen Schreiben
SET VARIABLE stac_root = 'https://stac.overturemaps.org/' || getvariable('release') || '/buildings/';

-- 1. Dateiauswahl ueber den STAC-Katalog: nur Dateien, deren bbox die Ziel-Bbox schneidet.
--    Die Collection fuehrt die Datei-bboxen in derselben Reihenfolge wie die Item-Links.
CREATE TEMP MACRO stac_select(typ) AS TABLE
  WITH c AS (
    SELECT CASE WHEN len(extent.spatial.bbox) > 1 THEN extent.spatial.bbox[2:] ELSE extent.spatial.bbox END AS boxes,
           list_filter(links, lambda l: l.rel = 'item') AS items
    FROM read_json(getvariable('stac_root') || typ || '/collection.json')
  )
  SELECT items[i].href AS item_url
  FROM c, range(1, len(boxes) + 1) r(i)
  WHERE boxes[i][1] <= getvariable('xmax') AND boxes[i][3] >= getvariable('xmin')
    AND boxes[i][2] <= getvariable('ymax') AND boxes[i][4] >= getvariable('ymin');

SET VARIABLE bld_items  = (SELECT list(item_url) FROM stac_select('building'));
SET VARIABLE part_items = (SELECT list(item_url) FROM stac_select('building_part'));
SET VARIABLE bld_files  = (SELECT list(assets.aws.alternate.s3.href ORDER BY id) FROM read_json(getvariable('bld_items')));
SET VARIABLE part_files = (SELECT list(assets.aws.alternate.s3.href ORDER BY id) FROM read_json(getvariable('part_items')));
SELECT len(getvariable('bld_files')) AS building_dateien, len(getvariable('part_files')) AS part_dateien;

-- 2. building: nur benoetigte Spalten, Filter ueber die bbox-Spalte (Zeilengruppen-Pruning), ohne Unterirdisches.
COPY (
  SELECT id, height, num_floors, min_height, min_floor, roof_shape, roof_height,
         class, subtype, has_parts, geometry
  FROM read_parquet(getvariable('bld_files'))
  WHERE bbox.xmin <= getvariable('xmax') AND bbox.xmax >= getvariable('xmin')
    AND bbox.ymin <= getvariable('ymax') AND bbox.ymax >= getvariable('ymin')
    AND is_underground IS NOT TRUE
) TO 'building.parquet' (FORMAT parquet, COMPRESSION zstd);

-- 3. building_part: Hoehen- und Dachattribute plus building_id. Die Teil-id dient nur Planetiler als
--    interne Feature-ID und landet nicht in den Kacheln (Lauf mit --exclude_ids=true).
COPY (
  SELECT id, building_id, height, num_floors, min_height, min_floor, roof_shape, roof_height, geometry
  FROM read_parquet(getvariable('part_files'))
  WHERE bbox.xmin <= getvariable('xmax') AND bbox.xmax >= getvariable('xmin')
    AND bbox.ymin <= getvariable('ymax') AND bbox.ymax >= getvariable('ymin')
    AND is_underground IS NOT TRUE
) TO 'building_part.parquet' (FORMAT parquet, COMPRESSION zstd);

SELECT 'building' AS typ, count(*) AS anzahl FROM 'building.parquet'
UNION ALL
SELECT 'building_part', count(*) FROM 'building_part.parquet';
