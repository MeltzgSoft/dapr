import { expect, test } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { checkAllRows, choose, ensureLibrary, trackRows, waitForIdle } from '../support/app';
import { MUSIC_DIR, SINK_DIR, TRACKS } from '../support/paths';

/**
 * The whole point of the application, driven through the browser and checked on
 * the filesystem: pick a source and a sink, tick everything, preview the plan,
 * sync, and find the files where the plan said they would be.
 */
test('syncs the ticked tracks onto the sink', async ({ page }) => {
  await page.goto('/');
  await ensureLibrary(page, 'E2E Source', MUSIC_DIR);
  await ensureLibrary(page, 'E2E Sink', SINK_DIR);

  await choose(page, 'source', 'E2E Source');
  await choose(page, 'sink', 'E2E Sink');
  await expect(trackRows(page)).toHaveCount(TRACKS.length);

  await test.step('the capacity meter names the sink it is about', async () => {
    await expect(page.locator('#capacity')).toContainText('Capacity — E2E Sink');
  });

  await test.step('ticking rows moves the capacity meter, which nothing in the response targets', async () => {
    const before = await page.locator('#capacity').textContent();
    await checkAllRows(page);
    // The meter is an out-of-band swap riding along with the table's response.
    await expect(page.locator('#capacity')).not.toHaveText(before ?? '');
  });

  await test.step('preview computes a plan of four adds', async () => {
    await page.getByRole('button', { name: 'Preview' }).click();
    await expect(page.locator('#controls .plan')).toContainText(`Add ${TRACKS.length}`);
  });

  await test.step('sync copies them, and no confirmation is needed once both scans finished', async () => {
    await page.getByRole('button', { name: 'Sync', exact: true }).click();
    await expect(page.locator('#overlay .modal')).toHaveCount(0);
    await waitForIdle(page);
  });

  await test.step('the files are on the sink, at their source-relative paths', async () => {
    for (const track of TRACKS) {
      const copied = path.join(SINK_DIR, track.rel);
      expect(fs.existsSync(copied), `${track.rel} should be on the sink`).toBe(true);
      expect(fs.statSync(copied).size).toBe(track.bytes);
    }
  });

  await test.step('and the table now shows where each track lives on the sink', async () => {
    await expect(trackRows(page).filter({ hasText: '01 Opening' }))
      .toContainText('Alice/Debut/01 Opening.mp3');
  });

  await test.step('a second preview has nothing left to do', async () => {
    await page.getByRole('button', { name: 'Preview' }).click();
    await expect(page.locator('#controls .plan')).toContainText('Add 0');
    await expect(page.getByRole('button', { name: 'Sync', exact: true })).toBeDisabled();
  });
});
