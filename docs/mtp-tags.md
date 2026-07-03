# Spike 4: MTP tag reading via device metadata

**Verdict: yes — read tags from MTP object properties, not file bytes.** MTP
devices index their media and expose per-object Artist / AlbumName / Name (and
Genre / Track / Duration) properties, so tags cost a few small USB
transactions per track instead of a whole-object transfer. melt-jfs did **not**
surface these; since we own it, the lib change is implemented (see below), and
this branch carries the dapr-side reader, wired so it degrades gracefully
until the new melt-jfs release is picked up.

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

## dapr wiring (prototype on this branch)

`dapr.device.mtp.tag` registers `tag/tags! :mtp`: read
`"mtp:title,artist,album"`, merge over path-derived fallbacks per field
(`merge-device-tags`, pure + unit-tested), `:source :embedded` when the device
reported anything, else `:path`.

- **`:source :embedded`, not a new `:device` value**: the device's media index
  *is* the files' embedded tags as it last scanned them, and reusing
  `:embedded` keeps `dapr.cache`'s better-tags preference (`:embedded` beats
  `:path`) working unchanged.
- **Graceful on melt-jfs 0.1.1**: the default provider (and 0.1.1's MTP
  provider) throws `UnsupportedOperationException` for the unknown view; the
  method catches Throwable and falls back to path tags — so this branch is
  safe to merge before the melt-jfs release, and lights up when `deps.edn`
  bumps to the release carrying the view.

## Caveats

- **The device must have indexed the file.** Android does this automatically;
  dumb devices may report nothing. Every miss degrades to today's behavior
  (path-derived tags), never worse.
- **Files uploaded as `FILETYPE_UNKNOWN` report no metadata** (libmtp's track
  gate filters on object format, and melt-jfs `sendFile` currently sends
  everything as unknown). Low impact for dapr — tracks it copies to the device
  hit the tag cache under the same `[rel size]` — but the real fix is a
  melt-jfs follow-up: infer the LIBMTP filetype / WPD object format from the
  filename extension in `sendFile`.
- **Title comes from PTP `Name` (0xDC44)**; a few devices return the display
  name rather than the tag title. Title is also the least valuable field
  (path-derived title ≈ filename), so per-field preference handles it.
- **Cache migration**: existing MTP tracks are cached with `:source :path` and
  an unchanged mtime, and `dapr.fs.nio/track-tags!` only re-reads entries
  without a recorded source — so **existing libraries keep path tags after the
  reader lands**. Options for the follow-up feature:
  (a) one-off migration that clears `:track/tag-source` for mtp-rooted tracks
  (recommended — one retract, next scan re-reads);
  (b) re-read any `:path`-sourced mtp track each scan (re-pays 1–2
  transactions per genuinely tagless file, every scan);
  (c) do nothing — entries upgrade only when a file's mtime changes.

## Verification status

- melt-jfs: full unit suite green (38 tests) against the fake backend; the
  FFM struct layout and the real USB cost need a **physical device** — plug
  one in and run `./gradlew integrationTest`
  (`trackMetadataReadsForAudioFileWithoutTransferringContent` prints the
  timing this doc estimates).
- dapr: unit tests cover the merge logic and the no-view fallback; lint +
  cljfmt clean. End-to-end (real tags in the track table) needs the local
  melt-jfs jar and a device:

  ```sh
  (cd ../melt-jfs && ./gradlew jar)   # → build/libs/melt-jfs-0.0.0-SNAPSHOT.jar
  clojure -Sdeps '{:deps {io.github.meltzg/melt-jfs
                          {:local/root "../melt-jfs/build/libs/melt-jfs-0.0.0-SNAPSHOT.jar"}}}' \
          -M:run
  ```

## Follow-ups

1. Verify on hardware: melt-jfs `./gradlew integrationTest` + the dapr smoke
   above; record the measured per-track cost here.
2. Merge melt-jfs [PR #9](https://github.com/MeltzgSoft/melt-jfs/pull/9),
   tag a release (0.1.2 or 0.2.0), bump dapr's `deps.edn`.
3. Promote this spike's prototype to the real feature: the deps bump plus the
   cache-migration decision above.
4. melt-jfs follow-up: `sendFile` filetype inference (fixes the
   `FILETYPE_UNKNOWN` caveat at the source).
5. Optional someday: the view already surfaces genre/trackNumber/
   durationMillis if the track table ever wants more columns.
