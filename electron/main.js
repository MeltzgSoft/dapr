'use strict';

/**
 * The Electron shell around the Dapr web UI.
 *
 * There is no application code here — the UI is the same server-rendered HTML a
 * browser gets, and the window is a BrowserWindow pointed at it. What the shell
 * owns is the lifecycle a browser tab cannot: choosing a port, starting the
 * backend on it, waiting for it to serve, and taking it down again on quit.
 *
 * The backend is deliberately launched with `--no-browser`: it would otherwise
 * open the user's own browser at the same URL, and they would end up with two
 * views of one application.
 */

const { app, BrowserWindow, dialog, shell } = require('electron');
const path = require('node:path');

const { startBackend } = require('./src/backend');
const { BASE_PORT } = require('./src/port');

const HOST = '127.0.0.1';

/** The running backend, once it is up. */
let backend = null;
let mainWindow = null;
/** Set while quitting, so the backend exiting doesn't look like a crash. */
let quitting = false;

function log(line) {
  // eslint-disable-next-line no-console
  console.log(`[dapr] ${line}`);
}

/**
 * One instance only. Two shells would mean two backends, and the second would
 * take a different port but the *same* cache DB — two writers over one file,
 * with the last snapshot winning. Focus the existing window instead.
 */
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });
  app.whenReady().then(main);
}

async function main() {
  try {
    backend = await startBackend({
      host: HOST,
      startPort: Number(process.env.DAPR_PORT) || BASE_PORT,
      onLog: log,
      onExit: (code, signal) => {
        // The UI's own Quit calls System/exit, so this is the normal way a
        // session ends — close down rather than leaving an empty window.
        if (quitting) return;
        log(`backend exited (code ${code}${signal ? `, signal ${signal}` : ''})`);
        app.quit();
      },
    });
    log(`serving at ${backend.url}`);
    createWindow(backend.url);
  } catch (err) {
    dialog.showErrorBox('Dapr could not start', String(err?.message || err));
    app.quit();
  }
}

function createWindow(url) {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 900,
    minHeight: 600,
    title: 'Dapr',
    backgroundColor: '#1a1a1a',
    // Nothing in the page needs Node, and the renderer only ever talks to the
    // local server over HTTP, so give it none of it. There is no preload script
    // because there is no bridge to expose: the UI's state lives on the server.
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      spellcheck: false,
    },
  });

  // Keep the window on the app. Anything else — a docs link, say — belongs in
  // the user's real browser, not in a chromeless window with no address bar.
  const isLocal = (target) => {
    try {
      const u = new URL(target);
      return u.hostname === HOST && u.port === String(backend.port);
    } catch {
      return false;
    }
  };
  mainWindow.webContents.on('will-navigate', (event, target) => {
    if (!isLocal(target)) {
      event.preventDefault();
      shell.openExternal(target);
    }
  });
  mainWindow.webContents.setWindowOpenHandler(({ url: target }) => {
    if (!isLocal(target)) shell.openExternal(target);
    return { action: 'deny' };
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
  mainWindow.loadURL(url);
}

/**
 * Closing the window ends the session on every platform, macOS included. The
 * usual macOS convention (stay resident with no windows) assumes a cheap app;
 * this one is holding a JVM, an open cache and possibly a device session, so
 * leaving it running invisibly is the wrong default.
 */
app.on('window-all-closed', () => app.quit());

// Take the backend down with us, and give its shutdown hook a moment to snapshot
// the cache before the process group goes away.
app.on('before-quit', () => {
  quitting = true;
  backend?.stop();
});

app.on('will-quit', () => backend?.stop());
