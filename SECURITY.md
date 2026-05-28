# Security Policy

## Reporting A Vulnerability

Please do not report security vulnerabilities through public GitHub issues, pull requests, or discussions.

Send vulnerability reports privately to `info@sendium.org`.

Include as much detail as possible:

- Affected Sendium version or Docker tag.
- Deployment type and relevant configuration, with secrets removed.
- Steps to reproduce.
- Impact and expected behavior.
- Logs or proof-of-concept details, if safe to share privately.

## Public Disclosure

Do not publicly disclose a vulnerability until the Sendium maintainers have investigated it and coordinated a fix or mitigation.

## Sensitive Information

When reporting any issue, remove:

- Passwords, API keys, and SMPP credentials.
- Real system IDs and account IDs.
- Private IP addresses when they are sensitive.
- Phone numbers and message bodies.
- Raw SMPP PDU, byte, worker message, response, and MO diagnostic logs.
- Provider names if your agreement requires confidentiality.

## Supported Versions

Sendium is currently pre-1.0. Security fixes are expected to target the latest public release or Docker image tag.
