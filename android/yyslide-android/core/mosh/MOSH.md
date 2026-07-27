# Mosh Protocol Implementation

Pure Kotlin implementation of the mosh (mobile shell) client protocol,
replacing the prebuilt C++ `libmoshclient.so` binary.

## Architecture

```
SSH bootstrap (ConnectionsViewModel)
  └─ exec "mosh-server new" → parse MOSH CONNECT <port> <key>

MoshTransport (pure Kotlin, in-process)
  ├─ MoshCrypto      AES-128-OCB via Bouncy Castle
  ├─ MoshConnection   UDP socket, timestamps, zlib, fragments
  ├─ WireFormat       Hand-coded protobuf (mosh wire format)
  ├─ UserStream       Client→server state (keystrokes + resizes)
  └─ SSP engine       State Synchronization Protocol
        │
        ▼
  TerminalViewModel.onDataReceived → termlib emulator renders
```

No PTY, no JNI, no native code. The mosh server sends VT100 escape
sequences (via `Display::new_frame()`) which are fed directly to
connectbot's termlib.

## Wire Format

### Packet structure
```
[8-byte nonce][AES-128-OCB(plaintext) + 16-byte auth tag]
```

### Plaintext structure
```
[2-byte timestamp][2-byte timestamp_reply][10-byte fragment header][payload]
```

### Fragment header
```
[8-byte fragment_id (uint64 BE)][2-byte combined (bit15=final, bits0-14=frag_num)]
```

After fragment reassembly, the payload is **zlib decompressed**, then
parsed as a `TransportBuffers.Instruction` protobuf.

### Nonce
- 12-byte OCB nonce = `[4 zero bytes][8-byte nonce value]`
- Nonce value: bit 63 = direction (0=client→server, 1=server→client),
  bits 0-62 = monotonic sequence number
- The 8-byte nonce value is sent in the packet header

### Key
- 128-bit AES key, base64-encoded (22 chars without padding) as `MOSH_KEY`

## Protobuf Definitions

Hand-coded encoder/decoder in `WireFormat.kt`. No protobuf plugin needed.
Field numbers match upstream mosh `src/protobufs/`:

### `TransportBuffers.Instruction`
| Field | Type   | Number | Notes |
|-------|--------|--------|-------|
| protocol_version | uint32 | 1 | Always 2 |
| old_num | uint64 | 2 | Base state for diff |
| new_num | uint64 | 3 | Target state |
| ack_num | uint64 | 4 | Latest received remote state |
| throwaway_num | uint64 | 5 | Oldest state sender still has |
| diff | bytes | 6 | Serialized UserMessage or HostMessage |
| chaff | bytes | 7 | Random padding (ignored) |

**Critical**: All fields must be written explicitly, even when 0.
Proto2 `has_xxx()` returns false for absent fields, and the mosh server
uses `has_old_num()` to decide whether to apply the diff.

### `ClientBuffers` (client→server diff)
```
UserMessage { repeated Instruction instruction = 1; }
Instruction { extensions 2 to max; }
  ext 2: Keystroke { optional bytes keys = 4; }
  ext 3: ResizeMessage { optional int32 width = 5; optional int32 height = 6; }
```

### `ServerBuffers` (server→client diff)
```
HostMessage { repeated Instruction instruction = 1; }
Instruction { extensions 2 to max; }
  ext 2: HostBytes { optional bytes hoststring = 4; }
  ext 3: ResizeMessage { optional int32 width = 5; optional int32 height = 6; }
  ext 7: EchoAck { optional uint64 echo_ack_num = 8; }
```

## SSP State Machine

Two state spaces are synchronized independently:

1. **Client→Server (UserStream)**: keystrokes and resizes
   - Each byte is a separate action/state increment
   - `diffFrom(oldNum)` returns serialized `UserMessage` with actions since `oldNum`

2. **Server→Client (Complete terminal)**: VT100 escape sequences
   - `HostBytes.hoststring` contains output from `Display::new_frame()`
   - Fed directly to termlib — no local framebuffer needed

### Send timing
- New keystrokes: send within 20ms
- New ack to convey: send within 20ms
- Retransmit unacked data: exponential backoff 100ms→200ms→400ms→800ms
- Keepalive: every 3 seconds
- Poll interval: 100ms max sleep between iterations

### Key invariants
- `throwawayNum` must be from the **UserStream** state space (= `serverAckedOurNum`),
  NOT the terminal state space. Mixing these causes the server to discard
  UserStream states the client still references in `oldNum`.
- Unconnected UDP socket on Android — connected sockets propagate ICMP errors
  from stale sessions as `PortUnreachableException`.

## Bugs Found During Implementation

1. **Missing `old_num` field**: Proto2 omits fields with default value (0).
   Mosh server checks `has_old_num()` and silently ignores diffs without it.
   Fix: always write all TransportInstruction fields.

2. **Wrong `throwaway_num` state space**: Was set to `remoteStateNum`
   (terminal states) instead of `serverAckedOurNum` (UserStream states).
   Server discarded UserStream states the client needed as diff bases.

3. **Connected UDP socket on Android**: `DatagramSocket.connect()` causes
   Android to deliver ICMP "port unreachable" as exceptions, killing the
   session when stale mosh-server processes exist on the same port.

4. **Main thread network**: `DatagramSocket` creation with DNS resolution
   triggers Android StrictMode. Socket creation deferred to IO coroutine.

## Not Implemented

- **Speculative local echo / prediction**: typed characters are not shown
  until the server echoes them back. Adds ~100ms perceived latency on LAN.
- **Roaming detection**: UDP source address changes are handled by the
  mosh server automatically, but reconnection UI is not shown.
- **RTT estimation**: timestamps are sent but not used for adaptive timing.
