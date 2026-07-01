# Dapr feature implementation plan

Source: `features.txt`. Each feature ships on its own branch off `main`. This doc
is the durable plan-of-record — it is expected to span several days/reboots.
Update the checkboxes and **Status** lines as work lands.

---

## ✅ CURRENT SESSION HANDOFF (feature 1 COMPLETE)

**Branch: `feat/sink-only-tracks` (feature 1) — DONE. Unit + integration green;
lint + cljfmt clean.**

### Branch/stack state
- `feat/app-settings` — ✅ done (off `main`).
- `feat/source-only-tracklist` — ✅ done (off `main`).
- `feat/library-availability` — ✅ done (stacked on `feat/source-only-tracklist`).
- `feat/sink-only-tracks` — ✅ **done**, stacked on `feat/library-availability`
  with `feat/app-settings` merged in (settings + source-only + availability).
- Merge order when landing: app-settings → source-only-tracklist → library-availability
  → sink-only-tracks.

### What landed this session (UI/events/format/tests — on top of the WIP planner+sync)
- `ui/events.clj`: `run-preview!` reads `:sink-only-handling` from settings and
  passes it + `:source-roots` (only computed for `:add-to-source`) to both the
  `plan/selection-plan` call and the `sync/build-plan!` fallback. `run-sync!`
  destructures `sink-catalog`, calls `sync/apply-source-adds-to-cache!` after
  `apply-plan-to-cache!`, and logs the `:add-to-source` count.
- `ui/format.clj`: `can-sync?` counts `:add-to-source`; `plan-summary-text` appends
  `· To source N (bytes)` when positive.
- `ui/views.clj`: `track-rows` now rows the **union** of source+sink catalogs with
  `:in-source?`; sink-only rows lock on (`:on? true`/`:disable true`) under
  `:keep`/`:add-to-source`. New `track-column` helper (`:cell-value-factory
  identity` + per-field `:comparator` + red `:style` for `:in-source? false`)
  replaces `text-column`/`size-column`. Settings modal gains a `sink-only-options`
  radio group (Keep / Delete / Copy-back) bound to `:sink-only-handling`.
- Tests: `plan_test` (`selection-plan-test` keep-by-default, `summary-test` with
  `:delete`, new `sink-only-handling-test`), `sync_test`
  (`apply-source-adds-to-cache-test`), `format_test` (add-to-source `can-sync?` +
  `plan-summary-text` cases), and `sync_integration_test` (op-count map shape +
  new end-to-end `sink-only-add-to-source-test` proving the real file copy-back).

### Gotchas learned
- The DEFAULT behavior CHANGED: previously every unselected sink track was deleted;
  now sink-only tracks default to `:keep`. This rippled into `plan_test` AND
  `sync_integration_test` (which now pass `:sink-only-handling :delete` to keep
  exercising the delete path). `execute-plan!`'s result map now always carries
  `:add-to-source` (so `{:add 0 :delete 0}` → `{:add 0 :add-to-source 0 :delete 0}`).
- Clojure fns implement `java.util.Comparator`, so a 2-arg fn works as a cljfx
  `:comparator`. Using `:cell-value-factory identity` lets a cell colour itself by
  the whole row while `:comparator` preserves per-field sorting.
- The integration suite shares a classpath with several **untracked** WIP tag files
  (`src/dapr/fs/tags.clj` etc.) that don't yet compile (`No such var: tags/clean`);
  they break `clojure -M:integration` until that separate feature lands. They are
  unrelated to this branch — set them aside to get a clean integration run.
- `docs/feature-plan.md` is otherwise kept untracked to follow across branches, but
  it is committed on `feat/sink-only-tracks`.

