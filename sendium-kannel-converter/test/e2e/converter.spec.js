import { expect, test } from '@playwright/test';

const customKannelConfig = `group = sendsms-user
username = alice
password = secret
user-allow-ip = 192.0.2.10

group = smsc
smsc = smpp
smsc-id = carrier-one
host = smpp.example.test
port = 2775
smsc-username = carrier-user
smsc-password = carrier-pass
transceiver-mode = true
throughput = 25
`;

const diagnosticSmppboxConfig = `group = smppbox
bearerbox-host = localhost
system-id = downstream-user
password = downstream-password
smppbox-port = 2775
`;

test.beforeEach(async ({ page }) => {
  await page.goto('/');
});

test('renders the default Kannel sample conversion', async ({ page }) => {
  await expect(page.getByRole('heading', { name: 'Kannel config converter' })).toBeVisible();
  await expect(page.getByLabel('kannel.conf editor')).toHaveValue(/group = smsc/);

  const preview = page.getByLabel('Generated Sendium file preview');
  await expect(preview).toContainText('outSms.instance.upstreamA.type = smppclient');
  await expect(preview).toContainText('outSms.instance.upstreamA.host = smpp.provider.example');

  await page.getByRole('tab', { name: 'Generated file credentials.yml' }).click();
  await expect(preview).toContainText('systemId: "legacy-http-user"');

  await page.getByRole('tab', { name: 'Generated file routingTable.conf' }).click();
  await expect(preview).toContainText('upstreamA::default:');
});

test('updates generated files when the Kannel source changes', async ({ page }) => {
  await page.getByLabel('kannel.conf editor').fill(customKannelConfig);

  const preview = page.getByLabel('Generated Sendium file preview');
  await expect(preview).toContainText('outSms.instance.carrierOne.host = smpp.example.test');
  await expect(preview).toContainText('outSms.instance.carrierOne.tps = 25');

  await page.getByRole('tab', { name: 'Generated file credentials.yml' }).click();
  await expect(preview).toContainText('systemId: "alice"');
  await expect(preview).toContainText('192.0.2.10');
});

test('loads smppbox sample and generates SMPP server settings', async ({ page }) => {
  await page.getByRole('tab', { name: 'smppbox.conf' }).click();
  await page.getByRole('button', { name: 'Load sample' }).click();

  await expect(page.getByLabel('smppbox.conf editor')).toHaveValue(/system-id = downstream-user/);

  const preview = page.getByLabel('Generated Sendium file preview');
  await expect(preview).toContainText('outSms.instance.smppIngress.type = smppserver');
  await expect(preview).toContainText('outSms.instance.smppIngress.srv.port = 2775');

  await page.getByRole('tab', { name: 'Generated file credentials.yml' }).click();
  await expect(preview).toContainText('systemId: "downstream-user"');
});

test('diagnostics navigate to the legacy source that needs review', async ({ page }) => {
  await page.getByRole('tab', { name: 'smppbox.conf' }).click();
  await page.getByLabel('smppbox.conf editor').fill(diagnosticSmppboxConfig);
  await page.getByRole('tab', { name: 'kannel.conf' }).click();

  await page.locator('.diagnostic-item', { hasText: 'bearerbox-host' }).click();

  await expect(page.getByRole('tab', { name: 'smppbox.conf' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByLabel('smppbox.conf editor')).toBeFocused();
});

test('downloads the active generated file and bundle', async ({ page }) => {
  const fileDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download' }).click();
  expect((await fileDownload).suggestedFilename()).toBe('smsg.properties');

  const bundleDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Bundle' }).click();
  expect((await bundleDownload).suggestedFilename()).toBe('conf.zip');
});

test('copies the active generated file to the clipboard', async ({ browserName, context, page }) => {
  test.skip(browserName !== 'chromium', 'Clipboard permissions are only configured for Chromium here.');

  await context.grantPermissions(['clipboard-read', 'clipboard-write']);

  await page.getByRole('button', { name: 'Copy' }).click();

  await expect(page.getByText('Copied smsg.properties')).toBeVisible();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toContain(
    'outSms.instance.upstreamA.type = smppclient',
  );
});
