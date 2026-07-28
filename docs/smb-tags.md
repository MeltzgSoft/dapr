# Spike: reading embedded audio tags over SMB

**Feature 3 (`spike/smb-tags`).** Deliverable: this evaluation + a working reader
(`dapr.device.smb.tag`).

## Problem

Every track's tags flow through the `dapr.device.tag/tags!` multimethod during a
scan (`dapr.fs.nio/track-tags!`). The `file://` method reads *embedded*
ID3/Vorbis/MP4 tags with **jaudiotagger**, and `mtp://` reads them from device
metadata; `smb://` fell back to the default method, which *derives* tags from the
path (`dapr.domain.tags/from-path`). So an SMB library showed folder/filename
guesses even when the files carry real tags — this spike closes that gap.

The blocker is that **jaudiotagger reads only a `java.io.File`** on the local
default filesystem (`AudioFileIO.read(File)` → `RandomAccessFile` internally). An
`smb://` path is a jcifs-backed NIO `Path`, not a `File`, so jaudiotagger can't
open it directly. SMB reads are also the expensive path, and a music library can
hold **tens of thousands** of tracks — so *how many bytes* a reader pulls per
track is the whole game.

## Key findings (verified against the pinned deps)

1. **jaudiotagger has no channel/stream entry point.** 3.0.1's public API is
   `File`-only; there is no `read(SeekableByteChannel)` or `read(InputStream)`. So
   handing jaudiotagger a live SMB channel is a dead end without forking it, and
   using jaudiotagger at all means first staging a **whole-file** local copy.

2. **smb-nio exposes a seek-capable channel.**
   `SMBFileSystemProvider.newByteChannel` returns a real `SeekableByteChannel`
   (`ch.pontius.nio.smb.SeekableSMBByteChannel`, backed by jcifs
   `SmbRandomAccessFile`) whose `position(long)` and `size()` work — verified by
   `javap` on `smb-nio-0.13.0.jar` and by an integration assertion that seeks 10
   bytes into a share file and reads the next 4. jcifs issues an SMB `READ` at the
   requested offset, so **ranged reads over SMB are real** — we are not forced to
   transfer whole files.

3. **melt-jfs already ships device-agnostic header parsers we own.** melt-jfs
   0.2.0's `org.meltzg.fs.mtp.audio` package parses embedded tags from a small
   header slice for FLAC, MP3, MP4/M4A, Ogg Vorbis/Opus and WAV. Despite the `mtp`
   in the package name it has **zero MTP coupling** — every class imports only
   `java.*`, there is no `module-info` restricting access, and the seam is a pure
   functional interface:

   ```java
   @FunctionalInterface interface RangedByteSource { byte[] read(long offset, int maxBytes); }
   AudioTags AudioTagReaders.read(String filename, RangedByteSource source, long fileSize);
   ```

   The MTP file-tags view backs that source with `bridge.readPartial(...)`; we back
   it with a `SeekableByteChannel`. So the parsers are reusable **as-is** — no
   modification to melt-jfs is required to use them over SMB.

## Options evaluated

| # | Approach | Bytes over SMB | Format coverage | Complexity |
|---|----------|----------------|-----------------|------------|
| (a) | NIO-copy the whole file to a temp file, read with jaudiotagger, delete | **whole file** | all jaudiotagger formats | low |
| (b) | Feed jaudiotagger a seekable SMB channel directly | header-ish | all | **impossible** — no channel API (finding 1) |
| **(c)** | Parse only the tag header over ranged reads (**melt-jfs parsers**) | **a few KB** | FLAC/MP3/MP4/Ogg/Opus/WAV; others → path | low (finding 3) |

Option (a) was the initial prototype, but its whole-file transfer per track is
untenable at library scale — the concern that drove this revision. Option (c) was
originally scoped as a hard "future optimization" needing custom per-format
parsers; **finding 3 collapses that cost**, because melt-jfs already has the
parsers and the ranged-read seam, and we own the source.

## Recommendation — ship (c) over melt-jfs

Read tags from a header slice via `AudioTagReaders`, backing its `RangedByteSource`
with an smb-nio `SeekableByteChannel`:

1. Open a read-only channel on the `smb://` path.
2. Adapt it to `RangedByteSource` — `(offset, maxBytes) -> seek + read` — so each
   range the parser asks for becomes one targeted SMB `READ`.
3. `AudioTagReaders.read(filename, source, size)` → `AudioTags`; map onto dapr's
   `{:artist :album :title :source}`, per field falling back to the path value.
4. A format melt-jfs doesn't parse (**aac, wma** — the only gaps vs dapr's
   `default-audio-extensions`), an unreadable header, or any failure degrades to
   **path-derived tags**. Never a whole-file read, never worse than the path
   default, never an aborted scan.

## What ships

- **`deps.edn`** — no version bump needed: the mtp-tags feature already put
  melt-jfs at `0.2.0` (the version that adds the audio parsers). Only the comments
  now note the smb reader backs one with a seekable channel.
- **`src/dapr/device/smb/tag.clj`** — registers `tags! :smb`. `channel-source`
  adapts a `SeekableByteChannel` to melt-jfs's `RangedByteSource`;
  `audio-tags->tags` maps `AudioTags` onto dapr's **full tag map**
  (artist/album/title/genre/track/disc/duration, matching the file:// and mtp://
  readers) with per-field path fallback and `:source :embedded` only when the file
  actually reported a title/artist/album.