### Next up: `feat/theming` (6) ✅ DONE. `feat/logging` (2) **base DONE** but has
**two open follow-ups** — reverted the live-log tail-follow/jump-to-bottom UX (wrong
path) and, with it, a real `append-log` memory-leak fix. Both are documented in the
feature-2 block below (follow-up A = re-apply the leak fix; follow-up B = rethink the
scroll UX). Then remaining order: `feat/shift-select` (8) → `ci/release-uberjar` (9).
Spikes (3, 4) anytime. (See per-feature blocks below.)

**Branch/commit state for continuing elsewhere:** on `feat/logging` at `f806bdb`
(the 4 commits after it — `26a32e7`, `52c8191`, `a59ba6a`, `102f77d` — were
reverted; `origin/feat/logging` still has them + the reverts). Pull the branch,
`clojure -M:test` should be green (75 tests). Start with follow-up A.

---

## Decisions locked in
- **Feature 1 (`:add-to-source`)**: actually **copy the file back** into the
  source library (a real file write), not just register a cache presence.
- **Feature 2 (logging lib)**: use **Telemere** (`com.taoensso/telemere`) —
  Clojure-native, with a built-in in-memory signal handler that makes the live
  log window cheap.
- **Feature 9 (release)**: build a **per-OS matrix** of uberjars (Linux / macOS /
  Windows), each with the matching JavaFX classifier.
- **Feature 4 (MTP tags)**: we own the **melt-jfs** source
  (`io.github.meltzg/melt-jfs`), so the spike may propose changes to that lib
  (e.g. surfacing MTP object metadata) rather than reading file bytes.

## Architecture recap (so a cold reboot has context)
Clojure desktop app. Pure logic isolated from side effects (effectful fns end `!`).
- `domain/{library,capacity,plan,tags}.clj` — pure.
- `fs/nio.clj` — NIO catalog/copy/delete/capacity (all providers).
- `cache.clj` — DataScript DB persisted to `cache.edn`; **system of record** for
  libraries + per-library `default-source?/default-sink?`. Snapshotted atomically.
- `state.clj` — pure `state -> state` transitions over one state map.
- `ui/{format,views,events}.clj` — cljfx views (data) + side-effecting handlers.
- `device/{file,smb,mtp}/*` + `device/tag.clj` — per-device multimethods;
  `device.tag/tags!` is the seam for embedded vs path-derived tags.
- `system.clj` / `main.clj` — Integrant wiring + entry point.
- Track identity = `[rel size]` (root excluded). Catalogs are `key -> track`.

---

## Shared foundation: persisted app settings
**Branch: `feat/app-settings` (merge FIRST).**

Features 1, 2, 6 need global persisted settings; the app has none yet (the cache
DB only persists libraries + default flags).

- [x] `cache.clj`: `app-settings`/`app-setting`/`set-app-setting!` over a singleton
      `:app/settings` **map-valued** entity — no schema entry, no
      `snapshot-version` bump, so old snapshots keep working (the attr just doesn't
      exist until first write).
- [x] `state.clj`: `:settings {}` in `initial-state` + `set-settings`/`set-setting`/
      `setting` transitions.
- [x] `system.clj` (`ig/init-key :dapr/state`): loads settings into `:settings`.
- [x] `events.clj`: generic `::set-setting {:key :value}` seam (swap state +
      persist + snapshot) so each feature branch only adds its control, not
      persistence plumbing.
- [x] Tests: `cache_test` (set/update/clear/snapshot round-trip) + `state_test`
      (transitions). Unit + integration green; lint + cljfmt clean.
- [ ] Settings modal panel host deferred to the first feature that adds a
      user-facing control (no dead UI in the foundation).

**Status:** implemented & committed on `feat/app-settings`, pushed.

---

## 1. `feat/sink-only-tracks` — tracks on sink but not source
Today `track-rows` only iterates `source-catalog`, so sink-only tracks are
invisible; `selection-plan` silently deletes any unselected sink track.

- [x] **Setting** `:sink-only-handling` ∈ `{:keep :delete :add-to-source}`,
      **default `:keep`**. Wired in the planner and surfaced as a settings radio.
