# Dapr feature implementation plan

Source: `features.txt`. Each feature ships on its own branch off `main`. This doc
is the durable plan-of-record — it is expected to span several days/reboots.
Update the checkboxes and **Status** lines as work lands.

---

## Current status

**All nine planned features are done and merged to `main`.** Both research
spikes have since been promoted to real features:

- **Spike 4 — MTP tags → shipped** on `spike/mtp-tags`
  ([PR #33](https://github.com/meltzg/dapr/pull/33)). `dapr.device.mtp.tag`
  reads embedded tags over MTP with no whole-file transfer — the melt-jfs 0.2.0
  `audio` view (a ranged header read) preferred over the `mtp` device index,
  both over path fallback. The full tag set (title/artist/album/genre/track/
  disc/duration) threads through reader → cache → the sortable table columns;
  two run-once migrations (`:migration/mtp-tag-sources`,
  `:migration/extended-tag-fields`) backfill existing caches. Verified on
  hardware (iRiver AK100_II, ~24 ms/track). See `docs/mtp-tags.md`.
- **Spike 3 — SMB tags → shipped** on `spike/smb-tags`
  ([PR #37](https://github.com/meltzg/dapr/pull/37)). `dapr.device.smb.tag` reads
  embedded tags over SMB via melt-jfs's ranged-read header parsers (no whole-file
  transfer), mapping the full field set with per-field path fallback; a
  `:migration/smb-tag-sources` migration backfills existing caches. See
  `docs/smb-tags.md`.

The detailed per-feature and per-spike records below are the durable build log,
kept for context; the merge mechanics for the original stacked PRs (#22–#28) are
historical — that stack has long since landed on `main`.

**Feature 10 — `feat/background-refresh`** (resumable background library refresh
with foreground device priority) is **built** on `feat/background-refresh` (§10):
all 8 phases, unit + integration green, pending a manual smoke on real hardware.

**Feature 11 — `feat/parallel-refresh`** (scan independent devices at once) is
**sketched** in §11 and deliberately not started: it should follow feature 10's
hardware smoke, since it changes the same device-arbitration primitives.

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
- **Feature 10 (background refresh)**: (a) **checkpoint-and-continue** resume
  (save the remaining directory frontier + `seen` set, not restart-from-scratch —
  restart re-lists every directory, the expensive MTP cost); (b) **cache-only
  Preview** so the only foreground device op is the sync copy/delete; (c) the
  **`file` pseudo-device is not coordinated** (local disk is parallel-safe and all
  local libraries share one device-key); (d) the **sync-confirmation gate fires
  when source OR sink is not `:complete` this session**. All four held up in the
  build; (c) generalized into the `device/arbitrate-access?` multimethod, so each
  device type declares whether it needs arbitrating rather than the coordinator
  keeping a list (see §10 "Deltas from the design").

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

**Setting:** `:log-dir` (nil = tmp). **Status:** ✅ **DONE** on `feat/logging`
(base at `f806bdb`, stacked on `feat/theming`; both follow-ups now landed —
A `0bf0690`, B `28d45e5`). The live log window follows the tail on a **ListView**,
freezes when the user scrolls up, and offers a ⤓ jump-to-bottom button.

### ✅ DONE follow-up A — re-applied the `append-log` leak fix (commit `0bf0690`)
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

### ✅ DONE follow-up B — "follow tail unless scrolled up" + jump-to-bottom (commit `28d45e5`)
Goal: the log window auto-scrolls to the newest line, but if the user scrolls up to
read scrollback it **freezes** there (streaming lines don't yank it), with a "⤓ Jump
to bottom" button to re-engage.

**How it was finally done — a virtualized `ListView`, NOT the TextArea.** The three
reverted attempts (`26a32e7`/`52c8191`/`a59ba6a`) all fought a wholesale-replaced
`TextArea`; the rethink switched widgets, which dissolves most of the constraints
below. Key facts that made ListView work (all verified against cljfx 1.10.8 source):
- cljfx sets `:items` via `mutator/observable-list`, i.e. **`.setAll` into the
  ListView's own stable items list** (not a new list each render). So a **single
  `ListChangeListener` attached once fires on every appended line** — that's the
  tail-follow hook (re-`.scrollTo(last)` while following).
- **`ext-on-instance-lifecycle` `:on-advanced` only fires when the instance is
  *replaced*** (`not= old new`); cljfx reuses the ListView node across appends, so
  on-advanced is useless for per-line work — hence the items ListChangeListener.
- **A ListView keeps its scroll position when items append** (the whole reason it
  beats TextArea, whose `setText` reset scrollTop to 0). So a frozen view just stays
  put — no pixel-position capture/restore needed.
- **Freeze detect = the vertical scrollbar's `valueProperty`** (0..1), listened to
  directly (catches wheel AND drag, no skew — we're not reading it from inside a
  scrollTop listener). Wired lazily once the scrollbar first exists (guarded by a
  `::log-scroll-wired` flag on the node's properties, re-checked from the items
  listener). `state/log-scrolled` unfollows only on a downward move past a 0.02
  epsilon, so the programmatic pin (which raises the value) never trips it.
- **One module-level bridge** (`events/log-state` + `events/on-log-scroll!`, over a
  `state-atom*` set in `make-handler`) lets the raw listeners read/update state.

Old TextArea-specific constraints, kept for the record (moot now we use ListView):
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
Deliverable: `docs/smb-tags.md` (+ prototype). `jaudiotagger` reads only
`java.io.File` (local default FS); smb:// previously fell back to path-derived
tags via `device.tag`.
- [x] Evaluate: (a) NIO-copy to a temp file then read (**whole-file transfer per
      track — untenable at library scale**); (b) jaudiotagger over a seekable
      channel (**ruled out** — jaudiotagger 3.0.1 has no channel/stream API);
      (c) **ranged header parse — SHIPPED.** smb-nio's `SeekableByteChannel`
      honours `position()`, and melt-jfs 0.2.0 ships device-agnostic header
      parsers (`org.meltzg.fs.mtp.audio.AudioTagReaders` over a `RangedByteSource`,
      zero MTP coupling) — which we own — so ranged parsing needed no custom code.
- [x] Recommend an approach + cost: ship (c). A first scan reads only a few KB of
      header per track (vs tens of MB for a whole FLAC under (a)); aac/wma and any
      failure degrade to path tags. See `docs/smb-tags.md`.
- [x] `dapr.device.smb.tag` registers `tags! :smb` — `channel-source` adapts an
      smb-nio `SeekableByteChannel` to melt-jfs's `RangedByteSource`,
      `audio-tags->tags` maps `AudioTags` → dapr's **full tag set**
      (artist/album/title/genre/track/disc/duration, matching the file:// and
      mtp:// readers) with per-field path fallback; loaded in `fs/nio`.
- [x] Cache migration `:migration/smb-tag-sources` retracts `:track/tag-source`
      from `:path`-cached tracks under `smb://` roots so the next scan re-reads
      them through the new reader. Runs once via the applied-id gate.

**Status:** ✅ **DONE** on `spike/smb-tags`
([PR #37](https://github.com/meltzg/dapr/pull/37)), rebased onto the merged
mtp-tags work. Reads real embedded tags over ranged reads with **no whole-file
transfer**; the full field set threads through to the table columns. Unit +
integration green; clj-kondo + cljfmt clean.

---

## 4. `spike/mtp-tags` — investigate MTP tag reading *(research)*
Deliverable: `docs/mtp-tags.md`. MTP exposes metadata natively (object properties:
Artist / AlbumName / Name) — potentially cheap vs. reading file bytes.
- [x] Check whether **melt-jfs** already surfaces MTP object properties; **we own
      that source**, so propose lib changes to expose them if not. → It did NOT
      (0.1.1: filesystem facts only). Implemented on melt-jfs
      **`feat/track-metadata`**
      ([PR #9](https://github.com/MeltzgSoft/melt-jfs/pull/9), `0c1d9bd`,
      onto `master`): `MtpBackend.getTrackMetadata` for both
      libmtp (FFM `LIBMTP_Get_Trackmetadata`) and WPD backends, surfaced as a
      new NIO **"mtp" attribute view** (`Files.readAttributes(path,
      "mtp:title,artist,album")`) — so the dapr side stays pure java.nio.
- [x] `device.tag/tags!` method for mtp:// reads tags from device metadata
      directly (no byte read) — `dapr.device.mtp.tag` on `spike/mtp-tags`,
      merged per-field over path fallbacks, `:source :embedded`. Safe with
      melt-jfs 0.1.1 on the classpath (no "mtp" view → catches
      UnsupportedOperationException → path tags), lights up on the deps bump.

**Status:** ✅ **DONE and promoted to a shipped feature** on `spike/mtp-tags`
([PR #33](https://github.com/meltzg/dapr/pull/33)). Beyond the original spike:
melt-jfs bumped to 0.2.0 (adds the `audio` view — embedded tags via
`GetPartialObject` ranged reads, preferred over the `mtp` index); the full tag
set (genre/track/disc/duration alongside artist/album/title) threads through the
reader, cache, and the sortable table; run-once migrations backfill existing
caches; and it is **hardware-verified** (iRiver AK100_II, ~24 ms/track). See
`docs/mtp-tags.md` for the cost analysis (metadata-only ≈ 100× cheaper than
whole-object reads), the audio/mtp view asymmetry, and remaining follow-ups
(melt-jfs `sendFile` FILETYPE_UNKNOWN inference).

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

## 8. `feat/facet-toggle` — double-click an artist/album facet to (de)select its tracks
**Scope changed from the original `feat/shift-select`.** Shift-click range-select was
built then **removed** at the user's request in favour of a simpler group toggle:
double-clicking a column-browser Artist or Album facet checks every track under it (or
unchecks them all if already selected). Renamed the branch to `feat/facet-toggle`.
- [x] `state.clj`: `toggle-keys` — batch group toggle (all-on ⇒ deselect all, else
      select those that fit the sink budget per-track via `cap/would-fit?`);
      `track-locked?` — a sink-only `:keep`/`:add-to-source` track is dropped from the
      group so it stays locked-on.
- [x] `ui/events.clj`: `facet-toggle!` reads `MouseEvent#getClickCount` (double only)
      and the list's selected item, resolves matching keys via `fmt/filter-catalog`
      over the **union** catalog (an album scoped to the active artist filter so
      same-named albums don't collide), then `state/toggle-keys`.
- [x] `ui/views.clj`: `filter-column` gains a `toggle-event` → `:on-mouse-clicked` on
      the facet list + a discoverability tooltip; wired for both columns. Single-click
      still selects/filters.
- [x] Tests: `state_test` (`track-locked?`, `toggle-keys`: select / deselect-when-all
      / budget-skip / locked-untouched). 78 tests green; lint + cljfmt clean.

**Design notes:**
- **Filtering is click-driven, not selection-model-driven, and single-click is
  deferred** (`4aa1434` → `ff77835`). Originally `:on-selected-item-changed` set the
  filter, so a double-click's *first* click narrowed the view before the toggle.
  First fix (`4aa1434`) moved filtering onto `:on-mouse-clicked` and *restored* the
  filter on the second click — but that still flashed the view narrowed between the
  two clicks. Final fix (`ff77835`) disambiguates single vs double the only way you
  can: a single click schedules its filter via a 250ms `PauseTransition`
  (`facet-single-click-millis`); a second click cancels the pending filter and toggles
  the group, so a double-click **never** applies the filter (no flash). One
  module-level `pending-facet-click*` atom holds the transition; any new click
  supersedes it. Trade-offs: single-click filtering lands ~250ms after the click (the
  double-click window), and keyboard arrow-key facet navigation no longer filters (the
  browser is mouse-driven anyway).
- `track-locked?` guard matters: without it, deselecting a locked sink-only track from
  `:selected` would skew the capacity `:used` even though `track-rows` forces it
  visually on. The whole toggle stays in the pure layer (testable), the events layer
  only extracts the JavaFX bits.

**Status:** ✅ **DONE** on `feat/facet-toggle` (stacked on `feat/logging`; `b4729fe`
feature + filter fixes through `8f7e216`, tip). 78 tests green; clj-kondo + cljfmt
clean; no reflection warnings. **Wants a manual smoke:** single-click an artist → view
filters after a ~250ms beat; double-click an artist → all its tracks check and the view
does **not** narrow; double-click again → they uncheck; double-click when the sink is
nearly full → only the tracks that fit get checked; the "All" entry and a red sink-only
album under `:keep` are left alone.

---

## 9. `ci/release-uberjar` — tagged release builds per-OS uberjars
- [x] **deps.edn:** added a `:build` alias (`io.github.clojure/tools.build`) +
      `build.clj` with an `uber` fn (main = `dapr.main`, AOT main). Version comes
      from `DAPR_VERSION`/git tag, stripping the leading `v` from `v#.#.#`. The
      JavaFX classifier is auto-detected or passed via `:javafx-classifier`.
- [x] **`.github/workflows/release.yml`:** triggers on `push: tags: ['v*.*.*']`;
      **matrix** over `{ubuntu-latest, macos-latest, windows-latest}` building the
      OS-matching JavaFX-classifier uberjar (linux / mac-aarch64 / win).
- [x] Creates the GitHub release and attaches each OS jar
      (`softprops/action-gh-release`; each matrix leg appends its jar).
- [x] Documented the `--enable-native-access=ALL-UNNAMED` runtime flag (README);
      the jar manifest also sets `Enable-Native-Access: ALL-UNNAMED`.

**Notes:** JavaFX is per-OS classifier (deps.edn pins `:linux` for local dev);
`build.clj` rewrites it per target OS rather than parameterizing deps.edn, so
`:run`/`:dev`/`:test` are untouched. **Status:** done — verified the linux jar
builds, carries linux natives, and has the right Main-Class/manifest.

---

## 10. `feat/background-refresh` — resumable background refresh + foreground device priority
**✅ DONE** on `feat/background-refresh` — all 8 phases built; unit (114 tests) +
integration (23 tests) green, clj-kondo + cljfmt clean. **Wants a manual smoke**
(see Verification below).

**Why.** Library refreshes are slow — over MTP/SMB a single directory listing is a
blocking native round-trip. Today the app scans on startup and on every
source/sink change (`events/reload-catalogs!`), and device access is **serial**
(melt-jfs per-MTP-device; SMB FileSystem creation) with **no cooperative release**,
so a user sync blocks behind an in-flight scan of the same device. Goal: one owner
of all device scanning (a background refresher) that pauses/releases a device the
moment a user op needs it, a UI that always reads the in-memory cache, and a sync
that warns when it would run against an unfinished refresh.

**Locked decisions** (see "Decisions locked in" §Feature 10): checkpoint-and-continue
resume · cache-only Preview · `file` pseudo-device uncoordinated · confirm sync when
source/sink not `:complete` this session.

**Design summary.**
- **Coordinator** `src/dapr/device/coordinator.clj` (new): per-device-key **fair
  `ReentrantLock`** (memoized in an atom). Foreground wins by blocking on `lock()`;
  the single refresher thread polls `hasQueuedThreads()` at directory boundaries and
  yields. `with-device!` (no-op for `"file"`), `with-devices!` (sorted acquire → a
  sync touches source **and** sink, deadlock-free), `queued?` (yield signal).
- **Refresher** `src/dapr/refresh.clj` (new): one daemon thread + a
  `LinkedBlockingDeque` + a checkpoint atom `{lib-id -> {:pending-roots :stack
  :seen}}`. `enqueue-all!` (source/sink to front), `refresh-one!`
  (`with-device!` → resumable walk → on `:dapr/pause` checkpoint + re-enqueue; on
  completion reconcile + `:complete`; on `:dapr/abort` discard), `run-loop!`.
- **Resumable walk** `src/dapr/fs/nio.clj` (`walk-audio-tracks!`, 76–146): variant
  taking an initial `stack`/`seen`/remaining-roots, an `on-batch` callback (for
  incremental upsert), and **returning a checkpoint** on `:dapr/pause`. Safe because
  the walk closes each `DirectoryStream` before descending — it holds no handle at a
  `:dir` boundary, so the remaining `stack` is a complete resumable frontier.
- **Split reconcile** `src/dapr/db/cache.clj` (`replace-library-tracks!`, 307–342):
  `upsert-library-tracks!` (upsert-only, per-batch — freshens `library-catalog`
  progressively; safe superset for the UI) + `reconcile-library-tracks!`
  (retract-gone, run **once** on full completion with the full `seen`). Keep the
  combined fn for existing sync-time callers.
- **State** `src/dapr/state.clj`: `:refresh {:status {} :active nil :progress {}}`
  (per-lib `:pending|:scanning|:paused|:complete|:error`) + `:confirm nil`; pure
  transitions `set-refresh-status`/`-active`/`-progress`, `library-complete?`,
  `open-confirm`/`close-confirm`.
- **Events** `src/dapr/ui/events.clj`: `start!`/`refresh-availability!` (264–279) and
  `::select-source`/`::select-sink` (452–458) → **paint from cache** + enqueue /
  re-prioritize (no ad-hoc scan). `run-preview!` (191–222) → cache-only (drop the
  `build-plan!` scan). `::sync` (469) → gate on `library-complete?`, else
  `open-confirm`; add `::sync-confirm`/`::sync-cancel`. `run-sync!` (224–250) wraps
  `execute-plan!` in `with-devices!`. Add `pause-ex?` mirroring `superseded-ex?`
  (73–78); retire the old scan path once unused.
- **Wiring** `src/dapr/system.clj` + `resources/config.edn`: new `:dapr/coordinator`
  and `:dapr/refresher` components (refresher deps state+cache+coordinator; threaded
  into `make-handler`). Refresher **halts before `:dapr/devices`** closes sessions.
- **UI** `src/dapr/ui/views.clj`: confirmation modal (Confirm→`::sync-confirm`,
  Cancel→`::sync-cancel`) mirroring the settings modal; a per-job sidebar in the
  activity window (`fmt/tasks`) with a clickable one-line summary, spinner and all,
  pinned to the main window (`fmt/status-summary`).

**Phases** (each isolated / testable):
- [x] 1. Coordinator (`dapr.device.coordinator`) + `coordinator_test`.
- [x] 2. Cache split (`upsert-library-tracks!` / `reconcile-library-tracks!`).
- [x] 3. Resumable walk in `dapr.fs.nio` (`scan-roots!`: checkpoint + `on-batch`).
- [x] 4. Refresher engine (`dapr.refresh`).
- [x] 5. Integrant wiring (`system.clj`/`config.edn`, halt ordering).
- [x] 6. Events rewire (paint-from-cache + enqueue; cache-only preview; `with-devices!`).
- [x] 7. Confirmation gate + modal view + per-job status bar.
- [x] 8. Cleanup: retire the old `reload-catalogs!` scan path.

**Deltas from the design** (all deliberate, decided while building):
- **Device capability is a multimethod, not a list.** `dapr.device.format/arbitrate-access?`
  (dispatching on device type, default `true`) says whether a device should be handed
  to one holder at a time; `:file` declares `false`, `:mtp`/`:smb` `true` — for
  *different* reasons, recorded at each method: MTP's driver serializes access
  outright, while SMB is fine concurrently (jcifs-ng multiplexes, and dapr already ran
  its source and sink scans in parallel over one FileSystem) but is one slow shared
  connection a walk would saturate, so the sync needs priority rather than a share of
  it. The coordinator
  therefore keeps no hardcoded set of uncoordinated keys, and a future device type
  declares its own access model beside the rest of its metadata. Its API takes a
  `{:key :type}` descriptor from `coordinator/library-device` — the key says *which*
  device to lock, the type *whether* to lock at all (the bare `"file"` key has no URI
  scheme to derive a type from).
- **`dapr.library.catalogs`** (new, unplanned) is the single seam painting state's
  catalogs from the cache; both the events layer and the refresher use it, so the
  refresher needs no dependency on `dapr.ui.events`.
- **`state/update-catalogs`** joins `set-catalogs`: a mid-session repaint must *keep*
  the user's ticked tracks (dropping only vanished keys), where `set-catalogs`
  pre-selects the sink's contents — right only for a fresh source/sink choice.
- **The checkpoint holds live `Path`s**, so it is valid only while the device's
  FileSystem is open (one app run) and is deliberately never persisted.
- **Paint happens outside the device lock.** Painting queries the sink's free space,
  which takes *that* device's lock; doing it while holding the refreshed library's
  lock could deadlock against a sync's two-device acquire.
- **Only the chosen libraries are ever walked.** Choosing a source or sink, or
  saving a library in the editor, is what schedules a scan of it (`refresh/refresh!`
  — the single queue entry point); nothing scans on launch beyond the persisted
  default source/sink, and ↻ Refresh re-probes availability and re-queues those
  same two. The first cut queued *every* configured library at startup, which on a
  real setup means reaching for a DAP that is unplugged and a share that is offline
  — a blocking probe per root and a failed row per absent device, to fill catalogs
  nothing was about to read. Unattached is the normal case for this app, not the
  exception. A library the last probe found unreachable is skipped even when
  explicitly queued (`state/library-unreachable?`), so the editor's save path can't
  reintroduce the same reaching; `::editor-save` therefore probes *before* it
  queues, since the edit may be what fixed the roots.
  - Re-queueing includes a library that already completed this session: the device
    may have changed since, and picking a library is exactly when the user wants its
    list to be right. Only a library being walked *right now* is left alone.
- **Progress is reported per job, and the workspace only carries a summary of it.**
  `fmt/tasks` projects state into `{:id :label :detail :progress :running? :error?}`
  rows: the running foreground op, then a row per library being scanned, paused or
  failed, then one row *counting* the merely queued — a queued library has no
  reason, counters or progress to show, and eight identical empty bars at launch is
  noise. The detailed rows cap at four. This replaced a bottom bar that only ever
  read the *foreground* `:status`/`:progress`, so once the scan path moved to the
  background refresher it sat on "Idle" with an empty bar through an entire refresh.
  - **The rows live in the activity window** (`log-window`, renamed from the log
    window) as a left sidebar beside the live log — the two answer the same
    question, one in the present tense and one in the past. It is opened by
    View ▸ Activity & Logs… or by clicking the summary. An empty sidebar says
    "Nothing running": a panel opened deliberately to ask what the app is doing
    must answer, where the always-visible strip should just get out of the way.
    - **Collapsible** (a `:titled-pane` carrying the job count in its title, so a
      closed panel still says how many). A collapsed TitledPane still reports its
      content's width, which would hold the sidebar open behind a closed panel, so
      the *pane* is clamped to `collapsed-width` instead (302 px → 165 px). The
      obvious alternative — dropping `:content` while collapsed — is a **trap**:
      `TitledPaneSkin`'s collapse animation captures the content node and calls
      `.setVisible` on it when the transition ends, so removing it mid-collapse
      throws an NPE on the JavaFX pulse, kills the transition, and the body never
      lays out again on re-expand. That shipped for one round and had to be
      reverted; the reproduction is a real shown window driven by `.setExpanded`,
      since neither a one-shot build nor a state-driven re-render animates.
      Expanded state lives in `:jobs-open?`, not in the node,
      since the rows re-render constantly while a scan runs; the user's click comes
      back through a raw property listener (`ensure-jobs-expanded-listener!` →
      `events/on-jobs-expanded!`), because cljfx's titled-pane can *set* `:expanded`
      but has no change callback — the same arrangement the log scrollbar already
      uses.
    - Each row is a **grid** cell pair: the name in a column of its own, the bar
      over the status text in the second. The name column is content-sized, which
      takes `:min-width :use-pref-size` — without a min of its own it is the column
      with slack, so GridPane ellipsises the *names* to keep the bars at their
      preferred width. Pinned to its content it takes what the longest name needs
      (26 px for "NAS", 181 px for a long one) and the bar column absorbs or yields
      the slack down to 90 px: text has to be read, a bar only has to be seen.
  - **The main window keeps a one-line summary** (`fmt/status-summary`): an
    indeterminate `:progress-indicator` (a spinner) while anything is actually
    moving, the leading job's text, and a count of the rest. It leads with a
    *failure* where there is one, so the words and the red match — a red line
    reading "Syncing…" is a puzzle, and the sync is the job whose result the user
    can already see. The spinner is suppressed when every job is queued, paused or
    dead: spinning over a failed scan claims the app is working on it.
  - **A job that isn't running gets no row, and no rows means no strip.** An idle
    app shows nothing rather than "Idle" beside a bar that will never fill;
    `:planned` and `:done` are already reported by the plan summary and the log.
    Likewise a bar is drawn only where a fraction means something — a queued
    library, a failed walk, or a walk that has not yet learned its total leaves that
    column blank instead of showing an empty bar, which reads as stalled.
  - The exception is a **failed** foreground op, which keeps its row until the next
    op supersedes it — and now shows `(:error state)`, the reason, which until this
    change was recorded by `state/set-error` and then rendered nowhere at all: the
    bar showed the bare word "Error".
  - Two cljfx constraints, both found by the headless render check and worth
    knowing: a prop value of **nil is not "absent"** (there is no Lifecycle for nil,
    so `main-stage` omits `:bottom` via `cond->` rather than setting it), and
    **`:tooltip` is a Control prop** — an HBox is a plain Region, so the summary's
    tooltip lives on its labels.
  - Consequently `:refresh :progress` is **keyed by library** (and `:refresh
    :active` is gone — it was `(= :scanning …)` in the per-library status, a
    second source of truth for the same fact). A paused library keeps the counters
    it reached, which is honest: it resumes from exactly there.
  - **A failed refresh surfaces as a red row** with its reason as the detail (and
    a tooltip, since the column truncates), replacing the sync bar's summary
    line — a background scan has no other way to reach the user, and the pinned
    strip is a better place for it than a label that duplicated the same
    information beside the pickers. It stays out of the app-wide
    `:error`/`:status`, which belong to the foreground op: a scan that failed must
    not blank a computed plan. The message clears as soon as that library is
    re-queued.
- **`cache/track-sources` is now scoped to the keys being written** rather than the
  whole track table, since an incremental refresh asks once per batch.
- **The whole-catalog scan path is gone**, not just unused: `nio/catalog!` (+ its
  private `walk-audio-tracks!`), `sync/catalog-of!`, `sync/scan-into-cache!`,
  `sync/build-plan!`, the redundant `sync/sink-roots!` alias, and `lib/catalog` /
  `lib/supported-schemes` (the latter already dead before this branch) were all
  deleted — `nio/scan-roots!` is now the app's single walk entry point. Tests that
  wanted a whole catalog use `tfs/scan-tracks!` / `tfs/scan-catalog!` (test-only
  helpers over `scan-roots!`), and `sync_integration_test` now plans the way the
  app does: scanned catalogs → `plan/selection-plan`.

**Key edge cases.** Retract only on completion (a quit mid-refresh keeps
stale-but-superset presences — safe; gate covers sync). File deleted while paused →
retracted on completion; file modified in an already-walked dir → stale until next
full refresh (accepted single-pass limit). Multi-root library → checkpoint remaining
roots too. Source+sink on one device → `with-devices!` dedups to one lock. Confirm
race → re-check `library-complete?` at confirm time. `:dapr/abort` checked before
`:dapr/pause`. Incremental upsert reuses `tx-batch-size` batching.

**Verification.** ✅ Built: `coordinator_test` (foreground preempts background,
two-device no-deadlock, parallel-safe file device never locked, descriptors from
roots); `nio_test` (paused-then-resumed walk == uninterrupted walk, no duplicate tag
reads, multi-root checkpoint); `cache_test` (partial upsert has no retracts;
reconcile retracts only what's gone and transacts nothing when unchanged; downgrade
protection holds per batch); `refresh_test` (checkpoint saved + re-queued on pause,
resumed from it, reconcile+snapshot+`:complete` on finish, error recorded, deleted
library dropped, queue priorities); `state_test` (`update-catalogs` keeps the
selection; per-library refresh projection; sync gate; confirm); `format_test`
(`arbitrate-access?`, `name-list`, `progress-fraction`, `tasks` — no rows when
nothing runs, row order, the collapsed queue, the row cap, error rows, and where a
bar is drawn at all — and `status-summary`: what leads, what is counted, when the
spinner turns); `refresh_integration_test`
(real temp dirs end to end: walk → cache → snapshot, deletion retracted, new file
picked up, worker stops clean). `clojure -M:test` (117) + `-M:integration` (23)
green; clj-kondo + cljfmt clean; views render-checked headlessly.

⏳ **Still wants a manual smoke** (`clojure -M:run`, real hardware): a large MTP/SMB
library paints instantly from cache while its status-bar row advances; choosing a
source/sink is instant; a sync mid-refresh pauses/releases the device (its row goes
"Paused" holding its counts, the Status row runs), then the refresh resumes where it
left off; the confirm dialog appears only while source/sink refresh is unfinished.

**Files.** New: `src/dapr/device/coordinator.clj`, `src/dapr/refresh.clj`,
`src/dapr/library/catalogs.clj`, `test/dapr/device/coordinator_test.clj`,
`test/dapr/refresh_test.clj`, `test-integration/dapr/refresh_integration_test.clj`.
Changed: `fs/nio.clj`, `db/cache.clj`, `state.clj`, `ui/events.clj`, `ui/views.clj`,
`ui/format.clj`, `system.clj`, `resources/config.edn`, `device/format.clj` +
`device/{file,mtp,smb}/format.clj` (the `arbitrate-access?` methods); extended
`nio_test`, `cache_test`, `state_test`, `format_test` (ui + device).

---

## 11. `feat/parallel-refresh` — scan independent devices at once
**📋 SKETCHED, not started.** Do it *after* feature 10's hardware smoke: landing
concurrency on a refresher that has never run against real MTP hardware means
debugging two unknowns at once.

**Why.** The refresher is one daemon thread taking one library at a time
(`refresh/run-loop!`), so a user with a DAP and a NAS waits for both slow scans
back to back even though the devices are unrelated. Nothing about the design
requires that: the coordinator already arbitrates *per device key*, and the UI is
already per-library (feature 10's status bar draws a row per job, and
`:refresh :progress` is keyed by library), so several rows advancing at once needs
no further UI work.

**The blocker, and the primitive that fixes it.** `coord/queued?` is
`ReentrantLock.hasQueuedThreads` — true when *anyone* waits. It is the walk's
signal to check-point and release. Two refresher threads on one device would
therefore preempt *each other*: B blocks, A check-points and releases, B acquires
and immediately sees A re-queued, so B check-points too — a ping-pong of
check-points a directory at a time, strictly worse than serial. The same false
positive already bites `refresh/repaint!`, which takes the *sink's* lock for its
free-space query and so looks like a foreground op to any worker holding it.

So `queued?` must mean "a **foreground** op is waiting", not "someone is waiting":
a per-key count of foreground waiters, incremented before `.lock` and decremented
once acquired, with the background path (a new `with-device-background!`, or a
flag) not counted. Background/background contention then degrades to ordinary
blocking, which is correct.

**Design summary.**
- **Coordinator**: foreground-waiter counts as above; `queued?` reads them.
- **Refresher**: a small pool instead of one thread, plus a **lease set of in-flight
  coordinated device keys** — a worker skips (rotates past) a library whose device
  another worker is already walking, rather than parking a pool thread on its lock.
  Devices that answer `arbitrate-access?` **false** (`:file`) need no lease, so
  local libraries can run as wide as the pool. `stop!` joins every worker.
- **Cache writes**: `upsert-library-tracks!` is read-modify-write (read
  `library-catalog` + `track-sources`, diff, transact) over track entities that are
  **shared across libraries**. Two libraries holding the same file can interleave
  and defeat the embedded→path downgrade guard, leaving good tags overwritten with
  path-derived ones until the next full scan. `d/transact!` is atomic, so nothing
  corrupts — but the diff must become atomic with its transaction: serialize cache
  *writes* through one lock (they are in-memory and fast next to a device walk, so
  contention is irrelevant). Note this interleave is *already possible* between the
  refresher and `sync/apply-plan-to-cache!`; parallelism only widens the window.
- **Repaint**: with the foreground-aware `queued?` a worker's `library-free!` no
  longer looks like a preemption; concurrent `paint!` calls are last-swap-wins over
  consistent cache reads, which is fine.

**Phases.** 1. Foreground-aware `queued?` (+ `coordinator_test` for two background
holders not thrashing). 2. Serialized cache writes. 3. Worker pool + device leases.
4. `stop!` over the pool. 5. Smoke: two slow devices scanning at once, a sync still
preempting both.

**Open questions.** Pool size (one thread per distinct device key, or a fixed small
pool?); whether the sync gate should wait for *all* in-flight refreshes rather than
just source and sink. Note the payoff shrank once the refresher stopped queueing
every library: the queue now holds a source and a sink, so parallelism buys the
overlap of exactly those two — worth it when they are both slow and on different
devices (a DAP and a NAS), which is the common sync, but no longer a launch-time
stampede to untangle.

**Prerequisite already split out:** the concurrent-snapshot fix this work would
otherwise have had to make first ships as its own change (`fix(cache): give each
snapshot write its own temp file`).

---

## Suggested build order
`feat/app-settings` → `feat/source-only-tracklist` (5) →
`feat/library-availability` (7) → `feat/sink-only-tracks` (1) →
`feat/theming` (6) → `feat/logging` (2) → `feat/facet-toggle` (8) →
`ci/release-uberjar` (9). Spikes (3, 4) run anytime in parallel.

Rationale: front-load shared settings infra; 5→7→1 touch overlapping
`track-rows`/`reload-catalogs!` code, so doing them in sequence avoids repeated
merges.

## Merge / rebase order (historical — the stack has landed)

> **Historical.** All of PRs #22–#28 below have merged to `main`. This section is
> kept as a record of how the stack was landed (and the rebase recipe for future
> stacked PRs).

The seven done features shipped as **stacked PRs** — each targeted its parent branch, so its
diff showed only that feature. They were merged strictly bottom-up (a child never
before its parent).

| Order | PR | Branch | Base (parent) |
|-------|-----|--------|---------------|
| 1 | [#22](https://github.com/meltzg/dapr/pull/22) | `feat/app-settings` | `main` |
| 2 | [#23](https://github.com/meltzg/dapr/pull/23) | `feat/source-only-tracklist` (5) | `main` |
| 3 | [#24](https://github.com/meltzg/dapr/pull/24) | `feat/library-availability` (7) | `feat/source-only-tracklist` |
| 4 | [#25](https://github.com/meltzg/dapr/pull/25) | `feat/sink-only-tracks` (1) | `feat/library-availability` |
| 5 | [#26](https://github.com/meltzg/dapr/pull/26) | `feat/theming` (6) | `feat/sink-only-tracks` |
| 6 | [#27](https://github.com/meltzg/dapr/pull/27) | `feat/logging` (2) | `feat/theming` |
| 7 | [#28](https://github.com/meltzg/dapr/pull/28) | `feat/facet-toggle` (8) | `feat/logging` |

Notes / gotchas:
- **#22 and #23 are independent roots** off `main` (either can merge first). #22 must
  land before #25, which merged the app-settings foundation into its history.
- **#25's diff transiently includes #22's commit** (app-settings was merged into
  `feat/sink-only-tracks`). It cleans up once #22 lands. If GitHub squash-merges #22,
  rebase the stack from #24 up (see below) so the duplicated commit drops cleanly.
- As each PR merges, GitHub **auto-retargets its child to the child's new base**
  (usually `main`). If you use **squash or rebase merges** (not merge commits), the
  child branch then shares no history with the squashed parent — **rebase the child
  onto the updated base before merging it**:
  ```
  # after PR N merges to main (squash/rebase), for child branch B on parent P:
  git fetch origin
  git rebase --onto origin/main origin/P B   # replay B's own commits onto main
  git push --force-with-lease origin B
  ```
  Repeat up the stack (#24 → #25 → #26 → #27 → #28). With plain **merge-commit**
  merges this is usually unnecessary — the child already contains the parent.
- Local `feat/sink-only-tracks` was 1 behind `origin` (origin has the completing
  commit `7182a7b`, which *is* in the theming/logging stack). `git fetch && git
  branch -f feat/sink-only-tracks origin/feat/sink-only-tracks` to sync the pointer.

## Per-branch checklist (apply to every feature)
- [ ] Branch off latest `main` (rebase on `feat/app-settings` if it's a consumer).
- [ ] `clojure -M:test` green.
- [ ] `clojure -M:integration` green (SMB via Testcontainers needs Docker; MTP
      skips without hardware — matches CI's `integration.yml`).
- [ ] `clojure -M:clj-kondo --lint src dev test test-integration` clean.
- [ ] `clojure -M:cljfmt check` clean.
- [ ] Manual smoke via `clojure -M:run` (or REPL `dev/go`).
