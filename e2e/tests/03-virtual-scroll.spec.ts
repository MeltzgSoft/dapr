import { expect, test } from '@playwright/test';
import { choose, ensureLibrary, trackRows } from '../support/app';
import { VIRTUAL_MUSIC_DIR, VIRTUAL_TRACK_COUNT } from '../support/paths';

const WINDOW_SIZE = 200;
const FINAL_START = VIRTUAL_TRACK_COUNT - WINDOW_SIZE;

test('virtualizes a large library while scrolling, sorting and filtering', async ({ page }) => {
  await page.goto('/');
  await ensureLibrary(page, 'E2E Virtual', VIRTUAL_MUSIC_DIR);
  await choose(page, 'source', 'E2E Virtual');

  const table = page.locator('#track-table');
  const body = table.locator('tbody');
  const scroll = page.locator('#track-scroll');

  await expect(table.locator('header')).toContainText(`Tracks (${VIRTUAL_TRACK_COUNT})`);

  await test.step('facet selectors match their case-insensitive table-column order', async () => {
    await expect(page.locator('#artists .pick')).toHaveText(['All', 'alpha', 'Beta', 'charlie']);
    await expect(page.locator('#albums .pick')).toHaveText(['All', 'apple', 'middle', 'Zebra']);
  });

  await test.step('a selected column sort is preserved in later windows', async () => {
    await table.locator('thead').getByRole('button', { name: 'Artist' }).click();
    await expect(body).toHaveAttribute('data-start', '0');
    await expect(body).toHaveAttribute('data-sort', 'artist');
    await expect(body).toHaveAttribute('data-dir', 'asc');
    await expect(trackRows(page).first()).toContainText('alpha');

    const windowRequest = page.waitForRequest(request =>
      new URL(request.url()).pathname === '/fragments/table-body');
    await scroll.evaluate((element: HTMLElement) => element.scrollTo({ top: element.scrollHeight }));
    const requestUrl = new URL((await windowRequest).url());
    expect(requestUrl.searchParams.get('sort')).toBe('artist');
    expect(requestUrl.searchParams.get('dir')).toBe('asc');
    await expect(body).toHaveAttribute('data-start', String(FINAL_START));
    await expect(trackRows(page).first()).toContainText('Beta');
    await expect(trackRows(page).last()).toContainText('charlie');
    await expect(trackRows(page).filter({ hasText: 'alpha' })).toHaveCount(0);
    await scroll.evaluate((element: HTMLElement) => element.scrollTo({ top: 0 }));
    await expect(body).toHaveAttribute('data-start', '0');
  });

  await test.step('only the first bounded window is initially materialized', async () => {
    await table.locator('thead').getByRole('button', { name: 'Title' }).click();
    await expect(body).toHaveAttribute('data-start', '0');
    await expect(trackRows(page)).toHaveCount(WINDOW_SIZE);
    await expect(trackRows(page).first()).toContainText('0001 Virtual');
    await expect(trackRows(page).filter({ hasText: '0420 Virtual' })).toHaveCount(0);
  });

  await test.step('scrolling to the bottom swaps in the final window without growing the DOM', async () => {
    await scroll.evaluate((element: HTMLElement) => element.scrollTo({ top: element.scrollHeight }));
    await expect(body).toHaveAttribute('data-start', String(FINAL_START));
    await expect(trackRows(page)).toHaveCount(WINDOW_SIZE);
    await expect(trackRows(page).first()).toContainText('0221 Virtual');
    await expect(trackRows(page).last()).toContainText('0420 Virtual');
  });

  await test.step('scrolling upward restores the first window', async () => {
    await scroll.evaluate((element: HTMLElement) => element.scrollTo({ top: 0 }));
    await expect(body).toHaveAttribute('data-start', '0');
    await expect(trackRows(page)).toHaveCount(WINDOW_SIZE);
    await expect(trackRows(page).first()).toContainText('0001 Virtual');
  });

  await test.step('reset sort restores Artist, Album, Disc, Track order', async () => {
    await table.getByRole('button', { name: 'Reset sort' }).click();
    await expect(body).toHaveAttribute('data-start', '0');
    await expect(body).not.toHaveAttribute('data-sort');
    await expect(trackRows(page).first()).toContainText('alpha');
    await expect(trackRows(page).first()).toContainText('apple');
    await expect(table.getByRole('button', { name: 'Reset sort' })).toHaveCount(0);

    await scroll.evaluate((element: HTMLElement) => element.scrollTo({ top: element.scrollHeight }));
    await expect(body).toHaveAttribute('data-start', String(FINAL_START));
    await expect(trackRows(page).last()).toContainText('charlie');
    await expect(trackRows(page).last()).toContainText('Zebra');
    await scroll.evaluate((element: HTMLElement) => element.scrollTo({ top: 0 }));
    await expect(body).toHaveAttribute('data-start', '0');
  });

  await test.step('sorting and filtering reset a stale scroll window', async () => {
    await table.locator('thead').getByRole('button', { name: 'Title' }).click();
    await expect(body).toHaveAttribute('data-sort', 'title');
    await expect(body).toHaveAttribute('data-dir', 'asc');
    await scroll.evaluate((element: HTMLElement) => element.scrollTo({ top: element.scrollHeight }));
    await expect(body).toHaveAttribute('data-start', String(FINAL_START));

    // The second Title click reverses the existing ascending sort.
    await table.locator('thead').getByRole('button', { name: /^Title/ }).click();
    await expect(body).toHaveAttribute('data-start', '0');
    await expect(trackRows(page)).toHaveCount(WINDOW_SIZE);
    await expect(trackRows(page).first()).toContainText('0420 Virtual');

    await scroll.evaluate((element: HTMLElement) => element.scrollTo({ top: element.scrollHeight }));
    await expect(body).toHaveAttribute('data-start', String(FINAL_START));
    await page.locator('#artists li', { hasText: 'alpha' })
      .getByRole('button', { name: 'alpha' }).click();
    await expect(body).toHaveAttribute('data-start', '0');
    await expect(table.locator('header')).toContainText('Tracks (140)');
    await expect(trackRows(page)).toHaveCount(140);
    await expect(trackRows(page).first()).toContainText('alpha');
    await expect(trackRows(page).filter({ hasText: 'Beta' })).toHaveCount(0);
    await expect(trackRows(page).filter({ hasText: 'charlie' })).toHaveCount(0);
  });
});
