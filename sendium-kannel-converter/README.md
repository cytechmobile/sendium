# Sendium Kannel Converter

Browser-only migration assistant for turning a narrow subset of `kannel.conf` into Sendium starter files.

## Local Development

```bash
npm install
npm run dev
```

## Verification

```bash
npm test
npx playwright install chromium
npm run test:e2e
npm run check
```

## GitHub Pages

The converter is published by `.github/workflows/kannel-converter-pages.yml` from the Vite production build in `dist/`.

Public URL: <https://cytechmobile.github.io/sendium/>

## V1.1 Scope

- Parse Kannel-style `group = ...` blocks and `key = value` lines.
- Generate starter `credentials.yml` from `group = sendsms-user` entries.
- Generate starter `smsg.properties` from SMPP `group = smsc` entries.
- Convert optional smppbox/opensmppbox-style ingress config into Sendium `smppserver` starters and SMPP credentials.
- Generate a minimal `routingTable.conf` fallback for the first converted SMPP client.
- Highlight unsupported or manual-review settings without uploading data anywhere.
- Attach inline next steps and Sendium/Kannel reference links to diagnostics.
- Cover the core converter behavior with fixture-based tests in `test/fixtures`.

The converter runs entirely in the browser.
