# Dapr

A Clojure desktop application for selectively syncing music libraries between
filesystems. A *library* is a **named, persistent collection of root
directories**, each addressed by a URI. Dapr supports two URI schemes:

- `file://` — local directories, mounted drives, NAS
- `mtp://`  — phones / DAPs / USB players, via
  [melt-jfs](https://github.com/meltzg/melt-jfs), a cross-platform `java.nio`
  FileSystem provider for MTP devices

The key design lever is that **both schemes are exposed as
`java.nio.file.FileSystem` providers**, so the entire sync engine is written
against `java.nio.file.*` and never special-cases the backend. The only
MTP-specific code is *device discovery* (`dapr.fs.mtp`), loaded lazily so the
default build, tests, and lint need neither the melt-jfs jar nor native
libraries.

## What it does

1. **Manage libraries** — create named libraries, each a set of `file://`/`mtp://`
   root directories (e.g. a phone's internal + SD storage). Libraries persist
   across sessions as EDN.
2. **Pick a source and a sink** library.
3. **Choose tracks** — the source's tracks are listed; those already on the sink
   are pre-selected. Check/uncheck tracks. A **capacity meter** (free space
   across the sink's distinct devices, plus space reclaimed by deletions) blocks
   selecting more than the sink can hold.
4. **Preview & sync** — Dapr computes an **add / delete** plan that makes the
   sink hold exactly the selected tracks, then applies it.

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
    plan.clj      pure  selection-plan -> [add/delete/skip/blocked]
  db/
    cache.clj      I/O   DataScript cache + EDN snapshot (system of record)
    migrations.clj I/O   named run-once DB migrations (see "Database migrations")
  fs/
    nio.clj       I/O   catalog!, copy!/delete!, capacity & device queries
    mtp.clj       I/O   MTP device discovery via melt-jfs (lazy, optional)
  library/
    store.clj     I/O   load!/save! libraries as EDN under the config dir
  sync.clj        I/O   build-plan! + execute-plan! with progress callback
  state.clj       pure  state-transition fns over a single state map
  ui/
    format.clj    pure  formatting + derived predicates (no JavaFX)
    views.clj     pure  cljfx view descriptions (data)
    events.clj    I/O   event handlers: swap! state, scans/copies, persistence
  system.clj      Integrant components: store, state atom, cljfx renderer
  main.clj        entry point
resources/config.edn  Integrant system map
```

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
- For `mtp://` support: the [melt-jfs](https://github.com/meltzg/melt-jfs) jar
  and native MTP access (libmtp on Linux/macOS, WPD on Windows).

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
- Virtualized track table for very large libraries
