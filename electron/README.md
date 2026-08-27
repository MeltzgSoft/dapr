# Dapr desktop shell (Electron)

A window around the Dapr web UI. There is no application code here: the page is
the same server-rendered HTML a browser gets from `dapr.web.*`, and the renderer
holds no state of its own. What the shell adds is the lifecycle a browser tab
cannot — choosing a port, starting the backend on it, waiting for it to serve,
and taking it down again on quit.

Running the shell is not part of the Clojure build — `clojure -M:run` still works
exactly as before, and still opens your ordinary browser. *Packaging* it is:
staging the jar and the bundled runtime is a `build.clj` task (see Packaging).

## Running it

```sh
cd electron
npm install
npm start
```

The shell starts the backend itself, so **don't run `clojure -M:run` alongside
it**: that leaves two servers with two independent states over one cache DB, and
whichever exits last wins the snapshot. Quit the browser-based one first.

`npm start` looks for a jar to run, in this order:

1. `dapr.jar` beside the Electron resources (a packaged app),
2. the newest jar in `target/` (`clojure -T:build uber` puts one there),
3. failing both, `clojure -M:run` from source — slower to boot, same app.

So `clojure -T:build uber` first if you want a fast start.

## The port

The shell binds the first available port **at or above 7373**, which is the same
default `dapr.web.server/default-port` uses — so a machine with nothing else
running lands on the familiar `http://127.0.0.1:7373/`. If something already
holds it, the scan steps up one at a time (7374, 7375, …) and the window follows
wherever the backend actually landed.

Availability is decided by *binding* the port, not by connecting to it: a port
held by a socket in `TIME_WAIT`, or bound by another user, also refuses
connections, and only a real bind tells those apart from free.

Nothing reserves the port between the scan and the backend binding it, so that
answer is advisory. If the backend fails to come up on the chosen port, the shell
moves to the next one and tries again (five attempts) rather than reporting a
failure that was really a race.

`DAPR_PORT` sets where the scan *starts*, if you want it out of the usual range.

## Lifecycle

- **One instance.** Two shells would mean two backends over one cache DB, with
  the last snapshot winning. A second launch focuses the existing window.
  (The lock is per config directory, so runs under different
  `XDG_CONFIG_HOME` values — the e2e suite, say — stay independent.)
- **Quit from the UI works.** It calls `System/exit`; the shell sees the backend
  exit and closes rather than leaving an empty window.
- **Closing the window stops the backend**, on macOS too. The usual macOS
  convention of staying resident with no windows assumes a cheap app; this one
  holds a JVM, an open cache and possibly a device session.
- **The stop signal is `SIGTERM`, not a kill.** The JVM's shutdown hook is what
  snapshots the cache DB (see `dapr.system`), so a killed backend loses whatever
  a scan had just learned. Only after a grace period does it escalate.

## Security posture

The renderer gets `contextIsolation`, `sandbox`, and no Node integration; there
is no preload script because there is no bridge to expose. Navigation is pinned
to the backend's own origin — anything else is handed to the system browser
rather than opened in a window with no address bar.

## Tests

```sh
npm test
```

`node --test` over `test/`, covering the port scan: that it prefers the base
port, that it steps over a run of taken ports, and that an exhausted range is an
error rather than a silent walk toward 65535. The scan tests bind real sockets on
a high base port, so they neither collide with a Dapr you have running nor depend
on you not having one.

There is no automated coverage of the window itself. What has been verified by
hand:

- a cold launch taking 7373; with 7373 held, stepping to 7374; with both held,
  stepping to 7375;