- [x] `ui/views.clj` `track-rows`: rows the **union** of source+sink keys; flags
      sink-only rows (`:in-source? false`).
- [x] Render sink-only rows **red** via a `track-column` helper.
- [x] `check-column`: for `:keep`/`:add-to-source`, sink-only rows force `:on? true`
      + `:disable true` (computed in `track-rows`).
- [x] `:add-to-source`: planner emits `:add-to-source`; `sync/execute-plan!` does a
      real **file copy sink-root → source-root**, and
      `sync/apply-source-adds-to-cache!` adds a presence on the source library.
- [x] `domain/plan.clj`: sink-only handling branches (keep→retain, delete→delete,
      add-to-source→copy + retain).
- [x] Settings panel `sink-only-options` radio group, dispatching `::set-setting`.

**Notes:** sequence AFTER feature 5 (both touch `track-rows`/catalog union).
**Status:** ✅ **DONE** on `feat/sink-only-tracks` — planner + sync + UI/events/
format complete; unit + integration green, lint + cljfmt clean.

---

## 2. `feat/logging` — Telemere logging, file output, live view
- [x] **deps.edn:** added `com.taoensso/telemere {:mvn/version "1.2.1"}`.
- [x] New `src/dapr/log.clj`:
  - file handler (`t/handler:file`) under the `:log-dir` setting, default
    `System/getProperty "java.io.tmpdir"`.
  - **increment-on-startup:** `next-log-file` picks `dapr.N.log` (smallest free N
    from 0), creating the dir; never overwrites an existing log.
  - **UI handler** mirrors each signal into the app state's activity log
    (`state/append-log`) so the live log window renders reactively — the ring
    buffer is the existing capped `:log` vector. Min level pinned to `:info`.
- [x] **Removed** the always-on activity log: `activity-pane` + the `workspace`
      split are gone; the **only** `append-log` caller is now the Telemere UI
      handler. All business call sites (events / device events / smb events,
      `scan-logger`) emit `t/log!` signals instead (per-dir `:info`, per-file
      `:debug` so the file isn't flooded — the progress bar covers file
      granularity). Errors log with the throwable attached, so the stack trace
      lands in the file (replaces the old `error-detail` string append).
- [x] `ui/views.clj`: a **View ▸ View Logs…** menu opens an on-demand live log
      window (`log-window`, driven by `:log-open?`, themed); Settings shows the
      **current log file** + a "Change log folder…" picker (`DirectoryChooser` →
      `::choose-log-dir` persists `:log-dir` and repoints the file via
      `log/set-dir!`).
- [x] Logging configured by a new `:dapr/log` Integrant component (depends on
      cache+state; the renderer depends on it, so it inits **before** scans run).
- [x] Tests: `log_test` (`log-dir`/`next-log-file`/`signal->line`), `state_test`
      (`open-log`/`close-log`/`set-log-file`). Unit green; lint + cljfmt clean.
      Verified the Telemere→state→file path end-to-end via a REPL smoke.

**Setting:** `:log-dir` (nil = tmp). **Status:** ✅ **base DONE** on `feat/logging`
(commit `f806bdb`, stacked on `feat/theming`). The live log window currently
**always pins to the tail** (`:scroll-top (* log-appends 1.0e7)`). Two follow-ups
below are **OPEN** — a UX enhancement that was attempted and **reverted** (wrong
path, see below), and an independent memory-leak fix that got reverted with it.

### OPEN follow-up A — re-apply the `append-log` leak fix (do this first; it's real)
`state/append-log` trims the capped `:log` with `subvec`. A `subvec` **retains its
backing vector**, and `conj`-ing onto a subvec keeps growing that backing — so
every line ever appended stays on the heap (confirmed: 50k appends → 500 shown, 50k
retained). The rising GC pressure degrades the **whole** UI the longer logging runs.
- **Fix:** materialize the trimmed window: `(into [] (subvec log (- n max-log-lines)))`.
  Note `vec` does **not** work here — a `SubVector` is `vector?` but not editable, so
  `vec` returns it unchanged; `into []` actually copies into a fresh `PersistentVector`.
