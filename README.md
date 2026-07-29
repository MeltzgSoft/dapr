# Dapr

A Clojure desktop application for selectively syncing music libraries between
filesystems. A *library* is a **named, persistent collection of root
directories**, each addressed by a URI. Dapr supports three URI schemes:

- `file://` — local directories, mounted drives, NAS
- `smb://`  — network shares, via `jcifs-ng`; per-host credentials live in the
  OS secure keystore, never in the library config
- `mtp://`  — phones / DAPs / USB players, via
  [melt-jfs](https://github.com/meltzg/melt-jfs), a cross-platform `java.nio`
  FileSystem provider for MTP devices

The key design lever is that **every scheme is exposed as a
`java.nio.file.FileSystem` provider**, so the entire sync engine is written
against `java.nio.file.*` and never special-cases the backend. The only
scheme-specific code lives under `dapr.device.*` — device discovery,
availability probing, and (where a backend allows it) embedded-tag reading —
loaded so the default build, tests, and lint need neither native libraries nor
a live device.

## What it does

1. **Manage libraries** — create named libraries, each a set of
   `file://`/`smb://`/`mtp://` root directories (e.g. a phone's internal + SD
   storage). Libraries persist across sessions as EDN; unavailable libraries
   (device unplugged, share unreachable) are greyed out.
2. **Pick a source and a sink** library.
3. **Choose tracks** — the source's tracks are listed in a sortable table with
   an iTunes-style artist/album column browser; those already on the sink are
   pre-selected. Check/uncheck tracks. A **capacity meter** (free space across
   the sink's distinct devices, plus space reclaimed by deletions) blocks
   selecting more than the sink can hold.
4. **Preview & sync** — Dapr computes an **add / delete** plan that makes the
   sink hold exactly the selected tracks, then applies it.

Libraries are scanned by a **background refresher**, never by the UI: picking a
source or sink (or saving an edited library) paints its tracks from the cache
immediately (however large the library) and starts a fresh scan of it at the
front of the queue, with progress — and any failure, with its reason — shown in
the sync bar. A scan **yields its device** the moment a sync needs it,
then resumes where it left off, so a transfer never waits behind one. A refresh
that hasn't finished leaves the cached track list a possibly-stale *superset* of
what is on the device, so syncing before it completes asks for confirmation
first; **↻ Refresh** re-checks which devices are reachable and re-scans every
library.

The table shows each track's tags — disc/track number, title, duration,
artist, album, genre. Tags come from the file's **own embedded metadata** where
the backend supports reading it (`file://` via jaudiotagger, `mtp://` via the
device index or a ranged header read — see [`docs/mtp-tags.md`](docs/mtp-tags.md)),
falling back to path-derived values otherwise. A **dark/light/system theme** and
an on-demand **live log window** round out the UI.

Key behaviours (current defaults):

- **Track identity = relative path (from the library root) + size** — the root
  is deliberately excluded, so the same relative path matches across
  roots/devices (source `ROOT1/foo/bar.mp3` matches sink `SD/foo/bar.mp3`).
  Cheap (no content reads, important for MTP). A track already on the sink at
  that relative path is left in place regardless of which device holds it.
- **Add placement** — a new track keeps its source-relative subpath and is
  written under the first sink root (in library order) with room.
- **Track scope** — audio files only (`mp3 flac m4a aac ogg opus wav wma`).
- There is no separate *move* op: with relative-path identity, a file
  reorganised into a new relative path is simply a delete of the old path plus
  an add of the new one.

## Architecture

Pure business logic is isolated from side effects (effectful fns end in `!`):

```
src/dapr/
  domain/
    library.clj   pure  libraries, tracks, catalogs, identity, audio filter
    capacity.clj  pure  budget / used / would-fit? math
    plan.clj      pure  selection-plan -> [add/delete/add-to-source/skip/blocked]
    tags.clj      pure  path-derived artist/album/title fallback
  db/
    cache.clj      I/O   DataScript cache + EDN snapshot (system of record)
    migrations.clj I/O   named run-once DB migrations (see "Database migrations")
  device/          per-scheme behaviour, keyed on the root URI scheme
    format.clj    pure  scheme multimethods (device-type, labels, supported?)
    fs.clj        I/O   root-path!/dir-children!/available? multimethods
    coordinator.clj I/O per-device locks; user ops preempt the refresher
    tag.clj       I/O   tags! multimethod (embedded vs path-derived); default is path
    events.clj    I/O   folder-browser event multimethods (setup/connect/list)
    views.clj     pure  device-specific view extension points + shared browser
    file/ smb/ mtp/    each: fs, format, events, views (+ tag for file & mtp)
  fs/
    nio.clj        I/O   scan-roots! (resumable walk), copy!/delete!, capacity
    credentials.clj I/O  SMB per-host credentials in the OS secure keystore
    paths.clj      I/O   config-dir / user-home resolution
  library/
    store.clj      I/O   load!/save! libraries as EDN under the config dir
    catalogs.clj   I/O   paint state's catalogs from the cache (no device walk)
  refresh.clj      I/O   background refresher: resumable walks into the cache
  sync.clj         I/O   execute-plan! with progress callback + cache follow-up
  log.clj          I/O   Telemere handlers: rolling log file + live-window buffer
  state.clj        pure  state-transition fns over a single state map
  ui/
    format.clj     pure  formatting + derived predicates (no JavaFX)
    views.clj      pure  cljfx view descriptions (data)
    events.clj     I/O   event handlers: swap! state, copies, persistence
  system.clj       Integrant components: cache, state, log, devices,
                   coordinator, refresher, renderer
  main.clj         entry point
resources/{config.edn, dark.css, light.css}  Integrant system map + themes
```

Device discovery/tag reading is loaded through the multimethods above, so the
generic engine in `dapr.fs.nio` never names a scheme. `dapr.device.tag/tags!`
is the seam for tags: `file://` reads embedded tags via jaudiotagger, `mtp://`
via the melt-jfs `audio`/`mtp` NIO attribute views, and any scheme without a
reader (e.g. `smb://`) falls back to the path-derived default.

Libraries are persisted at `$XDG_CONFIG_HOME/dapr/libraries.edn` (fallback
`~/.config/dapr/…`, `%APPDATA%\dapr\…` on Windows). The scan cache and the system
of record for libraries live in a DataScript DB snapshotted to `…/dapr/cache.edn`
(`dapr.db.cache`).

### Database migrations

The cache DB evolves through **named, run-once migrations** in `dapr.db.migrations`.
Each is `{:migration/id <keyword> :migration/migrate <fn of conn>}`; the DB records
every applied migration (`:migration/id`), and on startup `run-migrations!` (called from the
`:dapr/cache` component) applies any whose id is **not yet recorded**, in registry
order, then snapshots. Migrations are keyed by name, **not a version number** — so
adding one never means picking "the next number," and two branches can each add a
migration without colliding.

To add one:

1. **Write the migrate fn** in `dapr.db.migrations` — a `!`-fn of the `conn` that
   makes its change by querying then transacting. Make it **idempotent /
   re-runnable**: it's recorded only after it returns, so one that throws is retried
   next startup.
2. **Append it to `registry`** with a fresh, descriptive `:migration/id` — a keyword,
   conventionally namespaced (e.g. `:migration/drop-blank-albums`). Vector order is
   the order migrations run.
3. **Add a test** in `dapr.db.migrations-test` (see the `marker-migration` helper for
   the framework, or drive the real `registry` end-to-end).

```clojure
;; dapr.db.migrations
(defn migrate-drop-blank-albums!
  "Retract :track/album entries stored as a blank string."
  [conn]
  (let [eids (d/q '[:find [?t ...] :where [?t :track/album ""]] (d/db conn))]
    (when (seq eids)
      (d/transact! conn (mapv (fn [t] [:db/retract t :track/album ""]) eids)))))

(def registry
  [;; …existing migrations…
   {:migration/id :migration/drop-blank-albums
    :migration/migrate migrate-drop-blank-albums!}]) ; append here
```

This is **data** migration only. It's separate from `dapr.db.cache/snapshot-version`,
which guards the on-disk EDN *shape*: bump that (not a migration) when the snapshot
format itself changes, and an older/unreadable snapshot is backed up and the DB
rebuilt from scratch. Inspect what has run with `applied-ids` / `applied`.

## Requirements

- JDK 21+ (developed on JDK 25). JavaFX is pulled in explicitly (`org.openjfx`
  `:linux` classifier) because modern JDKs no longer bundle it.
- For `mtp://` support: native MTP access (libmtp on Linux/macOS, WPD on
  Windows), reached via [melt-jfs](https://github.com/meltzg/melt-jfs).
- For `smb://` support: a secure keystore for per-host credentials — Secret
  Service/KWallet (Linux), Keychain (macOS), or Credential Manager (Windows).

## Usage

```bash
# Run the app
clojure -M:run

# Unit + hermetic tests
clojure -M:test

# Filesystem integration tests (NIO/Jimfs, SMB/Testcontainers, optional MTP hardware)
clojure -M:integration

# Lint and format
clojure -M:clj-kondo --lint src dev test test-integration
clojure -M:cljfmt check        # or: clojure -M:cljfmt fix

# Build a release uberjar for the host OS (target/dapr-<version>-<classifier>.jar)
clojure -T:build uber
```

### Where development happens

Source of truth is a self-hosted Forgejo forge; **GitHub is a read-only push
mirror**. Issues and pull requests live on the forge, and issues are disabled
on the mirror rather than left to look open for business.

CI runs on the forge (`.forgejo/workflows/`): lint, unit and integration on
Linux and Windows. macOS has no self-hosted runner, so that leg runs on GitHub
on every push to `main` (`.github/workflows/macos.yml`) and again as the
release gate — the workflows on GitHub are the release path plus macOS, not
everyday CI.

### Running a release jar

Release uberjars are published per OS by the `Release` workflow on each
`v#.#.#` tag (each jar carries its own platform's JavaFX natives). Run the jar
for your OS with native access enabled:

```bash
java --enable-native-access=ALL-UNNAMED -jar dapr-<version>-<classifier>.jar
```

The jar's manifest sets `Enable-Native-Access: ALL-UNNAMED`, so JDKs that honour
it run `java -jar dapr-....jar` without the flag; older JDKs still need it to
reach the native MTP/keystore code.

REPL-driven development (Integrant):

```clojure
clojure -M:dev
(require 'dev)
(dev/go)      ; start the window
(dev/reset)   ; reload changed code + restart
(dev/halt)    ; stop
```

## Roadmap

- Content-hash or tag-based track identity (beyond rel+size) — would re-enable
  efficient *move* detection for files reorganised into a new relative path
- Per-device bin-packing for adds (beyond first-fit placement)
- Embedded-tag reading over `smb://` (today its tags are path-derived)
