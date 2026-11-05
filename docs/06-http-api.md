# HTTP APIs: Sending SMS

**Use the native JSON API for new integrations.** The Kannel-compatible endpoint remains available
to migrate existing clients with minimal changes.

| API | Endpoint | Authentication | Purpose |
|---|---|---|---|
| Native JSON | `POST /api/v1/messages` | `Authorization: Bearer <apiKey>` | New integrations |
| Kannel compatibility | `GET /sendsms` | Username/password query parameters | Existing Kannel clients |

## Native JSON API

### Credentials

Use an existing file-backed HTTP API-key entry with a nonblank `accountId`, or add a separate entry
to the file selected by `smsg.credentials.file.path` (standalone default: `conf/credentials.yml`):

```yaml
credentials:
  - type: HTTP
    accountId: "customer-one"
    apiKey: "replace-with-a-long-random-api-key"
```

Send the opaque key in `Authorization: Bearer <apiKey>`; it is not a JWT. SMPP credentials and
HTTP username/password credentials without an API key cannot authenticate this endpoint. Ownership
comes only from the configured account, never a request field. Keep existing Kannel credentials as
separate entries rather than changing their lookup keys. Successfully reloaded key changes and entry
removals apply to subsequent requests; an invalid reload retains the last successfully loaded state.

`allowedIps` optionally restricts a credential to exact client IP addresses (not CIDR ranges).
Omitted or empty means unrestricted. See the trusted-proxy configuration below for reverse proxies.
`product` is retained as authenticated metadata, but has no new routing meaning. Existing server-side
routing rules choose the provider; callers cannot select an account, SMSC or route.

### Request and response

Submit one recipient per request, with `Content-Type: application/json` and a UTF-8 JSON body.
Do not URL-encode message text: `+` and `%` are literal characters. No query parameters are accepted.

```bash
# Set SENDIUM_API_KEY to the apiKey from your existing HTTP credential.
curl -i https://sms.example.com/api/v1/messages \
  -H "Authorization: Bearer ${SENDIUM_API_KEY}" \
  -H 'Content-Type: application/json' \
  --data-binary '{
    "from": "Sendium",
    "to": "+306900000000",
    "text": "Your verification code is 123456",
    "encoding": "gsm",
    "priority": 2,
    "callbackUrl": "https://customer.example/receipts"
  }'
```

| Field | Required | Contract |
|---|---|---|
| `from` | Yes | Nonblank sender, 1–21 printable ASCII characters. No sender allowlist; provider address restrictions still apply. |
| `to` | Yes | String containing 1–20 digits, optionally preceded by `+`. |
| `text` | Yes | Nonempty text, at most 4096 UTF-16 code units, representable in the selected encoding. |
| `encoding` | No | `gsm` (default) or `ucs2`. GSM-incompatible text is rejected rather than transliterated or switched automatically. UCS2 supports the basic multilingual plane, not emoji/surrogate characters. |
| `priority` | No | Integer `1` (low), `2` (normal/default), or `3` (high). Uses existing processing behavior, not a delivery-time guarantee. |
| `callbackUrl` | No | Absolute HTTP(S) URL, at most 2048 characters, with no user info or fragment. Selects JSON POST receipts; no host preapproval or allowlist. Omit it when no callback is needed. |

The entire request body is limited to **32768 bytes**. Unknown or duplicate fields, trailing JSON,
wrong field types and explicit null fields are rejected. `account`, `smsc`, `externalTags`, raw UDH,
binary payloads and bulk submission are not part of this contract. The API's text limit is an admission
bound; existing provider/worker multipart and length policies still apply after routing.

Successful admission returns **HTTP 202**:

```json
{
  "messageId": "123e4567-e89b-12d3-a456-426614174000",
  "status": "accepted"
}
```

Acceptance means the configured outbound coordinator owns the message. It does not establish a
viable route, provider acceptance, handset delivery or restart durability. The currently implemented
outbound profile is memory-backed. Native admission never falls back to a legacy queue if the
coordinator is unavailable. Each successful request creates a new message; an uncertain client-side
timeout followed by a retry can therefore duplicate a submission.

### Errors

Native admission/authentication and HTTP method/media errors use JSON without echoing credentials or
message content:

```json
{"code":"INVALID_REQUEST","message":"encoding must be gsm or ucs2"}
```

