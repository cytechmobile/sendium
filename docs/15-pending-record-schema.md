# Pending accepted-source record — CBOR schema

This documents the explicit pending-source representation introduced by #343.
It is not Java serialization, a serialization of `StandardMessage`, a file layout,
or a supported durable backend. The standalone runtime still supports only
`memory/memory/memory`. Filesystem admission/restart recovery belongs to #339.

## API and ownership

`PendingSourceRecord` contains the existing typed `OutboundWork.SourceId`, an
accepted disposition (`READY` or `HELD`), and `PendingMessageData`.
`PendingMessageData.capture(StandardMessage)` captures the accepted values using
an explicit mapping; `restore()` returns a new mutable runtime message each time.
Maps, lists and byte arrays are detached from both input and restored messages.
The source ID must equal the gateway UUID in `serial` (canonical UUID syntax,
case-insensitive). Custom `StandardMessage` subclasses are not supported by this
mapper. No existing memory snapshot or ingress behavior is changed by the codec.

`PendingRecordCodec.encode(record)` returns CBOR bytes.
`PendingRecordCodec.decode(bytes)` validates one complete envelope and returns a
record. The codec has no storage, acknowledgement, routing, or lifecycle side
effects. Later admission integration must fail before client success if a selected
durable representation cannot preserve the accepted data. No codec integration
or new backend profile is enabled by this first task.

## Version 1 envelope

The top-level value is a CBOR map with text keys, emitted in this order:

| Field | Type | Required meaning |
|---|---|---|
| `schemaVersion` | Positive signed 32-bit integer | `1` |
| `sourceId` | UUID text | Existing gateway/source UUID |
| `disposition` | Text enum | `READY` or `HELD` |
| `message` | Map | Explicit message fields below |

All documented fields are present, including fields whose value is null.
Missing fields are corruption, not an instruction to use Java constructor
defaults. Null is permitted for message text/reference/container fields as listed
below; it is not interchangeable with empty text, an empty collection, zero or
false. Primitive fields cannot be null. Numeric range/type checks do not coerce
strings, booleans or fractional numbers into integer fields. `dcs` uses the signed
Java byte representation (-128 through 127), preserving all eight bits.

This first task represents complete accepted sources. Multipart group identity,
instance assignment, ordinal, total, original UUID and deadline are added by the
planned multipart task before version 1 is released. `HELD` alone does not claim
that restart-safe multipart reconstruction is implemented.

## Explicit message field inventory

Every public **instance** field of `StandardMessage` is preserved at capture time.
None is implicitly omitted, derived from a new wall-clock time, or obtained by
reflective message serialization. Fields that later change during routing or
provider processing retain their **accepted** value here: this is not a checkpoint
of later runtime execution. Constants, `fieldMap`, the logger, methods and Java
class identity are not message data and are not encoded.

The parameterized `PendingRecordMappingTest#roundTripsEveryPreservedField` discovers
public instance fields for test coverage only. A new field without an explicit
capture/restore mapping fails the test instead of silently inheriting serialization.

| Runtime field | Schema field | Stored type / reason |
|---|---|---|
| `from` | `from` | Nullable text; sender |
| `to` | `to` | Nullable text; recipient |
| `timestamp` | `timestamp` | Nullable text; original timestamp representation |
| `type` | `type` | Int32; message type |
| `body` | `body` | Nullable text; accepted body |
| `serial` | `serial` | UUID text; gateway identity |
| `message_center` | `messageCenter` | Nullable text; message center/input |
| `owner_id` | `ownerId` | Nullable text; account identity |
| `systemId` | `systemId` | Nullable text; system identity |
| `priority` | `priority` | Int32; selection/routing input |
| `acked` | `acked` | Boolean; accepted acknowledgement-related value |
| `msgId` | `msgId` | Int32; accepted message field |
| `expr` | `expr` | Int64; existing eligibility/order input |
| `rtxCnt` | `rtxCnt` | Int32; initial accepted value, not a durable retry checkpoint |
| `binbody` | `binbody` | Nullable text; unchanged existing binary-body representation |
| `binheader` | `binheader` | Nullable text; unchanged UDH representation |
| `flash` | `flash` | Boolean; message formatting |
| `mclass` | `mclass` | Int32; message class |
| `ttl` | `ttl` | Int32; validity input |
| `ddt` | `ddt` | Int32; deferred-delivery input |
| `dcs` | `dcs` | Signed int8; data coding |
| `onetwork` | `onetwork` | Int32; original network/routing input |
| `cnetwork` | `cnetwork` | Int32; current network at acceptance |
| `ingateway` | `ingateway` | Nullable text; ingress identity |
| `outgateway` | `outgateway` | Nullable text; accepted routing/worker input |
| `state` | `state` | Int32; accepted state, not a provider-outcome checkpoint |
| `cost` | `cost` | Int64; accepted processing input |
| `ctstamp` | `ctstamp` | Int64; accepted creation timestamp |
| `sutstamp` | `sutstamp` | Int64; accepted submit timestamp |
| `errcode` | `errcode` | Nullable text; accepted error-related value |
| `extrid` | `extrid` | Nullable text; external identity |
| `nextTarget` | `nextTarget` | Nullable text; routing input |
| `field1` | `field1` | Tagged extension; includes normal HTTP protocol ID input |
| `field2` | `field2` | Tagged extension; includes normal HTTP alternate DCS input |
| `field3` | `field3` | Tagged extension; includes normal HTTP reply-path input |
| `field4` | `field4` | Tagged extension; includes normal HTTP billing-info input |
| `field5` | `field5` | Tagged extension; accepted input only |
| `field6` | `field6` | Tagged extension; accepted input only |
| `field7` | `field7` | Tagged extension; accepted input only |
| `field8` | `field8` | Tagged extension; accepted input only |
| `field9` | `field9` | Tagged extension; accepted input only |
| `field10` | `field10` | Tagged extension; no later filter-phase marker synthesized |
| `paid` | `paid` | Boolean; accepted processing input |
| `metadata` | `metadata` | Nullable text; opaque metadata, not parsed/reformatted |
| `smsCnt` | `smsCnt` | Int32; initial accepted count |
| `smsSubmitCnt` | `smsSubmitCnt` | Int32; initial accepted count |
| `attrs` | `attrs` | Null or tagged string-keyed map; DLR/request and processing inputs |
| `reassembledParts` | `reassembledParts` | Null or array of nullable text; original source identities if present |
| `dlrReturnMetadata` | `dlrReturnMetadata` | Null or explicit DLR map below, despite runtime `@JsonIgnore` |
| `tlvs` | `tlvs` | Null or sorted text-keyed map of nullable text; SMPP TLVs |
| `dispatchType` | `dispatchType` | Int32; dispatch input |
| `hlrRoute` | `hlrRoute` | Nullable text; routing input |
| `marginPercentage` | `marginPercentage` | Finite float64; accepted processing input |
| `marginStatus` | `marginStatus` | Null or named known enum (`NOT_CALCULATED`, `KNOWN`, `UNKNOWN`) |

