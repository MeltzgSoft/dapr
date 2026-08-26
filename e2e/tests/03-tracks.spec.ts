import { expect, test } from '@playwright/test';
import { choose, ensureLibrary, trackRows, waitForIdle } from '../support/app';
import { MUSIC_DIR, SINK_DIR, TRACKS } from '../support/paths';

test.describe('the track picker', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await ensureLibrary(page, 'E2E Source', MUSIC_DIR);
    await ensureLibrary(page, 'E2E Sink', SINK_DIR);
    await choose(page, 'source', 'E2E Source');
    // A sink as well as a source: without one there is no capacity at all, so
    // every row is locked and nothing about selection can be exercised.
    await choose(page, 'sink', 'E2E Sink');
  });

  test('fills itself from a background scan, with no reload', async ({ page }) => {
    // Nothing here navigates or reloads: the rows arrive because the server
    // pushed "the track table moved" and the region re-fetched its fragment.
    // See 07-push.spec.ts for the isolated proof of that path.
    await expect(trackRows(page)).toHaveCount(TRACKS.length);
    await expect(page.locator('#track-table header')).toContainText(`Tracks (${TRACKS.length})`);
  });

  test('shows the tags derived from each path', async ({ page }) => {
    const first = trackRows(page).filter({ hasText: '01 Opening' });
    await expect(first).toContainText('Alice');
    await expect(first).toContainText('Debut');
    await expect(first).toContainText('1000 B');
  });

  test('sorts by a clicked column, and reverses on the second click', async ({ page }) => {
    await page.locator('#track-table thead').getByRole('button', { name: 'Title' }).click();
    await expect(trackRows(page).first()).toContainText('01 Opening');
    await page.locator('#track-table thead').getByRole('button', { name: /^Title/ }).click();
    await expect(trackRows(page).first()).toContainText('04 Fourth');
  });

  test('the column browser filters, and its ✓ ticks a whole facet', async ({ page }) => {
    await test.step('artists and albums are listed from the catalog', async () => {
      await expect(page.locator('#artists li')).toContainText(['All', 'Alice', 'Bob']);
    });

    await page.locator('#artists li', { hasText: 'Alice' }).getByRole('button', { name: 'Alice' }).click();
    await expect(trackRows(page)).toHaveCount(3);
    await expect(page.locator('#albums li')).toContainText(['All', 'Debut', 'Encore']);

    await test.step('narrowing to an album narrows further', async () => {
      await page.locator('#albums li', { hasText: 'Debut' }).getByRole('button', { name: 'Debut' }).click();
      await expect(trackRows(page)).toHaveCount(2);
    });

    await test.step('the ✓ ticks every track under a facet without narrowing the view', async () => {
      await page.locator('#artists li', { hasText: 'Bob' }).getByRole('button', { name: '✓' }).click();
      // Still filtered to Alice/Debut — the tick did not move the view.
      await expect(trackRows(page)).toHaveCount(2);
      await page.locator('#artists li', { hasText: 'All' }).getByRole('button', { name: 'All' }).click();
      await expect(trackRows(page)).toHaveCount(TRACKS.length);
      await expect(trackRows(page).filter({ hasText: '04 Fourth' })
        .locator('input[type="checkbox"]')).toBeChecked();
    });

    await test.step('and unticks them again', async () => {
      await page.locator('#artists li', { hasText: 'Bob' }).getByRole('button', { name: '✓' }).click();
      await expect(trackRows(page).filter({ hasText: '04 Fourth' })
        .locator('input[type="checkbox"]')).not.toBeChecked();
    });
  });

  test('the search box narrows a facet list as you type', async ({ page }) => {
    await page.locator('#facets .facet', { hasText: 'Artist' }).locator('input[name="q"]').fill('bo');
    await expect(page.locator('#artists li')).toHaveCount(2); // All + Bob
    await expect(page.locator('#artists')).toContainText('Bob');
    await expect(page.locator('#artists')).not.toContainText('Alice');
  });

  test('with no sink there is nowhere to put anything, so the rows are locked', async ({ page }) => {
    // The blank option is "no sink": the tracks stay browsable, but nothing can
    // be picked, because there is nowhere for it to go.
    await page.locator('#sink-library').selectOption('');
    await waitForIdle(page);

    await expect(page.locator('#capacity')).toContainText('Select a sink');
    // Not "clicking it does nothing": the capacity rule disables the row, which
    // is the difference between a refusal you can see and one you cannot.
    await expect(trackRows(page).filter({ hasText: '01 Opening' })
      .locator('input[type="checkbox"]')).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Preview' })).toBeDisabled();
  });
});
