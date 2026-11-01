import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.Planetiler;
import com.onthegomap.planetiler.Profile;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.reader.SourceFeature;
import java.nio.file.Path;
import java.util.List;

/**
 * gpxto3d: Overture-Gebaeude als eigene z14-Vektorkacheln (Kachelsatz {@code buildings}).
 *
 * <p>Eingabe sind zwei mit DuckDB vorgefilterte GeoParquet-Dateien (siehe ../sql/extract-buildings.sql):
 * {@code building.parquet} und {@code building_part.parquet}. Ausgabe ist eine PMTiles-Datei
 * mit genau einer Zoomstufe (14) und den Ebenen {@code building} und {@code building_part}.
 * Unterirdische Gebaeude, Namen, Adressen und Quellenlisten fehlen, ebenso MVT-Feature-IDs
 * ({@code --exclude_ids=true}); die GERS-ID steht im Attribut {@code id}. Die Overture-Release-ID
 * ({@code --overture_release}) steht als {@code version} in den Metadaten.
 *
 * <p>Bau und Lauf (Planetiler 0.10.x, JDK 21):
 * <pre>
 * javac -proc:none -implicit:none -sourcepath . -cp planetiler.jar -d build GpxBuildings.java
 * java -Xmx8g -cp "planetiler.jar;build" GpxBuildings \
 *   --building=data/building.parquet --building_part=data/building_part.parquet \
 *   --output=out/buildings.pmtiles --bounds=5.8,45.8,17.2,55.1 --tmpdir=tmp/planetiler \
 *   --exclude_ids=true --overture_release=2026-10-21.0
 * </pre>
 * (unter Linux/macOS ':' statt ';' im Klassenpfad)
 */
public class GpxBuildings implements Profile {

  static final int ZOOM = 14;

  /** Attribute der Ebene building; id ist die GERS-ID. */
  static final List<String> BUILDING_ATTRS = List.of(
    "id", "height", "num_floors", "min_height", "min_floor",
    "roof_shape", "roof_height", "class", "subtype", "has_parts");

  /** Attribute der Ebene building_part: Hoehen- und Dachattribute plus building_id. */
  static final List<String> PART_ATTRS = List.of(
    "height", "num_floors", "min_height", "min_floor",
    "roof_shape", "roof_height", "building_id");

  private final String release;

  GpxBuildings(String release) {
    this.release = release;
  }

  @Override
  public void processFeature(SourceFeature src, FeatureCollector features) {
    if (!src.canBePolygon()) {
      return;
    }
    // Sicherheitsnetz: DuckDB filtert unterirdische Gebaeude bereits weg.
    if (Boolean.TRUE.equals(src.getTag("is_underground"))) {
      return;
    }
    String layer = src.getSource(); // Quellname = Ebenenname
    List<String> attrs = switch (layer) {
      case "building" -> BUILDING_ATTRS;
      case "building_part" -> PART_ATTRS;
      default -> null;
    };
    if (attrs == null) {
      return;
    }
    var polygon = features.polygon(layer)
      .setMinZoom(ZOOM)
      .setMaxZoom(ZOOM)
      // Kein Grundriss darf wegen Mindestgroesse wegfallen (Planetiler-Standard: 1/16 Pixel).
      .setMinPixelSize(0);
    // Vereinfachung bleibt beim Planetiler-Standard fuer die hoechste Zoomstufe: 1/16 Pixel,
    // also eine Einheit des 4096er-Kachelrasters (bei 48 Grad Nord etwa 0,4 m).
    // Flaechen, die schmaler als eine Rastereinheit sind, fallen beim Einrasten trotzdem weg
    // (Probebau: 3.059 von 76,2 Mio. Gebaeuden, ebenso in den offiziellen Overture-Kacheln).
    for (String attr : attrs) {
      Object value = src.getTag(attr);
      if (value != null) {
        polygon.setAttr(attr, value);
      }
    }
  }

  @Override
  public String name() {
    return "gpxto3d buildings";
  }

  @Override
  public String description() {
    return "Overture buildings and building parts, z14 only (gpxto3d)";
  }

  @Override
  public String attribution() {
    return "<a href=\"https://www.openstreetmap.org/copyright\" target=\"_blank\">&copy; OpenStreetMap</a> "
      + "<a href=\"https://docs.overturemaps.org/attribution\" target=\"_blank\">&copy; Overture Maps Foundation</a>";
  }

  @Override
  public String version() {
    return release;
  }

  @Override
  public boolean isOverlay() {
    return true;
  }

  public static void main(String[] argv) throws Exception {
    Arguments args = Arguments.fromArgsOrConfigFile(argv)
      .orElse(Arguments.of("minzoom", ZOOM, "maxzoom", ZOOM));
    Path building = args.inputFile("building", "GeoParquet mit Overture building", Path.of("data", "building.parquet"));
    Path part = args.inputFile("building_part", "GeoParquet mit Overture building_part",
      Path.of("data", "building_part.parquet"));
    String release = args.getString("overture_release", "Overture-Release-ID der Eingabe", "");
    Planetiler.create(args)
      .setProfile(new GpxBuildings(release))
      // Feature-ID = Hash der GERS-ID; Ebene = Quellname
      .addParquetSource("building", List.of(building), false, f -> f.get("id"), f -> "building")
      .addParquetSource("building_part", List.of(part), false, f -> f.get("id"), f -> "building_part")
      .overwriteOutput(Path.of("out", "buildings.pmtiles"))
      .run();
  }
}
