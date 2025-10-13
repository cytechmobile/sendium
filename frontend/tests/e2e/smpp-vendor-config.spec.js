import { test, expect } from '@playwright/test';

test.describe('Vendor Configuration Page', () => {
  const initialConfig = [{ id: "vendor1", name: "Test Vendor 1", host: "localhost", port: 2775, systemId: "testSysId1", password: "testPass1", enabled: true }];
  // The component's fetch returns text, so the textarea will have the raw text.
  // The backend resource sends JSON string as text/plain or application/json.
  // For this test, we'll assume the GET mock returns a string, as the component expects text.
  const initialConfigString = JSON.stringify(initialConfig); 

  // For pretty-printed JSON, if the actual API or component formats it:
  // const initialConfigStringPretty = JSON.stringify(initialConfig, null, 2);


  test('should load, display, edit, and save vendor configuration', async ({ page }) => {
    // Mock GET /api/admin/vendors before navigation
    await page.route('**/api/admin/vendors', async (route, request) => {
      if (request.method() === 'GET') {
        console.log('Mocking GET /api/admin/vendors');
        await route.fulfill({
          status: 200,
          contentType: 'application/json', // The actual resource returns application/json for GET
          body: initialConfigString, 
        });
      } else {
        // Other methods like PUT will be handled specifically or fall through if not mocked again
        await route.continue();
      }
    });

    await page.goto('/');

    // 1. Navigate
    // Based on App.vue, the tab text is "SMPP Vendors (JSON)"
      await page.getByText('Vendors (JSON)').click();
    // Verify navigation by checking the URL and a unique header
    await expect(page).toHaveURL('/admin/vendors-config');
    await expect(page.getByRole('heading', { name: 'Vendor Configuration' })).toBeVisible();

    // 2. Verify Initial Load
    // The textarea is identified by its role or a more specific selector if needed.
    // The component loads config into `configText` which is bound to the textarea.
    const textarea = page.locator('textarea');
    // If the actual API pretty-prints, then use initialConfigStringPretty here.
    // The current resource sends compact JSON string.
    await expect(textarea).toHaveValue(initialConfigString);
    await expect(page.getByText('Configuration loaded successfully.')).toBeVisible();


    // 3. Edit
    const modifiedConfig = [
      { ...initialConfig[0], port: 2776, name: "Updated Vendor 1" },
      { id: "vendor2", name: "New Vendor 2", host: "127.0.0.1", port: 2777, systemId: "newUser", password: "newPassword", enabled: false }
    ];
    // The component sends the raw text from the textarea for PUT.
    const modifiedConfigString = JSON.stringify(modifiedConfig); 
    // const modifiedConfigStringPretty = JSON.stringify(modifiedConfig, null, 2);

    await textarea.fill(modifiedConfigString); // Use non-pretty string if component doesn't auto-format

    // 4. Mock PUT and Save
    let putPayload = null;
    let putRequestMade = false;

    // Re-route for PUT specifically for this part of the test
    await page.unroute('**/api/admin/vendors'); // Remove previous general mock
    await page.route('**/api/admin/vendors', async (route, request) => {
      if (request.method() === 'PUT') {
        console.log('Mocking PUT /api/admin/vendors');
        putPayload = await request.postData(); // Get raw post data
        putRequestMade = true;
        await route.fulfill({
          status: 200,
          contentType: 'application/json', // The actual resource returns application/json for PUT response
          body: JSON.stringify({ message: 'Configuration saved successfully.' }),
        });
      } else if (request.method() === 'GET') {
        // If a reload happens after save, or for any other GETs
        console.log('Mocking GET (after PUT setup) /api/admin/vendors');
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: initialConfigString, // or modifiedConfigString if expecting immediate reload effect
        });
      } else {
        await route.continue();
      }
    });
    
    await page.getByRole('button', { name: 'Save Configuration' }).click();

    // 5. Verify Success
    await expect(page.getByText('Configuration saved successfully.')).toBeVisible();
    
    // Check that the PUT request was made
    expect(putRequestMade).toBe(true);
    // The payload sent by the component is the direct text area content
    expect(putPayload).toBe(modifiedConfigString);
  });
});
