import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.FeatureMerge;
import com.onthegomap.planetiler.Planetiler;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.expression.Expression;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.geo.GeometryPipeline;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.util.Parse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;
import org.openmaptiles.OpenMapTilesProfile;
import org.openmaptiles.util.Utils;

/**
 * gpxto3d: OSM-Kacheln fuer Wasser, Wald, Flusslinien, Strassen und Baeume (Kachelsatz {@code osm}).
 *
 * <p>Eine PMTiles-Datei mit den Zoomstufen 8 bis 14 im OpenMapTiles-Schema (Feldnamen wie OpenMapTiles):
 * <ul>
 *   <li>z8–z13 {@code water}: class (lake, river, pond, dock, swimming_pool, ocean), intermittent=1 nur falls gesetzt.
 *       Je Kachel verschmolzen, Ufer vereinfacht und geglaettet, Kleinstflaechen und -inseln entfernt.
 *       Tunnel (z. B. verdolte Baeche als Flaeche) fehlen. Ozean bzw. Nord- und Ostsee aus den osmdata-Wasserpolygonen.</li>
 *   <li>z8–z13 {@code landcover}: nur class=wood (landuse=forest, natural=wood), leaf_type falls gueltig; verschmolzen.</li>
 *   <li>z14 {@code waterway}: class (river, stream, canal, drain, ditch), name, brunnel, intermittent, id (OSM-Weg),
 *       width (Meter, aus dem OSM-Tag width). z8–z13: generalisiert aus Planetilers eingebautem OpenMapTiles-Profil
 *       (lange benannte Fluesse zuerst, Baeche ab z13), nur class, name, brunnel, intermittent.</li>
 *   <li>z8–z14 {@code transportation}: aus dem eingebauten OpenMapTiles-Profil, gekuerzt auf die Felder class,
 *       subclass und brunnel; in z8–z12 nur Hauptstrassen (motorway bis tertiary, je nach Stufe wie OpenMapTiles).</li>
 *   <li>z14 {@code trees}: class=tree (Punkte, natural=tree), class=tree_row (Linien, natural=tree_row), leaf_type.</li>
 * </ul>
 *
 * <p>Die Klasse erweitert das eingebaute OpenMapTiles-Profil, das nur {@code transportation} und {@code waterway}
 * liefert ({@code only_layers=transportation,waterway}). Die uebrigen Ebenen erzeugt dieses Profil selbst.
 *
 * <p>Bau und Lauf (Planetiler 0.10.2, JDK 21; unter Linux ':' statt ';' im Klassenpfad):
 * <pre>
 * javac -encoding UTF-8 -proc:none -implicit:none -sourcepath . -cp planetiler.jar -d build GpxOsm.java
 * java -Xmx8g -cp "planetiler.jar;build" GpxOsm --osm_path=dach-latest.osm.pbf \
 *   --ocean_path=water-polygons-split-3857.zip --output=osm.pmtiles --tmpdir=tmp --bounds=5.8,45.8,17.2,55.1
 * </pre>
 *
 * <p>Laengen und Flaechen der Glaettung sind Kachelpixel (256 px je Kachel) der jeweiligen Zoomstufe, gelten also in
 * jeder Stufe sinngemaess gleich. In z13 ist ein Pixel bei 48 Grad Nord etwa 12,7 m, bei 54 Grad etwa 11,2 m, bei
 * 46 Grad etwa 13,3 m; in z8 das 32-Fache.
 */
public class GpxOsm extends OpenMapTilesProfile {

  static final int Z_MIN = 8;
  static final int Z_AREA_MAX = 13;
  static final int Z_LINE = 14;
  static final String OCEAN_SOURCE = "ocean";
  static final double BUFFER_PX = 4;
  static final Set<String> LEAF_TYPES = Set.of("broadleaved", "needleleaved", "mixed", "leafless");
  /** Die Felder von transportation, die das Studio liest. */
  static final Set<String> TRANSPORTATION_FIELDS = Set.of("class", "subclass", "brunnel");
  /** In z8–z12 nur Hauptstrassen; OpenMapTiles fuehrt ab z12 auch Wohnstrassen. */
  static final int Z_MAIN_ROADS_MAX = 12;
  static final Set<String> MAIN_ROADS = Set.of("motorway", "trunk", "primary", "secondary", "tertiary");
  /** Felder der generalisierten Flusslinien unter z14 (ohne Namensuebersetzungen). */
  static final Set<String> WATERWAY_FIELDS = Set.of("class", "name", "brunnel", "intermittent");