- Add a regression assertion: the trimmed `:log` is **not** a
  `clojure.lang.APersistentVector$SubVector`.
- This is independent of follow-up B — land it on its own.

### OPEN follow-up B — "follow tail unless scrolled up" + jump-to-bottom (REVERTED — rethink)
Goal: the log window auto-scrolls to the newest line, but if the user scrolls up to
read scrollback it **freezes** there (streaming lines don't yank it), with a "⤓ Jump
to bottom" button to re-engage. Several attempts (commits `26a32e7`/`52c8191`/
`a59ba6a`) were **reverted** — the approach fought JavaFX/cljfx too hard. **Don't
just retry the same thing.** Hard-won constraints to respect next time:
- **cljfx has no `scrollTop`-changed prop for `:text-area`** (only `:on-scroll`, a
  wheel-only `ScrollEvent` that the skin can consume and that misses scrollbar
  drags). Verified against the cljfx 1.10.8 `text-area` prop map.
- **`TextArea.setText` resets `scrollTop` to ~0.** cljfx replaces the whole text on
  every append, so any `scrollTop` listener fires with ~0 each line → naive "scrollTop
  dropped ⇒ user scrolled up" logic freezes on **every** line. Must ignore near-zero.
- **Scrollbar-based "at bottom?" has a timing skew:** `scrollTop` updates and fires
  its listener *before* the vertical scrollbar's value catches up, so reading the
  scrollbar in that listener sees the stale (still-max) value and never freezes. If
  detecting at all, use `scrollTop` **alone** (delta vs. a tracked bottom baseline).
- **The pin fights the user mid-stream:** the `:scroll-top` pin re-pins to bottom on
  every append render, so a freeze must take effect *synchronously* or the next line
  yanks the view back down.
- **Frozen view needs a snapshot:** render a *frozen copy* of the log (not the live
  growing `:log`) so appends don't move/regrow it, and keep providing `:scroll-top`
  (removing the prop resets to top).
