'use strict';

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const { firstAvailablePort } = require('./port');

/** How long to wait for the server to answer before calling the launch failed. */
const READY_TIMEOUT_MS = 240_000;
/** Gap between readiness probes. */
const PROBE_INTERVAL_MS = 250;
/** How many ports to try before giving up (see the race note in port.js). */
const BIND_ATTEMPTS = 5;
/** Grace period between asking the JVM to stop and killing it outright. */
const SHUTDOWN_GRACE_MS = 5_000;

const REPO_ROOT = path.resolve(__dirname, '..', '..');

/**
 * How to start the backend.
 *
 * A packaged app ships the uberjar beside the Electron resources, so that is
 * looked for first; a checkout falls back to whatever `clojure -T:build uber`
 * last produced, and finally to running from source. The last of those is what
 * makes `npm start` useful in a checkout with no jar built — it is slower to
 * boot, but it is the same application.
 */
function launchCommand(port, host) {
  const args = ['--port', String(port), '--host', host, '--no-browser'];
  const jar = bundledJar() || builtJar();
  if (jar) {
    return {
      command: javaCommand(),
      args: ['--enable-native-access=ALL-UNNAMED', '-jar', jar, ...args],
      describe: `${javaCommand()} -jar ${path.basename(jar)}`,
    };
  }
  return { command: 'clojure', args: ['-M:run', ...args], describe: 'clojure -M:run' };
}

/**
 * Which `java` to run.
 *
 * A packaged app carries its own trimmed runtime (see scripts/stage.js), so an
 * installed Dapr does not require the user to have a JDK — which is what makes
 * it an application rather than a developer tool. Falling back to whatever is on
 * PATH is for a checkout, where there is no staged runtime; `DAPR_JAVA` forces a
 * specific one, which is occasionally useful when debugging against another JDK.
 */
function javaCommand() {
  if (process.env.DAPR_JAVA) return process.env.DAPR_JAVA;
  return bundledJava() || 'java';
}

/** The runtime staged into a packaged app, or null when running from a checkout. */
function bundledJava() {
  const dir = process.resourcesPath;
  if (!dir) return null;
  const exe = path.join(dir, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  return fs.existsSync(exe) ? exe : null;
}

/** The jar staged into a packaged app, or null when running from a checkout. */
function bundledJar() {
  // process.resourcesPath only exists inside a packaged Electron app.
  const dir = process.resourcesPath;
  if (!dir) return null;
  const jar = path.join(dir, 'dapr.jar');
  return fs.existsSync(jar) ? jar : null;
}

/** The most recent uberjar in target/, or null when none has been built. */
function builtJar() {
  const dir = path.join(REPO_ROOT, 'target');
  if (!fs.existsSync(dir)) return null;
  const jars = fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.jar'))
    .map((f) => path.join(dir, f))
    .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
  return jars[0] || null;
}

/** Resolve once the server answers on `url`, or reject on timeout. */
async function waitForReady(url, { signal, timeout = READY_TIMEOUT_MS } = {}) {
  const deadline = Date.now() + timeout;
  for (;;) {
    if (signal?.aborted) throw new Error(signal.reason || 'backend exited before it was ready');
    try {
      const res = await fetch(url, { redirect: 'manual' });
      // Any answer at all means the server is up and listening; the status only
      // has to be something an HTTP server produced.
      if (res.status > 0) return;
    } catch {
      // Connection refused while the JVM is still starting: expected, keep waiting.
    }
    if (Date.now() > deadline) throw new Error(`Dapr did not answer at ${url} within ${timeout} ms`);
    await new Promise((r) => setTimeout(r, PROBE_INTERVAL_MS));
  }
}

/**
 * Pick a port, start the backend on it, and resolve once it is serving.
 *
 * Returns `{ child, port, url, stop }`. `onExit` is called if the process ends
 * on its own — which is the normal path when the user picks Quit in the UI, and
 * how the shell learns to close.
 */
async function startBackend({ host = '127.0.0.1', startPort, onExit, onLog } = {}) {
  let candidate = startPort;
  let lastError;

  for (let attempt = 0; attempt < BIND_ATTEMPTS; attempt++) {
    const port = await firstAvailablePort(candidate);
    const url = `http://${host}:${port}/`;
    const { command, args, describe } = launchCommand(port, host);
    onLog?.(`starting backend: ${describe} --port ${port}`);

    const child = spawn(command, args, {
      cwd: REPO_ROOT,
      // The JVM's own stdout carries the "Dapr is running at …" line and any
      // stack trace, which is the only diagnosis available when a launch fails.
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, DAPR_NO_BROWSER: '1' },
    });

    let exited = null;
    const abort = new AbortController();
    child.stdout.on('data', (b) => onLog?.(String(b).trimEnd()));
    child.stderr.on('data', (b) => onLog?.(String(b).trimEnd()));
    child.once('exit', (code, sigterm) => {
      exited = { code, sigterm };
      abort.abort(`backend exited (code ${code}${sigterm ? `, signal ${sigterm}` : ''}) before it was ready`);
    });
    child.once('error', (err) => abort.abort(`could not run ${command}: ${err.message}`));

    try {
      await waitForReady(url, { signal: abort.signal });
      // Only now hand the caller the exit hook: until the server is up, an exit
      // means "try the next port", not "the user quit".
      child.once('exit', (code, sigterm) => onExit?.(code, sigterm));
      if (exited) throw new Error(abort.signal.reason);
      return { child, port, url, stop: () => stopBackend(child) };
    } catch (err) {
      lastError = err;
      if (!exited) stopBackend(child);
      // Something took the port between the check and the bind (or that port is
      // unusable for another reason) — move past it and try again.
      candidate = port + 1;
      onLog?.(`backend did not come up on ${port}: ${err.message}`);
    }
  }
  throw new Error(`Could not start the Dapr backend after ${BIND_ATTEMPTS} attempts: ${lastError?.message}`);
}

/**
 * Ask the backend to stop, and insist if it does not.
 *
 * The polite signal matters: the JVM's shutdown hook is what snapshots the cache
 * DB (see dapr.system), so a killed backend loses whatever a scan had just
 * learned. Windows has no SIGTERM worth the name, so there the kill is the only
 * option and the snapshot has to come from the app quitting itself.
 */
function stopBackend(child, { grace = SHUTDOWN_GRACE_MS } = {}) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  child.kill(process.platform === 'win32' ? 'SIGKILL' : 'SIGTERM');
  const timer = setTimeout(() => {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
  }, grace);
  // Don't let the grace timer hold the event loop open once the child is gone.
  timer.unref?.();
  child.once('exit', () => clearTimeout(timer));
}

module.exports = { startBackend, stopBackend, waitForReady, launchCommand, REPO_ROOT };
