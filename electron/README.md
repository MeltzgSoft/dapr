# Dapr desktop shell (Electron)

A window around the Dapr web UI. There is no application code here: the page is
the same server-rendered HTML a browser gets from `dapr.web.*`, and the renderer
holds no state of its own. What the shell adds is the lifecycle a browser tab
cannot — choosing a port, starting the backend on it, waiting for it to serve,
and taking it down again on quit.

This is not part of the Clojure build. `clojure -M:run` still works exactly as
before, and still opens your ordinary browser.

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

## Not done yet

No packaging. `electron-builder` (or Forge) would need to stage the uberjar into
the app's resources and produce per-OS installers — and, unlike the jar, those
*are* per-OS, which is a release-matrix change worth its own branch.
