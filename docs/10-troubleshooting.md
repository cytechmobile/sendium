# Troubleshooting

This guide lists common problems and the first checks to run.

## Container Does Not Start

Check:

- `docker logs sendium`
- `logs/smsg.log` if file logging is enabled
- The `conf` directory is mounted to `/work/conf`
- `credentials.yml`, `smsg.properties`, and `routingTable.conf` exist
- The configured SMPP and HTTP ports are not already in use

## HTTP API Returns 401

Check:

- The credential entry has `type: HTTP`.
- The submitted `username` or `user` matches the configured `systemId`.
- The submitted `password` or `pass` matches the configured `password`.
- If `allowedIps` is configured, the request source IP is allowed.
- The credential file was saved correctly and hot reloaded.

## HTTP API Returns 400

The `/sendsms` endpoint requires:

- `username` or `user`
- `password` or `pass`
- `from`
- `to`
- `text`

Check the response body for the missing parameter.

## Message Is Accepted But Not Delivered

Check:

- `routingTable.conf` has a matching rule for the message type.
- The target worker name in `routingTable.conf` matches a configured worker in `smsg.properties`.
- The SMPP client worker is enabled with `outSms.instance.<name>.enable = true`.
- The upstream SMPP provider accepted the bind.
- `smppclient.log` contains submit responses or connection errors.

## Routing Falls Through Unexpectedly

Check:

- Rules are evaluated top-to-bottom.
- The fallback rule uses the `default` operator.
- Message attributes match the names documented in [Routing Engine](05-routing-engine.md).
- String comparisons are case-sensitive unless `equalsIgnoreCase` is used.

## SMPP Client Cannot Bind

Check:

- `host`, `port`, `username`, and `password` are correct.
- `connections.transceivers`, `connections.transmitters`, or `connections.receivers` is greater than zero.
- TLS settings match the provider requirement.
- Firewall rules allow outbound traffic to the provider.
- The provider has allowlisted your source IP if required.

## SMPP Server Rejects Binds

Check:

- The credential entry has `type: SMPP`.
- The SMPP client is using the configured `systemId` and `password`.
- `srv.maxConnections`, `srv.maxConnectionsPerIP`, `conf.maxConnectionsPerUser.default`, or per-user limits are not exceeded.
- If `allowedIps` is configured, the bind source IP is allowed.

## DLR Callback Is Not Received

Check:

- The HTTP submission includes a URL-encoded `dlr-url` parameter.
- The callback endpoint is reachable from the Sendium container or host.
- The callback URL uses supported placeholders from [Webhooks](07-webhooks.md).
- The receiving endpoint returns an HTTP status from `200` to `399`.
- Logs contain DLR forwarding failures or retry messages.

## MO Callback Is Not Received

Check:

- `forward.mo.url` is configured on the SMPP client worker receiving MO messages.
- `forward.mo.format` is set to `JSON` or `FORM`.
- The callback endpoint is reachable from the Sendium container or host.
- The receiving endpoint returns an HTTP status from `200` to `399`.
- `smppclient.log` contains incoming MO activity.

## Logs Are Missing

Check:

- `QUARKUS_LOG_FILE_ENABLE=true` is set when using file logs.
- `/work/logs` is mounted to a writable host directory.
- `QUARKUS_LOG_FILE_PATH`, `QUARKUS_LOG_FILE_SMPPCLIENT_PATH`, and `QUARKUS_LOG_FILE_SMPPSERVER_PATH` point to valid paths.
- Console logging is enabled if you expect logs in `docker logs`.

## Asking For Help

Before opening a public issue or discussion, remove credentials, system IDs, IP addresses, phone numbers, and message content from configs and logs.

Use:

- [GitHub Discussions](https://github.com/cytechmobile/sendium/discussions) for questions and setup help.
- [GitHub Issues](https://github.com/cytechmobile/sendium/issues) for reproducible bugs.
- `info@sendium.org` for security-sensitive reports.