  /** Glaettung und Verschmelzung einer Flaechenebene (Werte in Kachelpixeln). */
  record AreaStyle(String layer, double simplifyPx, double closePx, double openPx, double smoothMaxPx,
    double minAreaPx2, double featureMinPx) {

    static AreaStyle from(Arguments a, String layer, double simplify, double close, double open, double smooth,
      double minArea) {
      return new AreaStyle(layer,
        a.getDouble(layer + "_simplify_px", "VW-Vereinfachung vor dem Kacheln (DP-aequivalente Toleranz, px)", simplify),
        a.getDouble(layer + "_close_px", "Schliessen: fuellt Buchten/Luecken schmaler als 2x Wert, verbindet Nachbarn (px)",
          close),
        a.getDouble(layer + "_open_px", "Oeffnen: entfernt Zipfel und Streifen schmaler als 2x Wert (px)", open),
        a.getDouble(layer + "_smooth_max_px", "groesste Abweichung der Chaikin-Glaettung (px)", smooth),
        a.getDouble(layer + "_min_area_px2", "kleinste Flaeche und kleinste Insel nach dem Verschmelzen (px^2)", minArea),
        a.getDouble(layer + "_feature_min_px", "Mindestgroesse je OSM-Flaeche vor dem Verschmelzen (Kantenlaenge, px)",
          0.5));
    }

    /** Vor dem Kacheln, je OSM-Flaeche und Zoomstufe: global gerechnet, also an Kachelgrenzen identisch. */
    GeometryPipeline beforeTiling() {
      double t = simplifyPx / 256d; // Geometrie ist hier in Kacheleinheiten der Zoomstufe skaliert
      return simplifyPx > 0 ? GeometryPipeline.simplifyVW(t * t) : GeometryPipeline.NOOP;
    }

    /**
     * Nach dem Verschmelzen, je Kachel und je verschmolzener Flaeche (auch einzelne Seen): Schliessen, Oeffnen,
     * Chaikin-Glaettung mit begrenzter Abweichung. Alle drei wirken nur bis etwa closePx + openPx + smoothMaxPx
     * (hier hoechstens gut 1 px) von einer Kante; das ist viel weniger als der Kachelpuffer (4 px). Die Schnittkanten
     * im Puffer veraendern innerhalb der Kachel also nichts; Nachbarkacheln passen weiter aufeinander.
     */
    GeometryPipeline afterMerge() {
      var chaikin = GeometryPipeline.smoothChaikin(10)
        .setMinVertexOffset(1d / 16) // ein Raster-Schritt der 4096er-Kachel
        .setMaxVertexOffset(smoothMaxPx);
      var dp = GeometryPipeline.simplifyDP(1d / 32); // ueberzaehlige Punkte unterhalb des Rasters
      return g -> {
        Geometry r = g;
        if (closePx > 0) {
          r = r.buffer(closePx, 4).buffer(-closePx, 4);
        }
        if (openPx > 0) {
          r = r.buffer(-openPx, 4).buffer(openPx, 4);
        }
        if (smoothMaxPx > 0) {
          r = chaikin.apply(r);
        }
        return dp.apply(r);
      };
    }
  }

  private final Stats stats;
  private final AreaStyle water;
  private final AreaStyle wood;
  private final GeometryPipeline waterSmooth;
  private final GeometryPipeline woodSmooth;

  public GpxOsm(Planetiler planetiler) {
    super(planetiler.translations(), planetiler.config(), planetiler.stats());
    this.stats = planetiler.stats();
    Arguments a = planetiler.config().arguments();
    // Standardwerte: siehe Probebau der OSM-Kacheln (Ticket 16), Abschnitt Glaettung.
    this.water = AreaStyle.from(a, "water", 0.3, 0.5, 0.2, 0.5, 2);
    this.wood = AreaStyle.from(a, "wood", 0.3, 0.5, 0.25, 0.5, 4);
    this.waterSmooth = water.afterMerge();
    this.woodSmooth = wood.afterMerge();
  }

  @Override
  public boolean caresAbout(Expression.PartialInput input) {
    return true; // eigene Ebenen sind nicht als Handler registriert
  }

  @Override
  public boolean caresAboutSource(String name) {
    return true;
  }

