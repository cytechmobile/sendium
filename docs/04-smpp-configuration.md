
# SMPP Server Configuration

The SMPP Server worker (`WorkerType: smppserver`) supports a wide variety of configuration parameters to tune networking, TLS, message routing, and performance constraints.

All properties below should be prefixed with your instance path. For example, if your instance is named `smpp`, the property `srv.port` becomes `outSms.instance.smpp.srv.port`.

## 🛠 Core Server Settings

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `srv.enabled` | `true` | Enables or disables the primary (plaintext) SMPP server. |
| `srv.port` | `0` | The port the server binds to. |
| `srv.host` | `localhost` | The IP address or hostname the server binds to. |
| `srv.systemId` | `Sendium` | The System ID presented by the server during bind responses. |
| `srv.maxConnections` | `1000` | The absolute maximum number of concurrent SMPP connections allowed. |
| `srv.maxConnectionsPerIP`| `0` | Maximum allowed connections originating from a single IP address (`0` = unlimited). |
| `srv.bindTimeout` | `5000` | Timeout in milliseconds waiting for a bind request. |
| `srv.maxInactivityTime` | `60` | Maximum session inactivity time (in minutes). Inactive sessions beyond this are terminated. |

## 🔒 TLS Settings

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `srv.tls.enabled` | `false` | Enables or disables the TLS-secured SMPP server. |
| `srv.tls.port` | `0` | The port for the TLS server. |
| `srv.tls.host` | `localhost` | The host for the TLS server. |
| `srv.tls.keystore.path` | `""` | Absolute or relative path to the Java Keystore (JKS) file. |
| `srv.tls.keystore.alias` | `""` | The alias of the certificate inside the keystore. |
| `srv.tls.keystore.password`| `""` | The password to access the keystore. |
| `srv.tls.reload` | `""` | Changing this value at runtime triggers a reload of the TLS server. |

## 🌐 Proxy / HAProxy Settings

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `srv.proxy.enabled` | `false` | Enables a dedicated SMPP server port that expects HAProxy protocol headers. |
| `srv.proxy.port` | `2777` | The port for the proxy-enabled server. |

## ⚖️ Thresholds & Limits (Defaults)

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `conf.maxConnectionsPerUser.default` | `0` | Default concurrent binds allowed per account/user (`0` = unlimited). |
| `conf.maxRate.default` | `0` | Default Throughput/TPS (Transactions Per Second) allowed per account (`0` = unlimited). |
| `conf.maxPending.default` | `1000` | Default window size (maximum unacknowledged requests allowed in-flight). |
| `conf.windowMonitorInterval.default` | `15000` | Interval (in ms) for monitoring the request window. |
| `conf.responseTout.default` | `30000` | Default request expiry / response timeout (in ms). |
| `conf.writeTimeout.default` | `30000` | Default socket write timeout (in ms). |

## ✉️ Message & Encoding Rules

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `ptrn.valid.receiver` | `[+]?[0-9]{10,20}` | Regex pattern applied to validate the receiver's address. |
| `forward.dlrs` | `true` | Enables forwarding of Delivery Receipts (DLRs) to the clients. |
| `flag.reverseDlrSrcDst` | `true` | Reverses the source and destination addresses inside DLRs. |
| `charset.gsm` | `GSM` | The default charset mapping for GSM (data coding `0`). |
| `charset.latin1` | `ISO-8859-1` | The default charset mapping for Latin-1. |
| `charset.ucs2` | `UCS-2` | The default charset mapping for UCS-2 (data coding `8`). |
| `ccat.8bit` | `true` | Use 8-bit reference numbers for Concatenated (multipart) SMS instead of 16-bit. |
| `reassembling.timeoutMillis` | `30000` | Timeout (in ms) to wait for all parts of a concatenated message to arrive before failing. |
| `filters.beforeInsertMessage` | `""` |  (Filters Not supported yet) Comma-separated list of filter class names to process messages before queuing. |

## 🧵 Thread Pool Configuration

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `srv.threads` | `1` | Netty boss thread count (accepts incoming connections). |
| `srv.worker.threads` | `10` | Netty worker thread count (handles I/O operations). |
| `srv.out.threads` | `10` | Thread pool size for handling outgoing logic/routing. |
| `srv.monitor.threads` | `1` | Thread pool size for session monitoring tasks. |

## 📊 Logging, Monitoring & JMX

