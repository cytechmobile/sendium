# Credentials Configuration

The `credentials.yml` file is used to define and manage authentication profiles for clients connecting to the application.

The application utilizes a background virtual thread to continuously monitor this file for modifications. When you update the file, the system detects the change (with a 200ms debounce delay) and automatically "hot reloads" the credentials into memory without requiring a service restart.


## 📝 Available Fields

Each credential entry in the YAML list supports the following properties:

| Field | Type | Description |
| :--- | :--- | :--- |
| `type` | String | The protocol type for the credential. Must be either `SMPP` or `HTTP`. |
| `systemId` | String | The username or system identifier used for authentication. |
| `password` | String | The password associated with the `systemId`. |
| `apiKey` | String | Opaque HTTP Bearer key for native `POST /api/v1/messages`; must be nonblank. Kannel `/sendsms` uses username/password query parameters. |
| `allowedIps` | List | A list of explicit IP addresses allowed to connect using this credential. |
| `accountId` | String | Account identity; required and nonblank for the native API. Native callers cannot override it. |
| `product` | String | An optional tag to associate the credential with a specific product. |

## ✅ Validation Rules

To be considered valid and loaded into memory, credentials must meet specific criteria based on their `type`:

* **SMPP Credentials:** Must provide both a `systemId` and a `password`. The system will use the `systemId` as the primary lookup key when authenticating clients.
* **HTTP Credentials:** Must provide either an `apiKey`, OR both a `systemId` and `password`. The credentials loader will prioritize the `apiKey` as the lookup key if it exists; otherwise, it will fall back to using the `systemId`. For the Kannel-compatible `/sendsms` endpoint, configure `systemId` and `password` and submit them as `username`/`password` or `user`/`pass`.

For new integrations, use the [native JSON API](06-http-api.md#native-json-api) with an HTTP `apiKey`
and `accountId`. Keep Kannel username/password entries separate. Successfully reloaded key updates
and entry removals affect subsequent native requests; a failed reload retains the last loaded state.

## 🔒 IP Whitelisting (`allowedIps`)

You can enhance security by restricting which IP addresses are permitted to use a specific credential.
* If you define IPs under `allowedIps`, the application will strictly reject connections originating from unlisted IPs.
* **Default Behavior:** If the `allowedIps` list is omitted or left empty, the application defaults to an "allow all" policy, permitting connections from any IP address.

For the native API these are exact address matches, not CIDR ranges. Forwarded client addresses are
used only with explicitly configured trusted proxies; see [HTTPS/proxy guidance](06-http-api.md#https-proxies-logs-and-rate-limits).

---

## 📖 Example Configuration

```yaml
credentials:
  # Standard SMPP credential requiring System ID and Password
  - type: SMPP
    systemId: "306910000000"
    password: "123qwe"

  # Kannel HTTP credential using username/password query parameters
  - type: HTTP
    systemId: "306910000001"
    password: "123qwe"

  # HTTP credential using an API Key, restricted to a specific IP
  - type: HTTP
    accountId: "customer-one"
    apiKey: "ak_live_abcdef1234567890"
    allowedIps:
      - "203.0.113.50"
```

## Related Documentation

* [HTTP API](06-http-api.md)
* [Docker Deployment](02-docker-deployment.md)
* [Documentation Map](DocumentationMap.md)
