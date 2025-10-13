// dlr-status.spec.js

import { test, expect } from '@playwright/test';

// --- Mock Data ---
// This is the first set of data the page will load.
const initialDlrData = [
    {
        forwardingId: 'internal-id-111',
        smscid: 'vendor-id-aaa',
        fromAddress: 'SENDER1',
        toAddress: '+11111111111',
        status: 'DELIVRD',
        receivedAt: '2025-09-18T12:30:00Z',
        sentAt: '2025-09-18T12:30:05Z',
        processedAt: '2025-09-18T12:30:10Z',
        forwardDate: '2025-09-18T12:30:15Z',
    },
    {
        forwardingId: 'internal-id-222',
        smscid: 'vendor-id-bbb',
        fromAddress: 'SENDER2',
        toAddress: '+22222222222',
        status: 'EXPIRED',
        receivedAt: '2025-09-18T13:00:00Z',
        sentAt: '2025-09-18T13:00:05Z',
        processedAt: '2025-09-18T13:00:10Z',
        forwardDate: '2025-09-18T13:00:15Z',
    },
];

// This is the second set of data that will load after clicking "Refresh".
const refreshedDlrData = [
    {
        forwardingId: 'internal-id-333',
        smscid: 'vendor-id-ccc',
        fromAddress: 'SENDER3',
        toAddress: '+33333333333',
        status: 'ACCEPTED',
        receivedAt: '2025-09-18T14:00:00Z',
        sentAt: '2025-09-18T14:00:05Z',
        processedAt: '2025-09-18T14:00:10Z',
        forwardDate: '2025-09-18T14:00:15Z',
    },
];

test.describe('DLR Status Page', () => {
    test('should display DLR data and update correctly on refresh', async ({ page }) => {
        // --- Step 1: Mock the initial API call ---
        // We intercept the GET request to '/api/dlr/status' before navigating.
        // Playwright will 'fulfill' this request with our mock data instead of calling the real backend.
        await page.route('**/api/dlr/status', async (route) => {
            // The first time the page loads, it will get 'initialDlrData'.
            console.log('Fulfilling initial API request for /api/dlr/status');
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify(initialDlrData),
            });
        });

        // --- Step 2: Navigate to the DLR page ---
        // Make sure this URL is correct for your application's routing.
        await page.goto('/dlr-status');

        // --- Step 3: Verify the initial data load ---
        // Check that the title is visible.
        await expect(page.locator('#DLRPageId')).toHaveCount(1);

        // The component sets a loading state. We can wait for the table to contain
        // data, which implies loading is finished. Let's find the row for the first item.
        // getByRole('cell', { name: 'DELIVRD' }) finds the cell with the status.
        const firstRowStatus = page.getByRole('cell', { name: 'DELIVRD' });
        await expect(firstRowStatus).toBeVisible({ timeout: 10000 });

        // Now, verify content from both initial items is present.
        await expect(page.getByRole('cell', { name: 'SENDER1' })).toBeVisible();
        await expect(page.getByRole('cell', { name: '+22222222222' })).toBeVisible();

        // --- Step 4: Mock the API call for the refresh action ---
        // We need to set up a new mock for the *next* time the API is called.
        // We use `unroute` to remove the old mock and `route` to add a new one.
        await page.unroute('**/api/dlr/status');
        await page.route('**/api/dlr/status', async (route) => {
            console.log('Fulfilling REFRESHED API request for /api/dlr/status');
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify(refreshedDlrData),
            });
        });

        // --- Step 5: Click the refresh button ---
        // The button has no text, so we locate it by its icon.
        // This selector finds a button that contains an <i> element with the class 'mdi-refresh'.
        const refreshButton = page.locator('button:has(i.mdi-refresh)');
        await refreshButton.click();

        // --- Step 6: Verify the updated data ---
        // Wait for the new data to appear in the table.
        const newRowStatus = page.getByRole('cell', { name: 'ACCEPTED' });
        await expect(newRowStatus).toBeVisible({ timeout: 10000 });

        // Check that content from the refreshed data is visible.
        await expect(page.getByRole('cell', { name: 'SENDER3' })).toBeVisible();
        await expect(page.getByRole('cell', { name: '+33333333333' })).toBeVisible();

        // Finally, ensure the old data is GONE. This is an important check!
        await expect(page.getByRole('cell', { name: 'SENDER1' })).not.toBeVisible();
        await expect(page.getByRole('cell', { name: 'DELIVRD' })).not.toBeVisible();
    });
});