| HTTP status | Error code | Meaning |
|---|---|---|
| `400` | `INVALID_REQUEST` | Invalid JSON, fields, encoding, callback URL or query parameters. |
| `401` | `UNAUTHORIZED` | Missing, invalid or wrong-type API key; includes `WWW-Authenticate: Bearer`. |
| `403` | `IP_NOT_ALLOWED` / `ACCOUNT_NOT_CONFIGURED` | Client IP denied or credential lacks a nonblank accountId. |
| `405` | `METHOD_NOT_ALLOWED` | Use POST; response includes `Allow: POST`. |
| `406` | `NOT_ACCEPTABLE` | The response format is JSON; do not require another format in `Accept`. |
| `413` | `PAYLOAD_TOO_LARGE` | Body exceeds 32768 bytes. |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Use `Content-Type: application/json`. |
| `500` | `INTERNAL_ERROR` | Unexpected admission failure. |
| `503` | `ADMISSION_UNAVAILABLE` | Coordinator absent, unavailable or full; retry later. |

### Delivery callbacks

An optional `callbackUrl` receives JSON POST with `messageId`, `status` (`delivered` or `failed`),
and nullable `errorCode`. Provider submission rejection also produces `failed`. No Kannel URL
placeholders or receipt mask are needed. Callbacks require `sendium.dlr.persistence.enabled=true`
and its configured PostgreSQL datasource. Delivery is at-least-once: handle duplicates by `messageId`.
See [native callbacks and retry behavior](07-webhooks.md#native-json-delivery-callbacks).

### HTTPS, proxies, logs and rate limits

Expose this API through HTTPS, using Quarkus TLS or a trusted reverse proxy/load balancer that
terminates TLS. Keep any cleartext proxy-to-Sendium hop on a controlled private network; use TLS
on that hop when the network is not trusted. Forwarding is disabled by default, so IP restrictions
use the connection address unless proxy forwarding is explicitly configured.

For a proxy at `192.0.2.10` that overwrites incoming forwarding headers, use these Quarkus settings
(replace the address with your actual trusted proxy):

```properties
quarkus.http.proxy.proxy-address-forwarding=true
quarkus.http.proxy.allow-forwarded=false
quarkus.http.proxy.allow-x-forwarded=true
quarkus.http.proxy.trusted-proxies=192.0.2.10
```

The proxy must overwrite client-supplied `X-Forwarded-For` and `X-Forwarded-Proto` with values it
establishes itself. Trust only known proxy peers. Headers from an untrusted peer do not override the
client address; application authentication never parses forwarding headers itself. `allowedIps`
then applies to the resolved client IP, rather than granting callers control through arbitrary headers.

Keep Bearer headers and request bodies out of proxy/APM/access-log capture. Use path-only access
logging without query strings, Authorization, Referer or request/response bodies, for example:

```properties
quarkus.http.access-log.pattern=%a %t "%m %U %H" %s %b
```

The native handler does not log keys or message content. Keep existing unsafe message-body/PDU debug
logging disabled when processing sensitive traffic. Avoid real credentials/content in public examples.
Per-client/account rate limits are enforced by the deployment API gateway in V1; Sendium does not
add a native per-client rate limiter. Existing worker TPS limits and bounded admission are separate
controls. Configure gateway body limits consistently with the API; gateway-generated errors have
the gateway's own response format.

### OpenAPI

The generated contract at `/q/openapi` (or `/q/openapi?format=json`) describes request fields,
Bearer authentication, responses and the optional JSON POST callback. Swagger UI, when enabled,
can use an existing opaque key through its **Authorize** control.

## Kannel-Compatible API

The compatibility endpoint keeps the classic Kannel parameter structure for existing clients.

## 📍 Endpoint Overview

* **HTTP Method:** `GET`
* **Path:** `/sendsms`
* **Content-Type (Response):** `text/plain`

## 🔐 Authentication

Authentication is handled via query parameters. The credentials provided must match an active `HTTP` credential type defined in your `credentials.yml` configuration.

You can use either `username` and `password`, or the shorthand `user` and `pass`.

---

## 📥 Request Parameters

All parameters must be passed in the query string.

### Required Parameters

| Parameter | Description | Example |
| :--- | :--- | :--- |
| `username` (or `user`) | The username/system ID for authentication. | `my_api_user` |
| `password` (or `pass`) | The password for authentication. | `example-password` |
| `from` | The Sender ID (can be a phone number or alphanumeric string). | `MyBrand` |
| `to` | The recipient's phone number. | `306910000000` |
| `text` | The message payload. **Must be URL-encoded.** | `Hello%20World` |

### Optional Parameters

| Parameter | Description |
| :--- | :--- |
| `account` | Accounting identifier. If omitted, it defaults to the `username`. |
| `smsc` | Target SMSC routing ID to force a specific outbound route. |
| `coding` | Data coding scheme: `0` (7-bit), `1` (8-bit), or `2` (UCS-2/Unicode). |
| `charset` | Character set of the `text` parameter (e.g., `UTF-8`, `ISO-8859-1`). If omitted, it defaults to `UTF-8` (or `UTF-16BE` if coding is `2`). |
| `udh` | User Data Header in hex format (used for concatenated messages or special encoding). Automatically sets coding to 8-bit if provided without a `coding` parameter. |
| `dlr-url` | Delivery report webhook URL. Supports `%d` (Kannel-style DLR status), `%I` (gateway message ID), and `%F` (provider/SMSC message ID). Must be URL-encoded. See [Webhooks](07-webhooks.md) for template rules and compatibility limits. |
| `dlr-mask` | Requested terminal receipt events: `1` delivered, `2` failed, `3` both. `0` or omitted disables receipts even with `dlr-url`. Other values are rejected with HTTP 400. A nonzero mask requires a nonblank `dlr-url`. |
| `mclass` | Message class: `0` (Flash), `1` (ME specific), `2` (SIM specific), `3` (TE specific). |
| `priority` | Message priority level (e.g., `0`, `1`, `2`, `3`). Defaults to normal priority. |
| `validity` | Validity period in minutes. |
| `deferred` | Deferred delivery time in minutes. |
| `pid` | Protocol Identifier. |
| `alt-dcs` | Alternative Data Coding Scheme. |
| `rpi` | Return Path Indicator. |
| `binfo` | Billing information string. |

---

## 📤 Responses

The API returns standard HTTP status codes along with a plain-text response body.

| HTTP Status | Meaning | Description |
| :--- | :--- | :--- |
| **`202 Accepted`** | **Success** | Admission succeeded through the configured path. The response body contains the unique gateway UUID (serial) of the message. |
| **`400 Bad Request`** | **Error** | Missing a required parameter (`to`, `from`, or `text`), or the selected lifecycle cannot support the submission. |
| **`401 Unauthorized`** | **Error** | Invalid or missing credentials. |
| **`500 Server Error`** | **Error** | An internal error occurred while parsing or processing the message payload. |
| **`503 Unavailable`** | **Error** | Admission is unavailable, at capacity, has an ownership conflict, or was interrupted. The client should retry later. |

---

## 📖 Example Request

If you used the generated Quick Start and configured an upstream provider, load its HTTP credentials before submitting a message:

```bash
cd sendium
set -a
. ./.sendium.env
set +a

curl -i -G http://127.0.0.1:8080/sendsms \
  --data-urlencode "username=${SENDIUM_HTTP_USER}" \
  --data-urlencode "password=${SENDIUM_HTTP_PASSWORD}" \
  --data-urlencode "from=Sendium" \
  --data-urlencode "to=306910000000" \
  --data-urlencode "text=Hello from Sendium!"
```

For a manual installation, replace the environment variables with the HTTP `systemId` and `password` from `credentials.yml`. Keep the endpoint local for evaluation. Before external use, terminate HTTPS at a trusted proxy and ensure proxy/access logs omit query strings because authentication credentials, addresses, and message content are URL parameters.

**Example Successful Response (`202 Accepted`):**
```text
123e4567-e89b-12d3-a456-426614174000
```

In the standalone runtime, `202 Accepted` means admission into the memory-backed pending store. The source remains retained through routing, provider processing, and required DLR handoffs. Capacity rejection returns `503`; a failing coordinator is never replaced by legacy queue admission. Embedded callers without a coordinator retain the legacy in-memory queue path. The memory profile is non-durable across restart. See [Outbound Message Storage](14-outbound-storage.md).

Neither memory admission path writes an outbound message or DLR row to PostgreSQL. Acceptance does not prove that a viable route exists, that an upstream SMSC accepted the message, or that a handset received it. Durable DLR state is created only after an upstream provider outcome. Local-only Quick Start installations have no outbound route. Check routing configuration, the SMPP client connection, message lifecycle logs, submit response, and delivery receipt for those later stages.

## Related Documentation

* [Authentication and Security](03-auth-security.md)
* [Webhooks](07-webhooks.md)
* [Documentation Map](DocumentationMap.md)