SMPP PDU and byte diagnostics are disabled by default because bind PDUs can include passwords and submit/deliver PDUs can include phone numbers and message bodies. Enable these settings only temporarily in controlled troubleshooting sessions, and treat the resulting logs as sensitive data.

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `log.pdus` | `false` | Opt-in logging for decoded SMPP Protocol Data Units (PDUs). May include bind passwords, phone numbers, and message bodies. |
| `log.bytes` | `false` | Opt-in raw hex byte logging for SMPP troubleshooting. Treat as sensitive. |
| `log.pdus.exclude` | `21,2147483669` | Comma-separated list of PDU Command IDs to suppress from logs (defaults to EnquireLink & EnquireLinkResp). |
| `srv.printStatsPeriod` | `300` | Interval (in seconds) to dump server statistics to the logs. |
| `srv.printRatePeriod` | `60` | Interval (in seconds) to calculate and print message rates. |
| `srv.jmx.enabled` | `false` | Expose server metrics via JMX. |
| `srv.jmx.domain` | `gr.cytech.sendium` | The JMX domain name to register beans under. |

---

### Example Configuration

```properties
# Enable the SMPP Server and set worker type
outSms.instance.server.enable = true
outSms.instance.server.type = smppserver

# Core bindings
outSms.instance.server.srv.port = 27777
outSms.instance.server.srv.maxConnections = 1000
outSms.instance.server.srv.maxConnectionsPerIP = 4

# Limits and Window sizes
outSms.instance.server.conf.maxPending.default = 1000
outSms.instance.server.conf.maxConnectionsPerUser.default = 4
outSms.instance.server.conf.maxRate.default = 0
```

## ⚙️ Base Worker Configuration (Inherited Properties)

SMPP workers are built on top of the core worker engine and inherit several foundational properties. These manage threading, queue behavior, fallback logic, and message filtering.

All properties are prefixed with your instance path (e.g., `outSms.instance.testRoute.`).

### Flow Control & Throughput

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `threadCount` | `1` | The number of concurrent threads allocated to process messages from the queue. |
| `tps` | `0` | Rate limiting. The maximum Transactions Per Second allowed (`0` means unlimited). |
| `pause` | `false` | If set to `true`, the worker pauses message processing but remains active. |
| `suspend` | `false` | Manually suspends the worker. |
| `pause.sleep.ms` | `1000` | The duration (in milliseconds) the worker sleeps while in a paused state before checking its status again. |

### Queue Management & Alerts

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `queue.name` | `""` | Overrides the default queue name (which defaults to the instance name). |
| `queue.honourPriorities`| `false` | If `true`, the internal queue will respect message priority flags rather than strict FIFO. |
| `alert.maxPending` | `0` | (Not supported yet) Alert threshold for the maximum number of pending messages (`0` = unlimited). |
| `alert.maxRejected` | `0` | (Not supported yet) Alert threshold for the maximum number of rejected messages (`0` = unlimited). |
| `alert.maxQueueSize` | `0` | (Not supported yet) Alert threshold for the overall queue size (`0` = unlimited). |

### Retry & Failure Actions

When a message fails to send, the worker uses these policies to decide how long to wait and where to send the message next.

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `maxRetries` | `1` | The maximum number of times the worker will attempt to retry sending a message internally (`0` = unlimited). |
| `fail.action.worker.type`| *Dynamic* | Action to take when a worker retry occurs (e.g., `SLEEP`, `RE_ENQUEUE_WORKER`, `RE_ENQUEUE_WORKER_DELAYED`). Defaults to `SLEEP` for synchronous and `RE_ENQUEUE_WORKER` for asynchronous handlers. |
| `fail.action.router.type`| `RE_ENQUEUE_ROUTER` | Action to take when the message fails permanently in the worker and must go back to the router. |
| `fail.action.worker.sleep`| *Dynamic* | Milliseconds to sleep before executing the worker retry action (Defaults to `1000` for sync, `0` for async). |
| `fail.action.router.sleep`| *Dynamic* | Milliseconds to sleep before pushing the message back to the router (Defaults to `2000` for sync, `0` for async). |
| `fail.action.delayed.delay`| `5000` | The delay (in milliseconds) before a delayed message can be re-attempted if the action is `RE_ENQUEUE_WORKER_DELAYED`. |

### Auto-Suspension Policies

If the worker encounters severe connectivity issues, it can auto-suspend to prevent message loss.

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `suspension.policy` | `SUSPEND` | The policy to apply when the worker auto-suspends (`SUSPEND`, `RETRY_ROUTER`, `FAIL`). |
| `suspension.stopMessages.ms`| `-1` | Time (in ms) after suspension before the worker stops accepting new messages from the router and flushes its queue back to the router (`-1` = disabled). |
| `suspension.disable.ms` | `-1` | Time (in ms) after suspension before the worker entirely disables itself (`-1` = disabled). |

