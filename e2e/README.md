# Browser end-to-end tests

[Playwright](https://playwright.dev) tests that drive the real application in a
real browser. They cover what the Clojure suite cannot reach: that htmx is wired
up, that a region's poll actually swaps in what a background scan found, and that
a sync started from the page puts real files on disk.

They are **not** part of the Clojure build — `clojure -M:test` and
`clojure -M:integration` stay pure JVM. Run these separately:

```bash
cd e2e
npm install
npx playwright install chromium   # once, to fetch the browser
npm test                          # headless
npm run test:headed               # watch it happen
npm run test:ui                   # Playwright's interactive runner
npm run report                    # open the last HTML report
```

`npm test` starts the app itself (`clojure -M:run --port 7374 --no-browser`) and
stops it afterwards, so nothing needs to be running first.

## What it runs against

Everything the suite touches lives under `e2e/.tmp` (gitignored), and `npm test`
wipes it first so each run starts with no libraries and an empty cache:

- `.tmp/config` is the app's `$XDG_CONFIG_HOME` — **your own libraries and cache
  are never opened, let alone edited**;
- `.tmp/music` is a seeded four-track library (zero-content `.mp3` files two
  folders deep, so the path-derived tags give an artist and an album);
- `.tmp/virtual-music` is a separate 420-track library used to prove the table
  keeps a bounded DOM while scrolling in both directions;
- `.tmp/sink` is the empty directory the sync test copies into.

Port 7374, deliberately not the app's usual 7373, so a `clojure -M:run` you have
open is neither torn down nor borrowed.

## Conventions

- **One worker, in order.** Dapr is a single-user desktop app: one server, one
  state atom, so "which library is the source" is global. Parallel tests would
  not be isolated — they would be editing the same session.
- **Helpers in `support/app.ts`** speak the UI's language (`createLibrary`,
  `choose`, `waitForIdle`) so specs read as what a user does.
- **`waitForIdle`** waits for the status strip to go quiet, which is the page's
  own answer to "has the background refresh finished?".
- Failures keep a trace (`npx playwright show-trace`) and a screenshot.