- **Escape hatch:** a raw `scrollTop` `ChangeListener` attached via
  `fx/ext-on-instance-lifecycle` `:on-created` (look up `".text-area"`) works, but the
  pure view fn has no handle to the live `state-atom`, so it needs a module-level
  bridge atom (one is enough; attaching from `system.clj` avoids the global but the
  log `Stage` isn't in `Window/getWindows` until first shown).
- **Suggested rethink:** rather than fighting a wholesale-replaced `TextArea`, consider
  (a) a **"Pause" toggle** instead of scroll-detection (dead simple, no listeners), or
  (b) a **virtualized `ListView`** of log lines (better for large logs; scroll/selection
  state is far more controllable than a `TextArea`), or (c) incremental `appendText`
  with an explicit "was at bottom?" check before appending. Decide the approach before
  coding.

Also worth noting (perf, not size-dependent): every log line swaps the shared state
atom → full `root-view` re-render. Fine for now; if it bites, gate the log-string
build behind `:log-open?` or coalesce append bursts.

---

## 3. `spike/smb-tags` — investigate SMB tag reading *(research)*
Deliverable: `docs/smb-tags.md` (+ optional prototype). `jaudiotagger` reads only
`java.io.File` (local default FS); smb:// currently falls back to path-derived
tags via `device.tag`.
- [ ] Evaluate: (a) NIO-stream to a temp file then read (simple, full read per
      file); (b) jaudiotagger over a seekable channel/`RandomAccessFile`
      (likely unsupported — jcifs `SmbFile` isn't a `File`); (c) read only the
      ID3/Vorbis header bytes via NIO channel and parse minimally.
- [ ] Recommend an approach + cost (SMB reads are the expensive path).
- [ ] If viable, follow-up `feat/smb-tags` registers a `device.tag/tags!` method.

**Status:** not started

---

## 4. `spike/mtp-tags` — investigate MTP tag reading *(research)*
Deliverable: `docs/mtp-tags.md`. MTP exposes metadata natively (object properties:
Artist / AlbumName / Name) — potentially cheap vs. reading file bytes.
- [ ] Check whether **melt-jfs** already surfaces MTP object properties; **we own
      that source**, so propose lib changes to expose them if not.
- [ ] If available, a `device.tag/tags!` method for mtp:// reads tags from device
      metadata directly (no byte read). Shared seam with #3 (`device.tag`).

**Status:** not started

---

## 5. `feat/source-only-tracklist` — show source tracks with no sink selected
- [x] `ui/events.clj` `reload-catalogs!`: gate relaxed from `(and src snk)` to
      `(when src ...)`; sink scan + free-space query are skipped when there's no
      sink (empty sink catalog, 0 free). `start!` fires on a default source alone.
      `load-cached-catalogs!` logs a "(no sink)" variant.
- [x] **Decision:** selection is **disabled until a sink exists** — with 0 free,
      `cap/row-fits?` refuses every track, so all checkboxes render disabled. No
      pre-selection (empty sink catalog). This falls out of existing capacity math;
      no `toggle-track` change needed.
- [x] `ui/views.clj` `capacity-bar`: no sink → shows "Select a sink" prompt
      instead of a misleading `0 B / 0 B`. `sink-rel` nil already handled.
- [x] Preview/Sync stay disabled (already gated by `can-preview?`/`can-sync?`).
- [x] Test: `state_test` source-only contract (empty selection, zero capacity,
      selection refused). Unit + integration green; lint + cljfmt clean.

**Notes:** do BEFORE feature 1 (overlapping `track-rows`/`reload-catalogs!`).
**Status:** implemented on `feat/source-only-tracklist`, committed.

---

## 6. `feat/theming` — dark / light / system
- [x] `resources/dark.css` + `resources/light.css` (style JavaFX controls via
      Modena's `-fx-base`/`-fx-background`/`-fx-control-inner-background` plus
      explicit table/list/text rules). On the classpath (`:paths` has `resources`).
- [x] **Setting** `:theme` ∈ `{:dark :light :system}` (default `:system`), persisted
      via the existing `::set-setting` seam.
- [x] `ui/views.clj`: `theme-stylesheets` adds `:stylesheets` to each `:scene` (main
      + settings) from the active theme; `fmt/active-theme` (pure) resolves
      setting+OS → `:dark`/`:light`. Memoized resource→external-form lookup.
- [x] **System detection:** `system.clj` `watch-os-color-scheme!` reads
      `Platform.getPreferences().getColorScheme()` on the FX thread and adds a
      `colorSchemeProperty` listener that swaps `state/:os-color-scheme`; the
      renderer re-renders on the state change. Best-effort (try/catch → nil ⇒
      `:system` falls back to light) so it degrades on platforms lacking the API.
- [x] Settings UI: `theme-options` radio group (System / Light / Dark).
- [x] Tests: `state_test` (`set-os-color-scheme`), `format_test` (`active-theme`).
      Unit green; lint + cljfmt clean.

**Notes:** stacked on `feat/sink-only-tracks` (both edit `views.clj` scenes + the
settings modal). **Status:** ✅ **DONE** on `feat/theming`.

---

## 7. `feat/library-availability` — grey out unavailable libraries
- [x] `dapr.device.fs/available?` multimethod (never throws): file:// → root is an
      existing dir; smb:// → resolve opens the authenticated FS, catch → false;
      mtp:// → open the device FS, catch Throwable → false.
- [x] Probe **async** off the JFX thread (`events/probe-availability!`); a library
      is available when **all** its roots are. Cached in
      `state/:library-availability {id -> bool}`. Probed on launch, on the manual
      Refresh button, and after a library add/delete — **not per-frame**.
- [x] `ui/views.clj` `library-combo`: `:cell-factory` greys + disables unavailable
      entries (a disabled list cell isn't selectable). Added a "↻ Refresh" button
      to the sync bar. Pure predicate `fmt/library-unavailable?` (unprobed =
      treated available, so no all-grey flash before the first probe).
- [x] `events/start!` + `state/clear-unavailable-selection`: a persisted default on
      an unreachable device is **dropped** (not pre-selected) after the launch
      probe; Refresh also clears a now-unavailable selection. Reuses the
      source-only reload from feature 5.
- [x] Tests: `device/fs_test` (file available/missing/unsupported), `state_test`
      (set + clear-unavailable), `format_test` (predicate). Unit + integration
      green; lint + cljfmt clean.

**Notes:** **stacked on `feat/source-only-tracklist`** (both edit
`start!`/`reload-catalogs!`) — merge 5 before 7.
**Status:** implemented on `feat/library-availability`, committed.

---

## 8. `feat/shift-select` — range-select tracks
Selection is via the custom checkbox column, not the table's selection model, so
shift needs the modifier from a mouse event (`check-box`'s `on-selected-changed`
carries no modifiers).
- [ ] `state.clj`: `:select-anchor` (last toggled row index) + `select-range`
      transition.
- [ ] `ui/views.clj` `track-rows`: include each row's index; `check-column` cell
      adds `:on-mouse-clicked` reading `MouseEvent#isShiftDown` + row index,
      dispatching `::toggle-range` when shift held (else `::toggle-track`, which
      sets the anchor).
- [ ] `ui/events.clj`: handle range toggle, honoring per-track capacity
      (`cap/row-fits?`) for adds.

**Status:** not started

---

## 9. `ci/release-uberjar` — tagged release builds per-OS uberjars
- [ ] **deps.edn:** add a `:build` alias (`io.github.clojure/tools.build`) +
      `build.clj` with an `uber` fn (main = `dapr.main`, AOT main). Take the
      **git tag as the version** (strip leading `v` from `v#.#.#`). Allow
      selecting the JavaFX classifier per OS.
- [ ] **`.github/workflows/release.yml`:** trigger on `push: tags: ['v*.*.*']`;
      **matrix** over `{ubuntu-latest, macos-latest, windows-latest}` building the
      OS-matching JavaFX-classifier uberjar.
- [ ] Create the GitHub release and attach each OS jar
      (`softprops/action-gh-release` or `gh release create`).
- [ ] Document the `--enable-native-access=ALL-UNNAMED` runtime flag for the jar.

**Notes:** JavaFX is per-OS classifier (deps.edn currently pins `:linux`); the
build must parameterize this per matrix leg. **Status:** not started

---

## Suggested order
`feat/app-settings` → `feat/source-only-tracklist` (5) →
`feat/library-availability` (7) → `feat/sink-only-tracks` (1) →
`feat/theming` (6) → `feat/logging` (2) → `feat/shift-select` (8) →
`ci/release-uberjar` (9). Spikes (3, 4) run anytime in parallel.

Rationale: front-load shared settings infra; 5→7→1 touch overlapping
`track-rows`/`reload-catalogs!` code, so doing them in sequence avoids repeated
merges.

## Per-branch checklist (apply to every feature)
- [ ] Branch off latest `main` (rebase on `feat/app-settings` if it's a consumer).
- [ ] `clojure -M:test` green.
- [ ] `clojure -M:integration` green (SMB via Testcontainers needs Docker; MTP
      skips without hardware — matches CI's `integration.yml`).
- [ ] `clojure -M:clj-kondo --lint src dev test test-integration` clean.
- [ ] `clojure -M:cljfmt check` clean.
- [ ] Manual smoke via `clojure -M:run` (or REPL `dev/go`).
