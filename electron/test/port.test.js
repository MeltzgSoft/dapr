'use strict';

const assert = require('node:assert/strict');
const net = require('node:net');
const { test } = require('node:test');

const { BASE_PORT, isFree, firstAvailablePort } = require('../src/port');

/** Hold `port` for the duration of `fn`, the way a running Dapr would. */
async function holding(ports, fn) {
  const servers = [];
  try {
    for (const port of ports) {
      const server = net.createServer();
      await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen({ port, host: '127.0.0.1', exclusive: true }, resolve);
      });
      servers.push(server);
    }
    return await fn();
  } finally {
    for (const s of servers) await new Promise((r) => s.close(r));
  }
}

/**
 * A high base for the scan tests, so they neither collide with a Dapr the
 * developer is running on 7373 nor depend on it being absent.
 */
const SCAN_BASE = 47_373;

test('the default base port is the one the server itself defaults to', () => {
  assert.equal(BASE_PORT, 7373);
});

test('a port nothing holds is free, and one held is not', async () => {
  await holding([SCAN_BASE], async () => {
    assert.equal(await isFree(SCAN_BASE), false);
    assert.equal(await isFree(SCAN_BASE + 1), true);
  });
});

test('the scan returns the base port when it is available', async () => {
  assert.equal(await firstAvailablePort(SCAN_BASE + 10), SCAN_BASE + 10);
});

test('the scan steps over a run of taken ports and takes the first one above', async () => {
  const taken = [SCAN_BASE + 20, SCAN_BASE + 21, SCAN_BASE + 22];
  await holding(taken, async () => {
    assert.equal(await firstAvailablePort(SCAN_BASE + 20), SCAN_BASE + 23);
  });
});

test('a fully occupied range is an error rather than a silent walk to 65535', async () => {
  await holding([SCAN_BASE + 30, SCAN_BASE + 31], async () => {
    await assert.rejects(
      () => firstAvailablePort(SCAN_BASE + 30, { limit: 2 }),
      /No free port between/,
    );
  });
});
