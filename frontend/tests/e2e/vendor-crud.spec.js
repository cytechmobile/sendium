import { test, expect } from '@playwright/test';

// --- Configuration ---
test.describe.configure({ mode: 'serial' });

// IMPORTANT: Replace with a valid admin API key from your backend.
const ADMIN_API_KEY = 'your-admin-api-key';

const SMPP_VENDOR_ID = `e2e-smpp-vendor-${Date.now()}`;
const HTTP_VENDOR_ID = `e2e-http-vendor-${Date.now()}`;
const HTTP_VENDOR_API_KEY_EDITED = `edited-api-key-${Date.now()}`;

test.describe('Vendor CRUD Page (Live Backend)', () => {

    // --- AUTOMATIC CLEANUP ---
    test.beforeAll(async ({ request }) => {
        console.log('--- E2E Pre-Test Cleanup: Deleting leftover test vendors ---');
        try {
            const response = await request.get('/api/admin/vendors', {
                headers: { 'X-API-Key': ADMIN_API_KEY }
            });
            expect(response.ok(), 'Cleanup: Failed to fetch vendors.').toBe(true);
            const allVendors = await response.json();

            for (const vendor of allVendors) {
                if (vendor.id.startsWith('e2e-')) {
                    console.log(`Cleanup: Deleting leftover vendor: ${vendor.id}`);
                    const deleteResponse = await request.delete(`/api/admin/vendors/${vendor.id}`, {
                        headers: { 'X-API-Key': ADMIN_API_KEY }
                    });
                    expect(deleteResponse.ok(), `Cleanup: Failed to delete vendor ${vendor.id}`).toBe(true);
                }
            }
            console.log('Cleanup successful.');
        } catch (error) {
            console.error('CRITICAL: E2E cleanup failed.', error);
            throw new Error('E2E Pre-test cleanup failed. Ensure your backend is running and ADMIN_API_KEY is correct.');
        }
    });

    test('should create a new SMPP vendor', async ({ page }) => {
        await page.goto('/admin/vendors');

        await page.getByTestId('new-vendor-btn').click();
        const dialog = page.getByTestId('edit-dialog');
        await expect(dialog).toBeVisible();

        // Fill the form for an SMPP vendor
        await dialog.locator('#vendor-id-input').fill(SMPP_VENDOR_ID);
        // Type is SMPP by default, so no need to change it.
        await dialog.locator('#smpp-host-input').fill('localhost');
        await dialog.locator('#smpp-port-input').fill('2775');
        await dialog.locator('#smpp-system-id-input').fill('smpp_user');
        await dialog.locator('#smpp-password-input').fill('smpp_pass');
        await dialog.locator('#vendor-tps-input').fill('10');

        await dialog.getByTestId('dialog-save-btn').click();

        // Assertions
        await expect(dialog).not.toBeVisible();
        await expect(page.getByTestId('snackbar')).toContainText('Vendor created successfully');
        await expect(page.getByTestId(`vendor-row-${SMPP_VENDOR_ID}`)).toBeVisible();
    });

    test('should create a new HTTP vendor', async ({ page }) => {
        await page.goto('/admin/vendors');

        await page.getByTestId('new-vendor-btn').click();
        const dialog = page.getByTestId('edit-dialog');
        await expect(dialog).toBeVisible();

        // Fill the form for an HTTP vendor
        await dialog.locator('#vendor-id-input').fill(HTTP_VENDOR_ID);
        await dialog.getByTestId('vendor-type-select').click();
        await page.getByRole('option', { name: 'HTTP' }).click();

        await dialog.locator('#http-api-key-input').fill('initial-api-key');
        await dialog.locator('#http-api-url-input').fill('http://vendor.example.com/api');
        await dialog.locator('#vendor-tps-input').fill('50');

        await dialog.getByTestId('dialog-save-btn').click();

        // Assertions
        await expect(dialog).not.toBeVisible();
        await expect(page.getByTestId('snackbar')).toContainText('Vendor created successfully');
        await expect(page.getByTestId(`vendor-row-${HTTP_VENDOR_ID}`)).toBeVisible();
    });

    test('should edit the existing HTTP vendor', async ({ page }) => {
        await page.goto('/admin/vendors');

        await page.getByTestId(`edit-vendor-btn-${HTTP_VENDOR_ID}`).click();
        const dialog = page.getByTestId('edit-dialog');
        await expect(dialog).toBeVisible();

        // Edit the API key and disable the vendor
        await dialog.locator('#http-api-key-input').fill(HTTP_VENDOR_API_KEY_EDITED);
        await dialog.locator('#vendor-enabled-switch').click();

        await dialog.getByTestId('dialog-save-btn').click();

        // Assertions
        await expect(dialog).not.toBeVisible();
        await expect(page.getByTestId('snackbar')).toContainText('Vendor updated successfully');

        // Verify the change is reflected in the table (check for disabled icon)
        const httpRow = page.getByTestId(`vendor-row-${HTTP_VENDOR_ID}`);
        await expect(httpRow.getByTestId('disabled-icon')).toBeVisible();
    });

    test('should delete the SMPP vendor', async ({ page }) => {
        await page.goto('/admin/vendors');

        await page.getByTestId(`delete-vendor-btn-${SMPP_VENDOR_ID}`).click();
        const dialog = page.getByTestId('delete-dialog');
        await expect(dialog).toBeVisible();
        await dialog.getByTestId('delete-dialog-confirm-btn').click();

        // Assertions
        await expect(dialog).not.toBeVisible();
        await expect(page.getByTestId('snackbar')).toContainText('Vendor deleted successfully');
        await expect(page.getByTestId(`vendor-row-${SMPP_VENDOR_ID}`)).not.toBeVisible();
    });

    test('should delete the HTTP vendor', async ({ page }) => {
        await page.goto('/admin/vendors');

        await page.getByTestId(`delete-vendor-btn-${HTTP_VENDOR_ID}`).click();
        const dialog = page.getByTestId('delete-dialog');
        await expect(dialog).toBeVisible();
        await dialog.getByTestId('delete-dialog-confirm-btn').click();

        // Assertions
        await expect(dialog).not.toBeVisible();
        await expect(page.getByTestId('snackbar')).toContainText('Vendor deleted successfully');
        await expect(page.getByTestId(`vendor-row-${HTTP_VENDOR_ID}`)).not.toBeVisible();
    });

});
