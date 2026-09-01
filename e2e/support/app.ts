import { expect, Locator, Page } from '@playwright/test';
import path from 'node:path';

/**
 * Helpers that speak the UI's language, so the specs read as what a user does
 * rather than as selectors. Everything here goes through the rendered page —
 * none of it reaches past the browser into the server.
 */

/** Escape a string for use inside a RegExp. */
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * The status strip carries `idle` when nothing is running and `active` while a
 * job is in flight, which makes it the page's own answer to "has the background
 * refresh finished?" — the question every sync assertion depends on.
 */
export async function waitForIdle(page: Page): Promise<void> {
  // Two consecutive readings, because one is not enough: a scan that has been
  // queued but has not started yet leaves the strip idle, and a test that took
  // that as "finished" would race the work it was waiting for.
  await expect
    .poll(async () => {
      const before = await page.locator('#status-bar').getAttribute('class');
      await page.waitForTimeout(400);
      const after = await page.locator('#status-bar').getAttribute('class');
      return before === 'idle' && after === 'idle';
    }, { timeout: 60_000 })
    .toBe(true);
}

export async function openSettings(page: Page): Promise<void> {
  if (await page.locator('#overlay .modal').count() === 0) {
    await page.getByRole('button', { name: 'Libraries & Settings' }).click();
  }
  await expect(page.locator('#overlay .modal')).toBeVisible();
}

export async function closeSettings(page: Page): Promise<void> {
  const close = page.locator('#overlay .modal > footer').getByRole('button', { name: 'Close' });
  if (await close.count() > 0) await close.click();
  await expect(page.locator('#overlay .modal')).toHaveCount(0);
}

/** A folder-browser entry button, matched on the name after its 📁 prefix. */
function entry(page: Page, name: string): Locator {
  return page.locator('#browser-panel .entry-list button')
    .filter({ hasText: new RegExp(`${esc(name)}$`) });
}

/**
 * Walk the open folder browser down to `absPath`, one click per path segment,
 * starting from the filesystem root in the Places list. This is the folder
 * browser's own test as much as it is a way to get somewhere: every step is a
 * real listing of a real directory, served by the same `dir-children!` the
 * mtp:// and smb:// backends implement.
 */
export async function browseTo(page: Page, absPath: string): Promise<void> {
  await entry(page, path.sep === '\\' ? 'Computer' : 'Computer /').first().click();
  for (const segment of absPath.split(path.sep).filter(Boolean)) {
    await entry(page, segment).first().click();
    // The listing is loaded on a background thread and polled for, so wait for
    // the crumb to appear rather than for a fixed time.
    await expect(page.locator('#browser-panel .crumbs')).toContainText(segment);
  }
}

/** Create a library through the settings panel and the folder browser. */
export async function createLibrary(page: Page, name: string, absPath: string): Promise<void> {
  await openSettings(page);
  await page.locator('#overlay').getByRole('button', { name: /Local files/ }).click();
  await page.locator('#overlay input[name="name"]').fill(name);
  await page.locator('#overlay').getByRole('button', { name: 'Browse…' }).click();
  await expect(page.locator('#browser-panel')).toBeVisible();
  await browseTo(page, absPath);
  await page.getByRole('button', { name: 'Use this folder' }).click();
  await expect(page.locator('#overlay .root-row')).toContainText(path.basename(absPath));
  await page.locator('#overlay').getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('#sync-bar option', { hasText: name })).toHaveCount(2);
  await closeSettings(page);
}

/**
 * Create the library only if this run has not already made it. The server keeps
 * its state for the whole run, so one creation serves every spec — and each spec
 * can still be run on its own.
 */
export async function ensureLibrary(page: Page, name: string, absPath: string): Promise<void> {
  if (await page.locator('#sync-bar option', { hasText: name }).count() === 0) {
    await createLibrary(page, name, absPath);
  }
}

/** Choose the source or sink library by name, and wait for its scan to finish. */
export async function choose(page: Page, role: 'source' | 'sink', name: string): Promise<void> {
  // By id, not by the label's text: both pickers list the same library names, so
  // "the label containing Source" also matches the sink's.
  const select = page.locator(`#${role}-library`);
  // Wait for the server's answer, not just for the DOM to change: selectOption
  // sets the value client-side whether or not htmx ever sent anything, so
  // asserting on the element alone would pass through a lost selection.
  const taken = page.waitForResponse((r) => r.url().includes(`/actions/select-${role}`));
  await select.selectOption({ label: name });
  await taken;
  await waitForIdle(page);
}

export function trackRows(page: Page): Locator {
  return page.locator('#track-table tbody tr.track-row');
}

/** Tick every currently materialized row, one at a time. */
export async function checkAllRows(page: Page): Promise<void> {
  const count = await trackRows(page).count();
  for (let i = 0; i < count; i++) {
    const box = trackRows(page).nth(i).locator('input[type="checkbox"]');
    if (!(await box.isChecked())) {
      await box.check();
      await expect(trackRows(page).nth(i).locator('input[type="checkbox"]')).toBeChecked();
    }
  }
}