  @Override
  public void processFeature(SourceFeature src, FeatureCollector features) {
    // 1. Eingebautes OpenMapTiles-Profil (transportation, waterway). Dessen Flusslinien gelten nur unter z14;
    //    in z14 stehen die eigenen mit id und width.
    super.processFeature(src, features);
    for (var feature : features) {
      if ("waterway".equals(feature.getLayer()) && feature.getMaxZoom() > Z_AREA_MAX) {
        feature.setMaxZoom(Z_AREA_MAX);
      }
    }
    // 2. Eigene Ebenen.
    if (OSM_SOURCE.equals(src.getSource())) {
      processOsm(src, features);
    } else if (OCEAN_SOURCE.equals(src.getSource()) && src.canBePolygon()) {
      areaFeature(features, water, src).setAttr("class", "ocean");
    }
  }

  private void processOsm(SourceFeature src, FeatureCollector features) {
    if (src.isPoint()) {
      if (src.hasTag("natural", "tree")) {
        features.point("trees")
          .setMinZoom(Z_LINE).setMaxZoom(Z_LINE)
          .setBufferPixels(0) // jeder Baum genau in seiner Kachel
          .setAttr("class", "tree")
          .setAttr("leaf_type", leafType(src));
      }
      return;
    }
    if (src.canBePolygon()) {
      if (isWater(src) && !src.hasTag("natural", "bay") && !src.getBoolean("tunnel")) {
        areaFeature(features, water, src)
          .setAttr("class", waterClass(src))
          .setAttr("intermittent", src.getBoolean("intermittent") ? 1 : null);
      }
      if (src.hasTag("landuse", "forest") || src.hasTag("natural", "wood")) {
        areaFeature(features, wood, src)
          .setAttr("class", "wood")
          .setAttr("leaf_type", leafType(src));
      }
    }
    if (src.canBeLine()) {
      if (src.hasTag("waterway", "river", "stream", "canal", "drain", "ditch")) {
        String name = src.getString("name");
        features.line("waterway")
          .setMinZoom(Z_LINE).setMaxZoom(Z_LINE)
          .setBufferPixels(BUFFER_PX)
          .setAttr("class", src.getString("waterway"))
          .setAttr("name", name == null || name.isBlank() ? null : name)
          .setAttr("brunnel", Utils.brunnel(src.getBoolean("bridge"), src.getBoolean("tunnel")))
          .setAttr("intermittent", src.getBoolean("intermittent") ? 1 : 0)
          .setAttr("id", src.id())
          .setAttr("width", Parse.meters(src.getTag("width")));
      }
      if (src.hasTag("natural", "tree_row")) {
        features.line("trees")
          .setMinZoom(Z_LINE).setMaxZoom(Z_LINE)
          .setBufferPixels(BUFFER_PX)
          .setAttr("class", "tree_row")
          .setAttr("leaf_type", leafType(src));
      }
    }
  }

  private static FeatureCollector.Feature areaFeature(FeatureCollector features, AreaStyle style, SourceFeature src) {
    return features.polygon(style.layer().equals("wood") ? "landcover" : style.layer())
      .setMinZoom(Z_MIN).setMaxZoom(Z_AREA_MAX)
      .setBufferPixels(BUFFER_PX)
      .setMinPixelSize(style.featureMinPx())
      .transformScaledGeometry(style.beforeTiling());
  }

  /** Auswahl wie OpenMapTiles-Tabelle osm_water_polygon (ohne covered=yes). */
  private static boolean isWater(SourceFeature src) {
    return (src.hasTag("landuse", "reservoir", "basin", "salt_pond") ||
      src.hasTag("leisure", "swimming_pool") ||
      src.hasTag("natural", "water", "bay", "spring") ||
      src.hasTag("waterway", "dock") ||
      src.hasTag("water", "river", "stream", "canal", "ditch", "drain", "pond", "basin", "wastewater")) &&
      !src.hasTag("covered", "yes");
  }

  /** Klassen und Reihenfolge wie OpenMapTiles water.class. */
  private static String waterClass(SourceFeature src) {
    if (src.hasTag("waterway", "dock")) {
      return "dock";
    } else if (src.hasTag("water", "river", "stream", "canal", "ditch", "drain")) {
      return "river";
    } else if (src.hasTag("water", "pond", "basin", "wastewater", "salt_pond")) {
      return "pond";
    } else if (src.hasTag("leisure", "swimming_pool")) {
      return "swimming_pool";
    }
    return "lake";
  }