- **`src/dapr/fs/nio.clj`** — loads `dapr.device.smb.tag` for its method
  registration alongside `dapr.device.file.tag`.

`file://` still uses jaudiotagger (a local `File`, cheap — no reason to change it);
`smb://` and `mtp://` both now read embedded tags over ranged reads via melt-jfs.

### Cache migration

- **`src/dapr/db/migrations.clj`** — `migrate-smb-tag-sources!`, registered as
  `:migration/smb-tag-sources`. Existing SMB tracks were cached `:source :path`
  under the old path-only reader with an unchanged mtime, and
  `dapr.fs.nio/track-tags!` reuses any cached entry that already has a `:source`
  when the mtime matches — so without this they'd keep their stale path-derived
  tags until the file's mtime changed. The migration retracts `:track/tag-source`
  from every `:path`-sourced track under an `smb://` root, so the next scan
  re-reads it through the new reader. It runs once via the framework's applied-id
  gate (`run-migrations!`, already wired in `system.clj`), so it can't repeatedly
  re-clear a genuinely tagless file (aac/wma or an untagged file, left at `:path`
  by the re-read) and force a wasted read every startup. Retract-only, no I/O.
  This mirrors #33's `:migration/mtp-tag-sources`, minus the legacy-flag bridge
  (that was specific to MTP's pre-framework migration).

### Optional melt-jfs ergonomic follow-up (not required)

The parsers are reusable today; the only friction is that each consumer writes the
~15-line channel→`RangedByteSource` glue, and the package is named `…mtp.audio`
though it is device-agnostic. Since we own melt-jfs, two small, backward-compatible
improvements would make the reuse first-class — but each needs a melt-jfs release
before dapr's CI (which pulls the published artifact) can use it, so they are
deliberately **out of scope** for this branch:

1. Add a `RangedByteSource.ofChannel(SeekableByteChannel)` static factory beside the
   existing `ofArray` — pure JDK, collapses every consumer's glue to a one-liner.
2. Optionally rename/alias the package to `org.meltzg.fs.audio` to signal that it is
   not MTP-specific.

## Cost

A first scan reads only the header slices each parser requests — a few KB per
track for the front-loaded formats, versus the tens of MB a whole FLAC would cost
under option (a). Rescans read nothing for unchanged files (the `track-tags!`
mtime/size cache). This is what makes the reader tenable on a
tens-of-thousands-of-files share.

`moov`-at-end MP4/M4A files may cost a second ranged read (a tail seek) rather than
one; melt-jfs's `Mp4MetadataReader` handles that internally, still without a
whole-file transfer.

## Testing

- **Unit** (`test/dapr/device/smb/tag_test.clj`): the `channel-source` ranged reads
  (absolute seeks, EOF truncation, empty/negative cases); the `audio-tags->tags`
  mapping (embedded wins, blank fields fall back per field, no-usable-tags keeps
  `:path`); and the real read path end-to-end over a **local** channel — a
  hand-built tagged FLAC parsed via melt-jfs, plus an unsupported extension (`aac`)
  and an unreadable `.flac` both degrading to path tags. `tags!` uses the path only
  for the channel and filename, so a local Path under an `smb://` root exercises the
  exact production path with no SMB.
- **Integration** (`test-integration/dapr/device/smb/tag_integration_test.clj`,
  part of `clojure -M:integration`): against the shared Samba backend
  (Testcontainers on Linux / native SMB on CI mac/win — the same fixture as
  `fs_integration_test`), it copies a real tagged FLAC onto the share and asserts it
  reads back `:embedded` with the embedded values winning over the path; an untagged
  file degrades to `:path`; and smb-nio's channel is shown to honour `position()`
  (finding 2).

  The FLAC fixture (`test/dapr/audio_fixtures.clj`, shared by both suites) is built
  in memory — `fLaC` + STREAMINFO + a VORBIS_COMMENT — the same shape as melt-jfs's
  own `SyntheticFlac` test fixture, so its `FlacMetadataReader` parses it. No
  external encoder or committed binary. FLAC (not WAV) because melt-jfs reads FLAC's
  VORBIS_COMMENT, whereas jaudiotagger's WAV chunk layout isn't what melt-jfs's WAV
  reader expects.

  Two test-harness fixes were needed to add a second SMB namespace alongside the
  existing `fs_integration_test`:
  - The shared `with-smb-backend` fixture starts **one** Samba container for the
    whole JVM run (torn down by a shutdown hook), instead of a fresh container per
    namespace. Restarting a container on the fixed port 445 between namespaces raced
    smbd readiness / docker-proxy connection tracking and reset the next namespace's
    connections; a single long-lived container removes the race.
  - Each namespace's fixture clears `dapr.device.smb.fs`'s process-wide FileSystem
    cache on entry, so a handle opened by a prior namespace can't be reused stale.

## Status

Complete on `spike/smb-tags`, rebased onto the merged mtp-tags work: reader +
unit tests + integration test (no deps bump — mtp-tags already put melt-jfs at
0.2.0). Unit suite green (99 tests); the SMB fs + tag integration tests green
(8 tests / 28 assertions against a live Samba container); clj-kondo + cljfmt clean.
This is effectively the `feat/smb-tags` implementation, not just a prototype — it
reads real embedded tags over ranged reads with no whole-file transfer, and threads
the full field set (genre/track/disc/duration alongside artist/album/title) through
to the table columns. The optional melt-jfs `ofChannel`/package-rename ergonomics
are the only remaining follow-up, and are not required for correctness.