### Modifiers, Filters & Char Mappings

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `charmapper.enabled` | `true` | Enables character mapping for outgoing messages. |
| `charmapper` | `""` | The specific character mapper profile to use. |
| `filters.beforeDoMessage` | `""` | (Filters Not supported yet)  Comma-separated list of filters to execute *before* the message is processed. |
| `filters.afterDoMessageSuccess` | `""` | (Filters Not supported yet) Comma-separated list of filters to execute *after* a message is successfully sent. 
| `filters.afterDoMessageFailure` | `""` | (Filters Not supported yet) Comma-separated list of filters to execute *after* a message fails. |

### Logging & KPIs

Message printing is disabled by default because worker message objects can include phone numbers, callback URLs, and message bodies. Enable `print.msgs` only for short-lived diagnostics and sanitize logs before sharing them.

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `debug` | `false` | Enables deep debug logging for the worker. |
| `print.msgs` | `false` | Opt-in printing of full worker message objects. Treat output as sensitive. |
| `kpi.enabled` | `false` |  (KPIs Not supported) Enables tracking of Key Performance Indicators (KPIs) for the vendor route. |
| `kpi.period.minutes` | `60` | (KPIs Not supported) The rolling time window (in minutes, max 120) for KPI calculations. |
| `kpi.volume` | `100` | (KPIs Not supported) The volume threshold required before KPI alerts trigger. |
| `kpi.fail.statuses` | `""` | (KPIs Not supported) Comma-separated statuses that explicitly count as KPI failures. |

---

# SMPP Client Configuration

The SMPP Client worker (`WorkerType: smppclient`) allows the application to connect to upstream SMSCs or providers. It offers extensive configuration for bindings, failover hosts, timeouts, character set mappings, and error handling policies.

## 🔌 Connection & Binding Settings

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `host` | `localhost` | The primary SMSC host/IP to connect to. |
| `port` | `27777` | The port of the primary SMSC. |
| `username` | `smsp` | The system ID (username) used to bind. |
| `password` | `psms` | The password used to bind. |
| `extra.hosts` | `""` | Comma-separated list of extra host/port pairs (e.g., `host1:port,host2:port`) to bind to concurrently. |
| `backup.hosts` | `""` | Comma-separated list of backup host/port pairs  (e.g., `host1:port,host2:port`) to use if the primary fails. |
| `local.bind.host` | `""` | The local IP address to bind from (useful for multi-homed servers). |
| `connections.transceivers` | `1` | Number of transceiver bounds to establish. |
| `connections.transmitters` | `0` | Number of transmitter binds to establish. |
| `connections.receivers` | `0` | Number of receiver binds to establish. |
| `systemType` | `""` | The system_type parameter sent in the bind request. |
| `interfaceVersion` | `52` (v3.4) | The SMPP interface version (52 = 3.4, 51 = 3.3). |

## 🔒 TLS / SSL Settings

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `ssl` | `false` | Enables SSL/TLS for the SMPP connection. |
| `ssl.trustAll` | `false` | If true, bypasses certificate validation (trusts all certificates). |

## ⏱ Timeouts & Keep-Alives

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `con.tout` | `10000` | Socket connection timeout in milliseconds. |
| `request.tout` | `30000` | Timeout in milliseconds waiting for a response to a request. |
| `reconnect.interval.millis` | `60000` | Interval in milliseconds between reconnection attempts. |
| `reconnection.stability.threshold.millis` | `5000` | Minimum lifespan (ms) a connection must survive to be considered stable before an immediate retry is allowed. |
| `unbind.timeout.millis` | `5000` | Timeout in milliseconds waiting for an unbind response. |
| `enquire.link.interval.millis`| `30000` | Interval in milliseconds to send EnquireLink (keep-alive) PDUs. |
| `enquire.link.noTrafficOnly` | `false` | If true, EnquireLinks are only sent if there has been no other PDU traffic. |

## ✉️ Addressing (TON/NPI)

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `src.addr.autodetect` | `true` | Auto-detects Alphanumeric source addresses (overrides TON/NPI if non-digits are found). |
| `src.addr.ton` | `2` | Default Type of Number for the source address. |
| `src.addr.npi` | `1` | Default Numbering Plan Indicator for the source address. |
| `dest.addr.ton` | `2` | Default Type of Number for the destination address. |
| `dest.addr.npi` | `1` | Default Numbering Plan Indicator for the destination address. |
| `addressRange`, `addressRangeTon`, `addressRangeNpi` | `""` | Used to configure the address range properties on the bind request. |