  private static String leafType(SourceFeature src) {
    String value = src.getString("leaf_type");
    return value != null && LEAF_TYPES.contains(value) ? value : null;
  }

  @Override
  public List<VectorTile.Feature> postProcessLayerFeatures(String layer, int zoom, List<VectorTile.Feature> items)
    throws GeometryException {
    return switch (layer) {
      case "water" -> merge(items, water, waterSmooth);
      case "landcover" -> merge(items, wood, woodSmooth);
      // Erst die OpenMapTiles-Nachbearbeitung (Verschmelzen wie dort), dann kuerzen: So bleiben die Featurezahlen
      // in z14 mit dem Probebau vergleichbar.
      case "transportation" -> {
        var roads = keepFields(super.postProcessLayerFeatures(layer, zoom, items), items, TRANSPORTATION_FIELDS);
        if (zoom <= Z_MAIN_ROADS_MAX) {
          roads.removeIf(road -> !MAIN_ROADS.contains(road.tags().get("class")));
        }
        yield roads;
      }
      case "waterway" -> zoom < Z_LINE ?
        keepFields(super.postProcessLayerFeatures(layer, zoom, items), items, WATERWAY_FIELDS) :
        super.postProcessLayerFeatures(layer, zoom, items);
      default -> super.postProcessLayerFeatures(layer, zoom, items);
    };
  }

  /** Behaelt nur die genannten Felder; {@code processed} ist null, wenn die Nachbearbeitung nichts geaendert hat. */
  private static List<VectorTile.Feature> keepFields(List<VectorTile.Feature> processed,
    List<VectorTile.Feature> items, Set<String> fields) {
    List<VectorTile.Feature> source = processed == null ? items : processed;
    List<VectorTile.Feature> result = new ArrayList<>(source.size());
    for (var item : source) {
      if (item != null) {
        Map<String, Object> attrs = new HashMap<>(item.tags());
        attrs.keySet().retainAll(fields);
        result.add(new VectorTile.Feature(item.layer(), item.id(), item.geometry(), attrs, item.group()));
      }
    }
    return result;
  }

  private List<VectorTile.Feature> merge(List<VectorTile.Feature> items, AreaStyle style, GeometryPipeline smooth)
    throws GeometryException {
    // Gruppiert nach gleichen Attributen und Naehe (2x Schliessradius), vereinigt jede Gruppe (Puffer 0),
    // dann die Pipeline (Schliessen, Oeffnen, Glaetten). minHoleArea > 0 entfernt in Planetiler 0.10.2 Inseln
    // unter minArea (extractPolygons vergleicht Loecher mit minArea).
    return FeatureMerge.mergeNearbyPolygons(items, style.minAreaPx2(), style.minAreaPx2(),
      2 * style.closePx(), 0, stats, smooth);
  }

  @Override
  public String name() {
    return "gpxto3d osm";
  }

  @Override
  public String description() {
    return "OSM water, wood, waterways, roads and trees (gpxto3d)";
  }

  @Override
  public String attribution() {
    return "<a href=\"https://www.openstreetmap.org/copyright\" target=\"_blank\">&copy; OpenStreetMap</a>";
  }

  @Override
  public String version() {
    return "1";
  }

  public static void main(String[] argv) throws Exception {
    Arguments args = Arguments.of(Map.of("only_layers", "transportation,waterway"))
      .orElse(Arguments.fromArgsOrConfigFile(argv))
      .orElse(Arguments.of(Map.of("minzoom", String.valueOf(Z_MIN), "maxzoom", String.valueOf(Z_LINE),
        "exclude_ids", "true")));
    Path osm = args.inputFile("osm_path", "OSM-PBF (Geofabrik D-A-CH)", Path.of("data", "dach-latest.osm.pbf"));
    boolean withOcean = args.getBoolean("ocean", "Ozean aus den osmdata-Wasserpolygonen einlesen", true);
    var planetiler = Planetiler.create(args)
      .setProfile(GpxOsm::new)
      .addOsmSource(OSM_SOURCE, osm);
    if (withOcean) {
      Path ocean = args.inputFile("ocean_path", "water-polygons-split-3857.zip (osmdata.openstreetmap.de)",
        Path.of("data", "water-polygons-split-3857.zip"));
      planetiler.addShapefileSource("EPSG:3857", OCEAN_SOURCE, ocean);
    }
    planetiler.overwriteOutput(Path.of("out", "osm.pmtiles")).run();
  }
}
