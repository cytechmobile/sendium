# Support

Use the right channel so the community and maintainers can help quickly.

## Questions And Setup Help

Use [GitHub Discussions](https://github.com/cytechmobile/sendium/discussions) for:

- Quick start help.
- Routing configuration questions.
- SMPP bind and provider setup questions.
- Usage patterns and integration ideas.
- General community discussion.

## Bugs

Use [GitHub Issues](https://github.com/cytechmobile/sendium/issues) for reproducible bugs.

Include:

- Sendium version or Docker tag.
- Steps to reproduce.
- Expected behavior.
- Actual behavior.
- Sanitized `credentials.yml`, `smsg.properties`, or `routingTable.conf` snippets when relevant.
- Relevant log lines from `smsg.log`, `smppclient.log`, `smppserver.log`, or `httpapi.log`.

Default `httpapi.log` entries omit request query strings. If your deployment uses a custom HTTP access-log pattern, sanitize any `/sendsms` query parameters before sharing logs.

## Feature Requests

Use GitHub Discussions for early ideas. Open an issue when the behavior and use case are clear enough to track.

## Security Issues

Do not post security vulnerabilities publicly. Email `info@sendium.org` and read [Security Policy](SECURITY.md).

## Before Posting Publicly

Remove:

- Passwords, API keys, and credentials.
- Real system IDs and account IDs.
- Real phone numbers and message bodies.
- Sensitive IP addresses and provider details.
