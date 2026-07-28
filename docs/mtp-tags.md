# Spike 4: MTP tag reading via device metadata

**Verdict: yes — read tags without pulling file bytes.** Two metadata-only
routes exist, both far cheaper than a whole-object transfer:

- the device's **media index** (per-object Artist / AlbumName / Name), and
- the file's **own embedded tags**, read from a few KB of header over MTP
  **ranged reads** (`GetPartialObject`).

melt-jfs surfaced neither at spike time; since we own it, both landed as NIO
attribute views. **melt-jfs 0.2.0** ships the `"audio"` view (embedded tags via
ranged reads) alongside the earlier `"mtp"` view (device index), and this branch
consumes both: `deps.edn` is on 0.2.0, and the dapr reader layers **audio over
mtp over path** per field. Prefer the `audio` view — it recovers the real
embedded tags even when the device's index is stale, unpopulated, or reports the
filename as the title. The reader degrades gracefully when a view is absent (an
older melt-jfs throws `UnsupportedOperationException`), falling through to the
next layer.

## Problem

`dapr.device.tag/tags!` has no `:mtp` method, so MTP tracks get path-derived
tags only. The byte-reading alternative is prohibitive: melt-jfs's
`newReadableByteChannel` has no partial read — it streams the **whole object**
over USB into a temp file. Even the streaming-FLAC trick that rescued smb://
(ranged reads, ~KB per file) can't work, because MTP itself has no ranged
`GetObject` in the operations melt-jfs uses. A 30 MB FLAC ≈ seconds per track;
a thousand-track library ≈ the better part of an hour, per scan of new files.

## What MTP offers instead

Devices maintain a media index (Android: MediaStore, fed by its media scanner)
and expose it as MTP **object properties**. Both native stacks reach it:

- **libmtp (Linux/macOS)**: `LIBMTP_Get_Trackmetadata(device, id)` returns a
  `LIBMTP_track_t` with title/artist/album/genre/tracknumber/duration.
  Verified against the libmtp 1.1.21 source (the version installed here):
  - On the **uncached** handle melt-jfs opens
    (`LIBMTP_Open_Raw_Device_Uncached`), `flush_handles` returns immediately
    (`if (!device->cached) return;`), so there is **no whole-device
    enumeration** — the call does one `GetObjectInfo` for that handle (already
    cached when a directory listing walked past it) plus one single-object
    `GetObjectPropList` (or per-property `GetObjectPropValue` on devices
    without proplist support).
  - It returns NULL for objects whose format code isn't a known track type —
    see the `FILETYPE_UNKNOWN` caveat below.
- **WPD (Windows)**: `IPortableDeviceProperties::GetValues` with
  `WPD_MEDIA_TITLE/ARTIST/GENRE/DURATION` + `WPD_MUSIC_ALBUM/TRACK` (property
  keys verified against the Windows SDK `PortableDevice.h`). One COM call per
  object, same metadata-only cost profile.

**Cost**: a few small USB transactions, ~10–30 ms/track expected (hardware
measurement still pending — see Verification). Versus seconds per track for
whole-object reads: ~100× cheaper, and dapr's tag cache makes it a
first-scan-only cost.

A bulk path also exists (`LIBMTP_Get_Tracklisting_With_Callback_For_Storage`)
but enumerates **every** track on a storage per call. Rejected: melt-jfs
deliberately avoids whole-device enumeration, and per-item reads fit dapr's
incremental cache (only never-seen tracks are read) and partial scans better.

## melt-jfs changes (implemented)