Preserving an initial count, state or routing field does not authorize persisting
later retries/outcomes or routing-time filter mutations as source state. Recovering
only the pending stage reruns selection/routing, as defined by the epic.

### DLR return map

`channel` is required non-null enum text (`HTTP` or `SMPP`). `accountId`, `systemId`,
`sourceAddress`, `destinationAddress` and `forwardDlrUrl` are required keys with
nullable text values. They preserve the corresponding `DlrReturnMetadata` record
components verbatim. Request flags in the message's normal protocol fields/attrs
are preserved by the message mapping; no DLR backend is serialized here.

## Extension vocabulary

An extension is either CBOR null, or a map containing `type` and `value`:

| `type` | `value` | Restored Java value |
|---|---|---|
| `string` | Non-null text | `String` |
| `boolean` | Boolean | `Boolean` |
| `int8` | Integer in signed 8-bit range | `Byte` |
| `int16` | Integer in signed 16-bit range | `Short` |
| `int32` | Integer in signed 32-bit range | `Integer` |
| `int64` | Integer in signed 64-bit range | `Long` |
| `float32` | Finite number exactly representable as float32 | `Float` |
| `float64` | Finite number | `Double` |
| `bytes` | Native CBOR byte string, not base64 text | Detached `byte[]` |
| `list` | Array of extensions | Detached `ArrayList` |
| `map` | Text-keyed map of extensions | Detached map |

Map keys are emitted in Java `String` natural order (at every nesting level).
Array order is retained. Scalar tags distinguish, for example, integer `1` from
long `1L` even if CBOR's preferred integer representation uses the same width.
This is deterministic encoding by this schema/codec, not a claim of implementing
every RFC canonical-CBOR option or preserving original collection classes.

Example in diagnostic JSON notation (actual bytes are CBOR):

```json
{"type":"map","value":{"attemptLimit":{"type":"int32","value":3},"campaign":{"type":"string","value":"summer"}}}
```

Unsupported values include arbitrary application objects/enums, characters,
non-string map keys, other array types, NaN/infinities, cyclic containers and
nesting beyond 64 container levels (root is level 0; levels 0–64 are supported).
Shared acyclic containers may occur in several places and are copied as values.
The codec rejects unsupported data explicitly; it never substitutes `toString()`
or skips an unsupported entry. Custom before-insert filter object support is
deferred. Existing in-memory snapshot rules remain unchanged.

## Validation, compatibility and failures

- Positive unknown schema versions produce `PendingRecordException` with
  `kind() == UNSUPPORTED_VERSION` and the existing storage reason `UNSUPPORTED`.
- Malformed/truncated CBOR, missing required fields, invalid primitive types or
  ranges, unknown enum/type tags, mismatched gateway/source UUIDs, duplicate map
  keys, invalid extension values and trailing CBOR values produce
  `kind() == CORRUPT_RECORD` with storage reason `UNAVAILABLE`. A corrupt source
  cannot be treated as absent. These codec-specific details avoid expanding
  protocol-facing reason switches before admission integration.
- Encode/capture of unsupported application values produces storage reason
  `UNSUPPORTED`. All codec errors identify pending stage and codec context, with
  fixed diagnostics and no parser-cause chain containing payload values.
- Unknown optional keys within version 1 are ignored. They cannot change the
  meaning of required fields; semantic changes require a new version. Unknown
  fields are not promised to survive decode/re-encode.
- No Java class names are interpreted, no Jackson default typing is enabled,
  and the runtime `StandardMessage` JSON contract is untouched.
- The parser limits total CBOR nesting to 256, sufficient for this tagged
  extension vocabulary's 64-level limit and envelope overhead.
- There is no earlier durable pending schema to migrate in this delivery.
  Backward migration/version adapters are not implemented speculatively.

The codec detects structural and semantic corruption. It is not a checksum or
an authenticated format: arbitrary bit changes that still form valid supported
values cannot all be detected by the codec. File publication/integrity policy is
owned by the physical backend implementation.
