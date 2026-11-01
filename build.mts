// gpxto3d: Kachelbau. Baut für eine Region die Kachelsätze buildings (Overture) und osm (OpenStreetMap) als PMTiles.
// Das ist die Methode der Datenbearbeitung (ODbL 4.6); Kurzbeschreibung in README.md.
//
//   node build.mts --region dach --work <arbeitsordner> [Optionen, siehe OPTIONS]
//
// Node ab 22.18 (führt TypeScript direkt aus), keine npm-Pakete. JDK, Planetiler und DuckDB lädt das Skript selbst nach
// <arbeitsordner>/tools, geprüft gegen die SHA-256 in tools.json; systemweit wird nichts installiert.
// Ergebnis: <arbeitsordner>/staging/{buildings,osm}.pmtiles und staging/build.json mit Versionen und Quellen.
import { spawnSync, type SpawnSyncOptions } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  chmodSync, closeSync, createReadStream, createWriteStream, existsSync, mkdirSync, openSync, readdirSync, readFileSync,
  readSync, renameSync, rmSync, statSync, writeFileSync,
} from 'node:fs';
import { basename, delimiter, dirname, join, resolve } from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { gunzipSync } from 'node:zlib';

export interface Region {
  id: string; name: string; bounds: [number, number, number, number];
  /** Pfad des Auszugs bei download.geofabrik.de, etwa europe/dach. */
  geofabrik?: string;
  /** Ob die OSM-Kacheln den Ozean tragen; oceanProbes sind Punkte im offenen Meer für die Prüfung nach dem Bau. */
  ocean: boolean; oceanProbes?: [number, number][];
}
export interface BuildOptions {
  region: Region; work: string; tools?: string; only?: 'buildings' | 'osm';
  /** Overture-Release-ID; ohne Angabe die neueste laut STAC-Katalog. */
  overtureRelease?: string;
  /** Fertige Eingaben statt Download bzw. Extraktion. */
  buildingParquet?: string; buildingPartParquet?: string; osmPbf?: string; oceanShapefile?: string;
  threads?: number; heap?: string;
}
export interface BuiltTileset { file: string; version: string; counts?: Record<string, number>; sources: string[] }
export interface BuildResult { region: string; finishedAt: string; buildings?: BuiltTileset; osm?: BuiltTileset }

const METHOD_DIR = dirname(fileURLToPath(import.meta.url));
const OVERTURE_STAC = 'https://stac.overturemaps.org/catalog.json';
const GEOFABRIK = 'https://download.geofabrik.de';
const OCEAN_URL = 'https://osmdata.openstreetmap.de/download/water-polygons-split-3857.zip';

export const OPTIONS = {
  region: { type: 'string' }, work: { type: 'string' }, tools: { type: 'string' }, only: { type: 'string' },
  'overture-release': { type: 'string' }, 'building-parquet': { type: 'string' },
  'building-part-parquet': { type: 'string' }, 'osm-pbf': { type: 'string' }, 'ocean-shapefile': { type: 'string' },
  threads: { type: 'string' }, heap: { type: 'string' },
} as const;

const log = (message: string) => console.log(`[${new Date().toTimeString().slice(0, 8)}] ${message}`);
const sqlString = (value: string) => `'${value.replace(/\\/g, '/').replace(/'/g, "''")}'`;
const megabytes = (bytes: number) => `${(bytes / 1e6).toFixed(0)} MB`;