melt-jfs 0.1.1 exposes only filesystem facts (`MTPItemInfo`, `basic` attribute
view). Branch **`feat/track-metadata`**
([PR #9](https://github.com/MeltzgSoft/melt-jfs/pull/9), commit `0c1d9bd`,
onto `master`) adds:

- `MTPTrackMetadata` record: title/artist/album/genre (null when unreported),
  trackNumber/durationMillis (0 when unreported).
- `MtpBackend.getTrackMetadata(handle, itemId)` — `default` returns null, so
  existing backend implementations keep compiling. Overridden by both
  `NativeLibMTP` (new `LIBMTP_track_t` FFM layout +
  `LIBMTP_Get_Trackmetadata`/`LIBMTP_destroy_track_t` bindings) and
  `WpdBackend` (one `GetValues` over the media/music keys). Both return null
  for "no metadata" uniformly.
- `MTPDeviceBridge.getTrackMetadata(deviceId, path)` — resolves the path
  (through the existing TTL listing cache) and asks the backend; null for
  non-files.
- **The seam dapr consumes:** a new `"mtp"` NIO attribute view —
  `Files.readAttributes(path, "mtp:title,artist,album")` (or `mtp:*`) —
  listed in `supportedFileAttributeViews`. Pure `java.nio`, so dapr's reader
  imports no melt-jfs classes.

Unit-tested against the fake backend (10 tests); device-gated integration
tests assert the view's shape and print a timing for the metadata read.

## dapr wiring (on this branch)

`dapr.device.mtp.tag` registers `tag/tags! :mtp`, layering three sources per
field with `merge-device-tags` (pure + unit-tested): path-derived fallback,
then the `"mtp"` view over it, then the `"audio"` view over that. So for each
field the best available value wins — embedded tags first, the device index
next, the path last. The full set threads through: title/artist/album/genre and
trackNumber/discNumber/durationMillis (path derivation only supplies the first
three; the rest come from a view or stay nil). Everything downstream carries
them — the cache track entity, the catalog, the scan-reuse path, and the track
table (columns disc/track/title/duration/artist/album/genre).

`read-view` requests `"*"`, not a fixed attribute list, because the two views
expose **different** sets: `AudioTags` (audio view) has discNumber but
`MTPTrackMetadata` (mtp index) has no disc field. `melt-jfs` throws
`IllegalArgumentException` for an attribute a view doesn't know, which the
reader would swallow to `{}` — losing that whole layer — so a shared explicit
list would silently drop the entire `mtp` layer. `"*"` returns whatever each
view supports; `merge-device-tags` picks each field by name and an absent one
just reads as nil.

- **`audio` preferred over `mtp`**: the `audio` view parses the file's own tags,
  so it is correct even when the device's index is stale, unpopulated, or
  filename-derived; `mtp` fills fields the embedded reader can't (an unsupported
  container, a corrupt header).
- **`:source :embedded`, not a new `:device` value**: either view reflects the
  files' embedded tags, so reusing `:embedded` keeps `dapr.db.cache`'s better-tags
  preference (`:embedded` beats `:path`) working unchanged. The layered merge
  keeps `:embedded` sticky — once any layer reports, a blank outer layer can't
  demote it back to `:path`.
- **Graceful when a view is absent**: an older melt-jfs (or the default
  provider) throws `UnsupportedOperationException` for an unknown view; each
  view read catches Throwable and yields `{}`, so the reader falls through to
  the next layer and, if all are blank, to path tags.

## Caveats

These applied to the `mtp` (device-index) view; the `audio` view now leads and
resolves every field it can, with `mtp` and path still behind it:

- **The device must have indexed the file** *(mitigated)*. The `mtp` view is
  blank until the device's media scanner runs; the `audio` view reads the file's
  own header instead, so freshly-uploaded and unindexed tracks tag immediately.
  A `mtp`-only miss degrades to path tags, never worse.
- **Files uploaded as `FILETYPE_UNKNOWN` report no `mtp` metadata** *(mitigated)*
  (libmtp's track gate filters on object format, and melt-jfs `sendFile` sends
  everything as unknown). The `audio` view is format-agnostic — it parses the
  bytes regardless. The source-level fix remains a melt-jfs follow-up: infer the
  filetype / WPD object format from the filename extension in `sendFile`.