## 📝 Encoding & Message Parameters

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `esm.class` | `""` | Default ESM class to apply to submitted messages. |
| `esm.class.override` | `false` | If true, overrides internal ESM class generation (like UDHI bits for multipart) with the defined `esm.class`. |
| `priority` | `""` | Message priority level to send to the SMSC. |
| `service` | `""` | The service_type parameter to apply to submitted messages. |
| `ccat.8bit` | `true` | Use 8-bit reference numbers for Concatenated SMS (false = 16-bit). |
| `msgType.dcs.map` | `1_0,2_8,4_4` | Maps internal message types (Text, UCS2, Binary) to SMPP Data Coding Schemes (DCS). |
| `dcs.charset.map` | `0_GSM,1_GSM,3_ISO-8859-1,8_UCS-2,4_HEX`| Default DCS to Charset mappings. |
| `dcs.charset.ext` | `""` | Override or add extra DCS-to-Charset mappings (Format: `DCS_CHARSET`). |
| `dlr.charset.fixed` | `""` | Forces a specific character set for decoding Delivery Receipts. |
| `msg.id.type` | `0` | Determines how the SMSC Message ID is parsed (0=StringLiteral, 1=SubmitRespHexDlrDec, 2=SubmitRespDecDlrHex). |

## 🚦 Error Handling & Routing Policies

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `status.retry.worker` | `103,88` | Comma-separated list of SMPP Status codes (e.g. MSGQFUL, THROTTLED) that trigger a local worker retry. |
| `status.retry.router` | `""` | SMPP Status codes that push the message back to the router for another route. |
| `status.retry.router.removeHlr`| `true` | Strips network routing info when pushing a message back to the router. |
| `status.fail` | `""` | SMPP Status codes that immediately fail the message. |
| `status.default` | `RETRY_WORKER` | Default fallback action for unmapped status codes (`RETRY_WORKER`, `RETRY_ROUTER`, `FAIL`). |
| `dlr.errcodes` | `""` | Maps SMSC-specific DLR error codes to internal Sendium error codes (Format: `GatewayErr_InternalErr`). |
| `resp.errcodes`| `""` | Maps SMSC-specific Submit_SM_Resp error codes to internal error codes. |

## 🪝 Forwarding & TLVs

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `forward.mo.url` | `""` | Webhook URL to forward incoming Mobile Originated (MO) messages. |
| `forward.mo.format` | `JSON` | Format for MO forwarding using POST request, such as `JSON` or `FORM`. |
| `registered.tlvs.submit`| `""` | Comma-separated TLVs to append on Submit_SM (Format: `tagName_tagShort`). |
| `registered.tlvs.mo` | `""` | Comma-separated TLVs to extract from Mobile Originated messages. |
| `registered.tlvs.dlr` | `""` | Comma-separated TLVs to extract from Delivery Receipts. |

## 📊 Logging & Diagnostics

SMPP client PDU, response, and MO diagnostics are disabled by default. These logs can include bind passwords, phone numbers, provider message IDs, callback data, and message bodies, so enable them only when the log destination is access-controlled and retention is appropriate.

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `log.pdus` | `false` | Opt-in logging for decoded SMPP Protocol Data Units (PDUs). May include bind passwords, addresses, and message bodies. |
| `log.bytes` | `false` | Opt-in raw hex byte logging of SMPP traffic. Treat as sensitive. |
| `log.pdus.exclude` | `21,2147483669` | Exclude specific Command IDs from PDU logs (defaults to EnquireLink/Resp). |
| `print.resps` | `false` | Opt-in logging for Submit_SM responses with the associated message object. Treat output as sensitive. |
| `print.mos` | `false` | Opt-in logging for incoming Mobile Originated (MO) messages. Treat output as sensitive. |
| `counters` | `true` | Enable session monitoring and performance counters. |
| `connection.healthcheck`| `false` | (Not supported yet) Enable strict connectivity verification before accepting messages. |

---

### Configuration Example

Here is how these base properties look when combined with your SMPP Client configuration:

```properties
# Basic Routing & Type
outSms.instance.testRoute.enable = true
outSms.instance.testRoute.type = smppclient

# Threading & TPS
outSms.instance.testRoute.threadCount = 4
outSms.instance.testRoute.tps = 50

# Retry Logic
outSms.instance.testRoute.maxRetries = 3
outSms.instance.testRoute.fail.action.worker.sleep = 2000

# SMPP Client Specific Bind Details
outSms.instance.testRoute.host = smpp.test.com
outSms.instance.testRoute.port = 2775
outSms.instance.testRoute.username = testSystemId
outSms.instance.testRoute.password = testPass
outSms.instance.testRoute.connections.transceivers = 1
```

## Related Documentation

* [Routing Engine](05-routing-engine.md)
* [Webhooks](07-webhooks.md)
* [Configuration Reference](09-configuration-reference.md)
* [Documentation Map](DocumentationMap.md)