function run(command: string, args: string[], options: SpawnSyncOptions = {}) {
  const result = spawnSync(command, args, { stdio: 'inherit', ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${basename(command)} endete mit Code ${result.status}.`);
  return result;
}

/** Region by name (regions/<name>.json) or by path. */
export function readRegion(nameOrPath: string): Region {
  const file = nameOrPath.endsWith('.json') ? nameOrPath : join(METHOD_DIR, 'regions', `${nameOrPath}.json`);
  return JSON.parse(readFileSync(file, 'utf8'));
}

async function hashFile(file: string, algorithm: 'sha256' | 'md5') {
  const hash = createHash(algorithm);
  await pipeline(createReadStream(file), hash);
  return hash.digest('hex');
}

/** Downloads to <target>.part, resuming an earlier attempt; the caller verifies and renames. */
async function download(url: string, target: string) {
  const part = `${target}.part`;
  mkdirSync(dirname(part), { recursive: true });
  const have = existsSync(part) ? statSync(part).size : 0;
  const response = await fetch(url, { headers: have ? { Range: `bytes=${have}-` } : {} });
  if (response.status === 416) return part;
  if (!response.ok || !response.body) throw new Error(`${url}: HTTP ${response.status}`);
  const resumed = have > 0 && response.status === 206;
  const total = (resumed ? have : 0) + Number(response.headers.get('content-length') ?? 0);
  let done = resumed ? have : 0, shown = Date.now();
  log(`Lade ${url}${total ? ` (${megabytes(total)})` : ''}${resumed ? `, weiter ab ${megabytes(have)}` : ''}`);
  const body = Readable.fromWeb(response.body as import('node:stream/web').ReadableStream);
  body.on('data', (chunk: Buffer) => {
    done += chunk.length;
    if (Date.now() - shown > 30_000) { log(`  ${megabytes(done)} von ${megabytes(total)}`); shown = Date.now(); }
  });
  await pipeline(body, createWriteStream(part, { flags: resumed ? 'a' : 'w' }));
  return part;
}

interface Pin { url: string; file: string; sha256: string }

async function fetchPinned(pin: Pin, dir: string) {
  const target = join(dir, pin.file);
  if (existsSync(target) && await hashFile(target, 'sha256') === pin.sha256) return target;
  const part = await download(pin.url, target);
  const actual = await hashFile(part, 'sha256');
  if (actual !== pin.sha256) {
    rmSync(part);
    throw new Error(`${pin.url}: SHA-256 ${actual} statt ${pin.sha256}.`);
  }
  renameSync(part, target);
  return target;
}

/** Unpacks into a scratch folder first, so a half-unpacked tool never looks finished. */
function unpack(archive: string, into: string) {
  const scratch = join(into, '.unpack');
  rmSync(scratch, { recursive: true, force: true });
  mkdirSync(scratch, { recursive: true });
  // Windows' own tar (bsdtar) also reads ZIP; Git Bash's GNU tar does not.
  const tar = process.platform === 'win32' ? join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'tar.exe') : 'tar';
  run(tar, ['-xf', archive, '-C', scratch]);
  for (const entry of readdirSync(scratch)) {
    rmSync(join(into, entry), { recursive: true, force: true });
    renameSync(join(scratch, entry), join(into, entry));
  }
  rmSync(scratch, { recursive: true, force: true });
}

export async function prepareTools(dir: string) {
  const pins = JSON.parse(readFileSync(join(METHOD_DIR, 'tools.json'), 'utf8'));
  const platform = `${process.platform}-${process.arch}`, exe = process.platform === 'win32' ? '.exe' : '';
  if (!pins.jdk[platform]) throw new Error(`Für ${platform} sind keine Werkzeuge hinterlegt (tools.json); mit --tools einen Ordner mit derselben Ablage vorgeben.`);
  const jdk = join(dir, 'jdk', pins.jdk.dir, 'bin');
  if (!existsSync(join(jdk, `java${exe}`))) unpack(await fetchPinned(pins.jdk[platform], join(dir, 'jdk')), join(dir, 'jdk'));
  const jar = join(dir, 'planetiler', pins.planetiler.file);
  if (!existsSync(jar)) await fetchPinned(pins.planetiler, join(dir, 'planetiler'));
  const duckdb = join(dir, 'duckdb', `duckdb${exe}`);
  if (!existsSync(duckdb)) {
    const archive = await fetchPinned(pins.duckdb[platform], join(dir, 'duckdb'));
    if (archive.endsWith('.zip')) unpack(archive, join(dir, 'duckdb'));
    else { writeFileSync(`${duckdb}.part`, gunzipSync(readFileSync(archive))); chmodSync(`${duckdb}.part`, 0o755); renameSync(`${duckdb}.part`, duckdb); }
  }
  const extensions = join(dir, 'duckdb-ext');
  const installed = join(extensions, `v${pins.duckdb.version}`, pins.duckdb.extensionPlatform[platform]);
  if (!['httpfs', 'spatial'].every(name => existsSync(join(installed, `${name}.duckdb_extension`))))
    run(duckdb, ['-c', `SET extension_directory=${sqlString(extensions)}; INSTALL httpfs; INSTALL spatial;`]);
  log(`Werkzeuge bereit: ${pins.jdk.version}, Planetiler ${pins.planetiler.version}, DuckDB ${pins.duckdb.version} (${dir})`);
  return { java: join(jdk, `java${exe}`), javac: join(jdk, `javac${exe}`), jar, duckdb, extensions };
}
type Tools = Awaited<ReturnType<typeof prepareTools>>;

/** Removes the inputs of earlier builds from a data folder, so quarterly builds do not pile up. */
function pruneInputs(dir: string, keep: string, earlier: (name: string) => boolean) {
  if (!existsSync(dir)) return;
  for (const name of readdirSync(dir)) if (name !== keep && earlier(name)) {
    log(`Entferne ältere Eingabe ${join(dir, name)}`);
    rmSync(join(dir, name), { recursive: true, force: true });
  }
}

function compileProfiles(tools: Tools, work: string) {
  const sources = join(METHOD_DIR, 'profiles'), classes = join(work, 'build', 'profiles');
  rmSync(classes, { recursive: true, force: true });
  run(tools.javac, ['-encoding', 'UTF-8', '-proc:none', '-implicit:none', '-sourcepath', sources, '-cp', tools.jar,
    '-d', classes, join(sources, 'GpxBuildings.java'), join(sources, 'GpxOsm.java')]);
  return classes;
}

function planetiler(tools: Tools, classes: string, profile: string, output: string, args: string[], o: BuildOptions) {
  const tmp = join(o.work, 'tmp', `planetiler-${basename(output, '.pmtiles')}`);
  run(tools.java, [`-Xmx${o.heap ?? '8g'}`, '-cp', [tools.jar, classes].join(delimiter), profile, ...args,
    `--output=${output}`, `--tmpdir=${tmp}`, `--bounds=${o.region.bounds.join(',')}`,
    ...(o.threads ? [`--threads=${o.threads}`] : [])]);
  rmSync(tmp, { recursive: true, force: true });
}

function countRows(tools: Tools, parquet: string) {
  const result = spawnSync(tools.duckdb, ['-csv', '-noheader', '-c',
    `SET extension_directory=${sqlString(tools.extensions)}; SELECT count(*) FROM ${sqlString(parquet)}`], { encoding: 'utf8' });
  const rows = Number(result.stdout.trim());
  if (result.status !== 0 || !Number.isInteger(rows)) throw new Error(`Zeilen von ${parquet} nicht lesbar: ${result.stderr}`);
  return rows;
}

async function latestOvertureRelease() {
  const response = await fetch(OVERTURE_STAC);
  const release = response.ok ? (await response.json()).latest : undefined;
  if (typeof release !== 'string') throw new Error(`${OVERTURE_STAC} nennt keinen aktuellen Release.`);
  return release;
}

/** Overture building and building_part of the region bbox, extracted with DuckDB (sql/extract-buildings.sql). */
function extractBuildings(o: BuildOptions, tools: Tools, release: string) {
  const dir = join(o.work, 'data', `overture-${release}-${o.region.id}`), partial = `${dir}.partial`;
  const files = { building: join(dir, 'building.parquet'), part: join(dir, 'building_part.parquet') };
  if (existsSync(files.part)) { log(`Overture-Auszug vorhanden: ${dir}`); return files; }
  rmSync(partial, { recursive: true, force: true });
  mkdirSync(partial, { recursive: true });
  const [xmin, ymin, xmax, ymax] = o.region.bounds;
  writeFileSync(join(partial, 'run.sql'), [
    `SET extension_directory=${sqlString(tools.extensions)};`,
    `SET temp_directory=${sqlString(join(o.work, 'tmp', 'duckdb'))};`,
    `SET VARIABLE release = '${release}';`,
    `SET VARIABLE xmin = ${xmin}; SET VARIABLE ymin = ${ymin}; SET VARIABLE xmax = ${xmax}; SET VARIABLE ymax = ${ymax};`,
    readFileSync(join(METHOD_DIR, 'sql', 'extract-buildings.sql'), 'utf8'),
  ].join('\n'));
  log(`Extrahiere Overture ${release} für ${o.region.name} (DuckDB)`);
  run(tools.duckdb, ['-bail', '-f', 'run.sql'], { cwd: partial });
  rmSync(join(partial, 'run.sql'));
  renameSync(partial, dir);
  return files;
}

async function buildBuildings(o: BuildOptions, tools: Tools, classes: string): Promise<BuiltTileset> {
  const given = o.buildingParquet && o.buildingPartParquet;
  if (given && !o.overtureRelease) throw new Error('Zu vorgegebenen Parquet-Dateien gehört --overture-release.');
  const release = o.overtureRelease ?? await latestOvertureRelease();
  const input = given ? { building: resolve(o.buildingParquet!), part: resolve(o.buildingPartParquet!) } : extractBuildings(o, tools, release);
  const counts = { 'overture:building': countRows(tools, input.building), 'overture:building_part': countRows(tools, input.part) };
  const file = join(o.work, 'staging', 'buildings.pmtiles');
  log(`Baue buildings (Overture ${release}, ${counts['overture:building']} Gebäude, ${counts['overture:building_part']} Gebäudeteile)`);
  planetiler(tools, classes, 'GpxBuildings', file, [`--building=${input.building}`, `--building_part=${input.part}`,
    '--exclude_ids=true', `--overture_release=${release}`], o);
  if (!given) pruneInputs(join(o.work, 'data'), basename(dirname(input.building)),
    name => name.startsWith('overture-') && name.endsWith(`-${o.region.id}`));
  return { file, version: release, counts, sources: [`Overture Maps Foundation, Release ${release}`] };
}

/** The dated Geofabrik extract behind <path>-latest.osm.pbf, checked against its MD5. */
async function downloadGeofabrik(path: string, dir: string) {
  const latest = `${GEOFABRIK}/${path}-latest.osm.pbf`;
  const head = await fetch(latest, { method: 'HEAD', redirect: 'manual' });
  const url = head.headers.get('location') ? new URL(head.headers.get('location')!, latest).href : latest;
  const target = join(dir, basename(new URL(url).pathname));
  if (existsSync(target)) { log(`Geofabrik-Auszug vorhanden: ${target}`); return target; }
  const md5 = (await (await fetch(`${url}.md5`)).text()).trim().split(/\s+/)[0];
  const part = await download(url, target);
  const actual = await hashFile(part, 'md5');
  if (actual !== md5) { rmSync(part); throw new Error(`${url}: MD5 ${actual} statt ${md5}.`); }
  renameSync(part, target);
  return target;
}

/** The osmdata ocean polygons (EPSG:3857), one folder per server date; the server publishes no checksum. */
async function downloadOcean(dir: string) {
  let head: Response;
  try { head = await fetch(OCEAN_URL, { method: 'HEAD', signal: AbortSignal.timeout(60_000) }); }
  catch (error) { throw new Error(`${OCEAN_URL} ist nicht erreichbar (${(error as Error).message}). Die Wasserpolygone lassen sich mit --ocean-shapefile vorgeben.`); }
  if (!head.ok) throw new Error(`${OCEAN_URL}: HTTP ${head.status}`);
  const date = new Date(head.headers.get('last-modified') ?? Date.now()).toISOString().slice(0, 10);
  const target = join(dir, date, basename(OCEAN_URL)), size = Number(head.headers.get('content-length'));
  if (existsSync(target)) { log(`Wasserpolygone vorhanden: ${target}`); return target; }
  const part = await download(OCEAN_URL, target);
  if (size && statSync(part).size !== size) throw new Error(`${OCEAN_URL}: ${statSync(part).size} statt ${size} Bytes.`);
  renameSync(part, target);
  return target;
}

function archiveMetadata(file: string): Record<string, string> {
  const fd = openSync(file, 'r');
  try {
    const head = Buffer.alloc(127);
    readSync(fd, head, 0, 127, 0);
    const raw = Buffer.alloc(Number(head.readBigUInt64LE(32)));
    readSync(fd, raw, 0, raw.length, Number(head.readBigUInt64LE(24)));
    return JSON.parse((head[97] === 2 ? gunzipSync(raw) : raw).toString('utf8'));
  } finally { closeSync(fd); }
}

/** Date of the OSM data: replication time of the extract, else the date in a Geofabrik file name. */
function osmVersion(archive: string, pbf: string) {
  const time = archiveMetadata(archive)['planetiler:osm:osmosisreplicationtime'];
  if (time) return time.slice(0, 10);
  const dated = /-(\d\d)(\d\d)(\d\d)\.osm\.pbf$/.exec(pbf);
  if (dated) return `20${dated[1]}-${dated[2]}-${dated[3]}`;
  throw new Error(`Datenstand von ${pbf} unbekannt.`);
}

async function buildOsm(o: BuildOptions, tools: Tools, classes: string): Promise<BuiltTileset> {
  if (!o.osmPbf && !o.region.geofabrik) throw new Error(`Region ${o.region.id} nennt keinen Geofabrik-Auszug; --osm-pbf angeben.`);
  const pbf = o.osmPbf ? resolve(o.osmPbf) : await downloadGeofabrik(o.region.geofabrik!, join(o.work, 'data', 'geofabrik'));
  const ocean = o.region.ocean ? (o.oceanShapefile ? resolve(o.oceanShapefile) : await downloadOcean(join(o.work, 'data', 'osmdata'))) : undefined;
  const file = join(o.work, 'staging', 'osm.pmtiles');
  log(`Baue osm (${basename(pbf)}${ocean ? `, Ozean ${ocean}` : ', ohne Ozean'})`);
  planetiler(tools, classes, 'GpxOsm', file, [`--osm_path=${pbf}`, ocean ? `--ocean_path=${ocean}` : '--ocean=false',
    '--nodemap_type=sortedtable'], o);
  const version = osmVersion(file, pbf);
  if (!o.osmPbf) pruneInputs(dirname(pbf), basename(pbf), name => name.startsWith(`${basename(o.region.geofabrik!)}-`));
  if (ocean && !o.oceanShapefile) pruneInputs(dirname(dirname(ocean)), basename(dirname(ocean)), name => /^\d{4}-\d\d-\d\d$/.test(name));
  const sources = [`© OpenStreetMap-Mitwirkende (ODbL), Auszug ${basename(pbf)} von Geofabrik, Stand ${version}`];
  if (ocean) sources.push(`Wasserpolygone von osmdata.openstreetmap.de (${basename(dirname(ocean))}/${basename(ocean)})`);
  return { file, version, sources };
}

export async function buildTiles(options: BuildOptions): Promise<BuildResult> {
  const o = { ...options, work: resolve(options.work) };
  const staging = join(o.work, 'staging');
  mkdirSync(staging, { recursive: true });
  const tools = await prepareTools(resolve(o.tools ?? join(o.work, 'tools')));
  const classes = compileProfiles(tools, o.work);
  const result: BuildResult = { region: o.region.id, finishedAt: '' };
  for (const name of ['buildings', 'osm'] as const) if (!o.only || o.only === name) rmSync(join(staging, `${name}.pmtiles`), { force: true });
  if (o.only !== 'osm') result.buildings = await buildBuildings(o, tools, classes);
  if (o.only !== 'buildings') result.osm = await buildOsm(o, tools, classes);
  result.finishedAt = new Date().toISOString();
  writeFileSync(join(staging, 'build.json'), JSON.stringify(result, null, 1));
  log(`Fertig: ${join(staging, 'build.json')}`);
  return result;
}

/** Turns parsed command-line values into build options. */
export function buildOptions(values: Partial<Record<keyof typeof OPTIONS, string>>, defaultWork?: string): BuildOptions {
  const work = values.work ?? defaultWork;
  if (!work) throw new Error('--work <arbeitsordner> fehlt.');
  if (values.only && values.only !== 'buildings' && values.only !== 'osm') throw new Error('--only ist buildings oder osm.');
  return {
    region: readRegion(values.region ?? 'dach'), work, tools: values.tools, only: values.only as BuildOptions['only'],
    overtureRelease: values['overture-release'], buildingParquet: values['building-parquet'],
    buildingPartParquet: values['building-part-parquet'], osmPbf: values['osm-pbf'], oceanShapefile: values['ocean-shapefile'],
    threads: values.threads ? Number(values.threads) : undefined, heap: values.heap,
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  buildTiles(buildOptions(parseArgs({ options: OPTIONS }).values)).catch(error => {
    console.error(error instanceof Error ? error.message : error);
    process.exit(1);
  });
}
