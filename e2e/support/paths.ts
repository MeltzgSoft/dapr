import fs from 'node:fs';
import path from 'node:path';

/**
 * Where the e2e run keeps everything it owns.
 *
 * All of it lives under `e2e/.tmp` (gitignored) rather than the developer's real
 * config directory: the app's system of record is `$XDG_CONFIG_HOME/dapr`, and a
 * test suite that created and deleted libraries there would be editing the
 * libraries someone actually uses. `npm test` wipes `.tmp` first (the `pretest`
 * script), so every run starts from an empty cache and no libraries.
 */
export const E2E_ROOT = path.resolve(__dirname, '..');
export const REPO_ROOT = path.resolve(E2E_ROOT, '..');
export const TMP = path.join(E2E_ROOT, '.tmp');

/** $XDG_CONFIG_HOME for the app under test — its cache.edn and libraries. */
export const CONFIG_DIR = path.join(TMP, 'config');
/** A seeded music library, the source of every sync test. */
export const MUSIC_DIR = path.join(TMP, 'music');
/** An empty directory the sync tests copy into. */
export const SINK_DIR = path.join(TMP, 'sink');

/**
 * Deliberately not 7373: a developer running `clojure -M:run` alongside the
 * suite should not have their session torn down by it, nor lend it their real
 * libraries.
 */
export const PORT = Number(process.env.DAPR_E2E_PORT ?? 7374);
export const BASE_URL = `http://127.0.0.1:${PORT}`;

/**
 * The seeded library. Sizes differ so a capacity figure is visibly wrong if the
 * arithmetic is; the paths are two folders deep because that is what makes the
 * path-derived tags an artist and an album (see dapr.domain.tags/from-path) —
 * these are zero-content files, so there is nothing embedded to read.
 */
export const TRACKS = [
  { rel: 'Alice/Debut/01 Opening.mp3', bytes: 1000, artist: 'Alice', album: 'Debut', title: '01 Opening' },
  { rel: 'Alice/Debut/02 Second.mp3', bytes: 2000, artist: 'Alice', album: 'Debut', title: '02 Second' },
  { rel: 'Alice/Encore/03 Third.mp3', bytes: 3000, artist: 'Alice', album: 'Encore', title: '03 Third' },
  { rel: 'Bob/Solo/04 Fourth.mp3', bytes: 4000, artist: 'Bob', album: 'Solo', title: '04 Fourth' },
];

/**
 * Create the seeded library and the empty sink. Idempotent: the Playwright
 * config module is loaded once per worker process, so this must be safe to run
 * again — it never removes anything (that is `pretest`'s job, once per run).
 */
export function seed(): void {
  for (const dir of [CONFIG_DIR, MUSIC_DIR, SINK_DIR]) {
    fs.mkdirSync(dir, { recursive: true });
  }
  for (const track of TRACKS) {
    const file = path.join(MUSIC_DIR, track.rel);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    if (!fs.existsSync(file) || fs.statSync(file).size !== track.bytes) {
      fs.writeFileSync(file, Buffer.alloc(track.bytes));
    }
  }
}
