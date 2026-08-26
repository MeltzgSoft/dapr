'use strict';

const net = require('node:net');

/**
 * Port the backend uses when nothing is in the way — the same default
 * `dapr.web.server/default-port` picks, so launching through Electron and
 * launching with `clojure -M:run` land on the same URL when the machine is idle.
 */
const BASE_PORT = 7373;

/**
 * How far above BASE_PORT to look before giving up. Generous enough to survive a
 * handful of stale Dapr instances and whatever else the developer has bound, but
 * bounded: a machine with 200 consecutive ports taken has something wrong with it
 * that silently scanning to 65535 would only hide.
 */
const DEFAULT_LIMIT = 200;

/**
 * Whether `port` can be bound on `host` right now.
 *
 * Asked by binding it rather than by connecting to it: a port with nothing
 * listening refuses a connection, but so does one held by a socket in TIME_WAIT
 * or bound by another user, and only an actual bind distinguishes "free" from
 * "quiet". `exclusive` keeps SO_REUSEADDR from reporting a shared port as
 * available when the backend would then fail to take it for itself.
 */
function isFree(port, host = '127.0.0.1') {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.on('error', () => resolve(false));
    server.listen({ port, host, exclusive: true }, () => {
      server.close(() => resolve(true));
    });
  });
}

/**
 * The first bindable port at or above `start`, scanning upward.
 *
 * Note this is inherently advisory: nothing reserves the port between this
 * answering and the backend binding it, so a caller that loses that race should
 * ask again rather than treat the answer as a promise (see backend.js, which
 * retries on the next port up).
 */
async function firstAvailablePort(start = BASE_PORT, { host = '127.0.0.1', limit = DEFAULT_LIMIT } = {}) {
  for (let port = start; port < start + limit; port++) {
    if (await isFree(port, host)) return port;
  }
  throw new Error(
    `No free port between ${start} and ${start + limit - 1} — is something holding that whole range?`,
  );
}

module.exports = { BASE_PORT, DEFAULT_LIMIT, isFree, firstAvailablePort };