- **`mtp` title comes from PTP `Name` (0xDC44)**; a few devices return the
  display name rather than the tag title *(mitigated)* — the `audio` view reads
  the real embedded title, and title is the least valuable field anyway
  (path-derived title ≈ filename).
- **`audio` supports FLAC, MP3, MP4/M4A, Ogg Vorbis, Opus, WAV.** Any other
  container falls through to the `mtp` index, then path — never worse than the
  device-index-only approach.
- **Cache migration** *(done — option a)*: existing MTP tracks were cached with
  `:source :path` and an unchanged mtime, and `dapr.fs.nio/track-tags!` only
  re-reads entries without a recorded source, so they would have kept path tags
  after the reader landed. `dapr.db.migrations/migrate-mtp-tag-sources!` retracts
  `:track/tag-source` from every `:path`-sourced track under an `mtp://` root, so
  the next scan re-reads it through the device reader. It's registered as the
  **`:migration/mtp-tag-sources`** migration in `dapr.db.migrations` and run once from the
  `:dapr/cache` init via the applied-id gate (`run-migrations!`). Running-once
  matters: an ungated re-run would re-clear genuinely tagless files (device reported
  nothing) every launch, re-paying the device read each time — the rejected option
  (b). Option (c), do nothing, would have left existing libraries on path tags until
  an mtime change. A sibling migration, **`:migration/extended-tag-fields`**, does the
  same for the extended fields (genre/track/disc/duration): it clears the recorded
  source on file:// and mtp:// tracks — at any value, since embedded-sourced tracks
  also predate the new fields — so the next scan backfills them through the richer
  readers.

## Verification status

- melt-jfs: full unit suite green against the fake backend; the FFM struct
  layout and the real USB cost were confirmed on hardware (see below).
- dapr: unit tests cover the layered merge (audio over mtp over path, sticky
  `:embedded`) and the no-view fallback; lint + cljfmt clean.
- **On hardware** *(done — iRiver AK100_II)*: `clojure -M:integration`
  (`dapr.device.mtp.tag-integration-test`) writes a FLAC fixture to the device
  and reads it back. The `audio` view returned all seven fields over
  GetPartialObject (genre=Jazz, trackNumber=7, discNumber=2, durationMillis=2000)
  and `tags!` produced the full `:source :embedded` map in **~24 ms** — a
  metadata-only cost, as predicted. The `mtp` index returned six fields (no
  discNumber), confirming the view asymmetry the `"*"` read exists to handle.

## Follow-ups

1. ~~Verify on hardware: melt-jfs `./gradlew integrationTest` + the dapr smoke
   above; record the measured per-track cost here.~~ **Done** — dapr's
   `dapr.device.mtp.tag-integration-test` verified the full field set on an
   iRiver AK100_II (~24 ms/track, metadata-only); see Verification status.
2. ~~Merge melt-jfs [PR #9](https://github.com/MeltzgSoft/melt-jfs/pull/9),
   tag a release, bump dapr's `deps.edn`.~~ **Done** — released as melt-jfs
   0.1.2 (Maven Central); `deps.edn` bumped 0.1.1 → 0.1.2.
3. ~~Promote this spike's prototype to the real feature: the deps bump plus the
   cache-migration decision above.~~ **Done** — deps bumped to 0.1.2 and the
   one-off `migrate-mtp-tag-sources!` migration (option a) shipped.
3b. ~~Adopt melt-jfs 0.2.0's `audio` view (embedded tags via ranged reads) as the
   preferred source, falling back to the `mtp` index then path.~~ **Done** —
   `deps.edn` on 0.2.0; `tag/tags! :mtp` layers audio over mtp over path.
4. melt-jfs follow-up: `sendFile` filetype inference (fixes the
   `FILETYPE_UNKNOWN` caveat at the source).
5. ~~Optional someday: the view already surfaces genre/trackNumber/
   durationMillis if the track table ever wants more columns.~~ **Done** — the
   full set (genre/trackNumber/discNumber/durationMillis) now threads through the
   reader, cache, and track table as dedicated columns.
