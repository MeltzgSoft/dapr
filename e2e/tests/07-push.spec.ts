import { expect, test } from '@playwright/test';
import { choose, ensureLibrary, trackRows } from '../support/app';
import { MUSIC_DIR, TRACKS } from '../support/paths';

/**
 * The push path (dapr.web.events). Regions keep a slow fallback timer, so an
 * update arriving *quickly* is the evidence that it was pushed rather than
 * polled — every assertion here is deliberately well inside that fallback.
 */
test.describe('server-pushed updates', () => {
  test('the page opens an event stream', async ({ page }) => {
    const stream = page.waitForResponse((r) => new URL(r.url()).pathname === '/events');
    await page.goto('/');
    const response = await stream;

    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toContain('text/event-stream');
  });

  test('regions listen for their own notification, and keep the timer as a fallback', async ({ page }) => {
    await page.goto('/');
    const trigger = await page.locator('#track-table').getAttribute('hx-trigger');
    expect(trigger).toMatch(/^sse:region-table, every \d+s$/);
  });

  test('a change the page never asked for still reaches it', async ({ page }) => {
    await page.goto('/');
    await ensureLibrary(page, 'E2E Source', MUSIC_DIR);
    await choose(page, 'source', 'E2E Source');
    await expect(trackRows(page)).toHaveCount(TRACKS.length);

    await page.getByRole('button', { name: 'Activity & Logs' }).click();
    const log = page.locator('#log-lines');
    await expect(log).toContainText('Refreshed');
    const before = ((await log.textContent()) ?? '').match(/Refreshed/g)?.length ?? 0;

    // Issued outside the page: this request's response goes to Playwright, not
    // to the browser, so nothing comes back to the DOM through htmx. If the log
    // grows, the server pushed the notification and the region re-fetched itself.
    await page.request.post('/actions/refresh');

    await expect
      .poll(async () => ((await log.textContent()) ?? '').match(/Refreshed/g)?.length ?? 0,
            { timeout: 8000 })
      .toBeGreaterThan(before);

    await page.locator('#activity .drawer').getByRole('button', { name: 'Close' }).click();
  });
});