- the UI rendering in the window;
- the backend exiting (the UI's Quit) closing the shell;
- **closing the window shutting the backend down cleanly** — the JVM exits, the
  port is released, and `cache.edn`'s mtime advances, which is the shutdown hook
  having run rather than the process having been killed. Whole sequence takes a
  few seconds;
- `SIGTERM` to the shell doing the same, because Electron turns that into a
  quit, which is the same path.

The one case that *does* orphan the backend is `SIGKILL` to the shell — no
handler can run, so the JVM keeps its port and its unsaved cache until it is
stopped by hand. That is inherent to `SIGKILL` rather than something the shell
can defend against.

## Packaging

```sh
cd electron && npm ci && cd ..
clojure -T:build uber
clojure -T:build package-electron
```

**Packaging is driven from `build.clj`**, not from npm. `package-electron` stages
the payload and then runs the locally installed electron-builder over it, so
there is one entry point and one place that knows the order — npm here is only
for `npm start` and `npm test`. Staging puts two things in `electron/resources/`,
which electron-builder copies verbatim to `process.resourcesPath`:

- `dapr.jar` — the uberjar, taken by the exact name the build gave it;
- `runtime/` — a trimmed JRE built with `jlink` from the JDK running the build.

This lives in `build.clj` because that is where the answers already are. It knows
the version (`DAPR_VERSION` → git tag → SNAPSHOT) and therefore the jar's exact
name, so a version mismatch is an error rather than a stale jar packaged
silently; and `jlink` is a JDK tool, which makes it the JVM build's business. It
also stamps the shell's `package.json` version — through `npm pkg set`, so the
rest of that file is left alone — but only when a version was actually asked for,
so a local packaging run does not leave the tree dirty.

`npm ci` stays a separate step: it is a dependency install, and keeping it out of
the build task is what lets CI cache it.

**The bundled runtime is the point.** Without it an installer would silently
require the user to have JDK 25, which is not what installing an application
should mean. It costs ~73 MB, taking the installers to roughly 190 MB
(AppImage) and 162 MB (deb).

The linked module set (`jlink-modules` in `build.clj`) is `java.se` plus
`jdk.unsupported`, `jdk.crypto.ec`, `jdk.zipfs` and `jdk.localedata` —
deliberately generous rather than derived from `jdeps`. Clojure resolves classes at runtime and the device backends reach
the OS through JNA, so static analysis under-reports what is actually loaded,
and the failure mode is a `NoClassDefFoundError` on a user's machine months
later, on whichever path was not exercised here.

Outputs land in `electron/dist/`: AppImage and deb on Linux, NSIS `.exe` on
Windows, `.dmg` on macOS. Each is built on its own OS — the staged JRE is native
code, so one runner cannot produce another platform's installer.

The release workflow (`.github/workflows/release.yml`) does this on a `v#.#.#`
tag and attaches the results alongside the jar. The version flows from the tag
through `DAPR_VERSION` into `build.clj`, which is the only place it is derived.

It is set with `npm pkg set` and deliberately *not* with electron-builder's
`-c.extraMetadata.version`: that flag rewrites `package.json` in place and drops
everything it does not recognise, including `scripts` and `devDependencies`,
leaving a checkout that later steps cannot build.

### Smoke-testing the Windows and macOS legs

Only the host OS's installers can be built on a given machine, so the Windows and
macOS legs are exercised nowhere but CI — and CI only packages on a `v#.#.#` tag.
That would make a real release the first run of that code, where a failure leaves
the jar attached and the installers missing.

So `release.yml` takes an `installers_only` dispatch input. Run the workflow from
any ref with it ticked and it skips the hardware gate and both test suites,
builds the installers on all three runners, and **publishes nothing** — each leg
uploads its output as a run artifact (`installers-<os>`, kept 7 days) for you to
download and open on that platform. The version is pinned to `0.0.0-smoke` rather
than taken from the ref, since a branch name like `split/08-package` would put a
slash in the jar's filename.

Note that GitHub reads dispatch inputs from the workflow file **on the default
branch**, so the tick box only appears once this has merged.

### Known gaps

- **No application icon.** Builds warn and fall back to the default Electron
  icon, which is worse than a placeholder in a shipped app but better than
  inventing branding here. Drop a 512×512 `build/icon.png` in to fix it.
- **Nothing is signed.** The macOS `.dmg` is unsigned and unnotarized, so
  Gatekeeper blocks it until the user right-click-Opens; the Windows installer
  will show a SmartScreen warning. Both need real certificates in CI secrets.
  The macOS entitlements (`build/entitlements.mac.plist`) are already written for
  the bundled JRE — JIT and library validation — so signing is a credentials
  problem, not a configuration one.
- **macOS is Apple silicon only**, because `macos-latest` is arm64. An Intel dmg
  needs its own matrix leg on `macos-13`.
