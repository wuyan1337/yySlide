---
layout: default
title: Backup file format
---

# yySlide backup file format

yySlide exports its connection list, SSH keys, known hosts, port-forward
rules, tunnel configs and a small block of settings as a single
encrypted file. The default extension is `.enc`. This page documents
the wire format and ships a small Python recipe so you can decrypt one
manually when the in-app importer can't (for example: corrupted
transfer, lost device, debugging a failed restore on a new phone).

The in-app importer is always the preferred recovery path — the
manual route reproduces the on-device decryption byte-for-byte but
gives you back the raw JSON, not a working app. You'll need to copy
fields back into a fresh install yourself.

The envelope intentionally retains the legacy magic value
`HAVEN_BACKUP_V1`. This is an upstream compatibility identifier, not the
current product name: keeping it allows yySlide to read existing compatible
backups without changing the encrypted wire format.

## Wire format

A backup file is a single byte stream:

```
+-----------------+-----------------+-----------------+-------------------+
| MAGIC (15)      | salt (16)       | iv (12)         | AES-GCM ciphertext|
| HAVEN_BACKUP_V1 | random          | random          | (with 16-byte tag |
|                 |                 |                 |  appended by GCM) |
+-----------------+-----------------+-----------------+-------------------+
```

| Field | Bytes | Notes |
|---|---|---|
| MAGIC | 15 | Literal ASCII `HAVEN_BACKUP_V1`, retained for compatibility with upstream and earlier compatible backups. It stays at `_V1` even though the inner JSON schema is at v2 because the envelope has not changed. |
| salt | 16 | Random per-export, used as the PBKDF2 salt. |
| iv | 12 | Random per-export, GCM IV / nonce. |
| ciphertext | rest | AES-256-GCM over the JSON payload, with the 16-byte authentication tag appended (standard Java GCM convention — `Cipher.doFinal()` writes the tag to the tail of the output). |

**Key derivation.** PBKDF2 with HMAC-SHA-256, 100,000 iterations,
256-bit output. The password is UTF-8 encoded.

**Cipher.** AES-256/GCM, 128-bit authentication tag, no associated
data. The MAGIC, salt, and IV bytes are *not* covered by the GCM
tag — they're plain prefix metadata. Swapping any of them produces
an authentication failure on decrypt because the derived key /
nonce mismatch.

The plaintext is a single UTF-8 JSON object. Top-level keys (any
subset present, all optional except `version`):

```
{
  "version": 2,
  "created": <ms-since-epoch>,
  "connections": [ { "id": ..., "label": ..., ... }, ... ],
  "groups":      [ ... ],
  "keys":        [ { "id": ..., "privateKeyBytes": "<base64>", ... }, ... ],
  "knownHosts":  [ ... ],
  "portForwards":[ ... ],
  "tunnels":     [ { "configText": "<base64>", ... }, ... ],
  "settings":    { ... }
}
```

`version: 1` files are still readable — the v2 fields default if
absent. Future versions are rejected with a non-zero exit on the
in-app importer; the manual decrypter below ignores the version
field entirely (the JSON is up to you).

## Manual decryption (Python)

This recipe needs Python 3.8+ and the `cryptography` package
(`pip install cryptography`).

```python
#!/usr/bin/env python3
"""Decrypt a yySlide backup (.enc) file to JSON on stdout.

Usage: ./decrypt-yyslide-backup.py <file.enc> <password>
"""
import sys
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"HAVEN_BACKUP_V1"
SALT_LEN = 16
IV_LEN = 12
ITERATIONS = 100_000


def decrypt_yyslide_backup(data: bytes, password: str) -> bytes:
    if not data.startswith(MAGIC):
        raise ValueError("Not a yySlide backup file (bad magic header)")
    if len(data) < len(MAGIC) + SALT_LEN + IV_LEN + 16:  # 16 = GCM tag
        raise ValueError(f"File too short ({len(data)} bytes) — likely truncated in transit")
    off = len(MAGIC)
    salt = data[off:off + SALT_LEN]
    off += SALT_LEN
    iv = data[off:off + IV_LEN]
    off += IV_LEN
    ciphertext = data[off:]
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        iterations=ITERATIONS,
    )
    key = kdf.derive(password.encode("utf-8"))
    return AESGCM(key).decrypt(iv, ciphertext, associated_data=None)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    with open(sys.argv[1], "rb") as f:
        data = f.read()
    try:
        plaintext = decrypt_yyslide_backup(data, sys.argv[2])
    except Exception as e:
        print(f"decrypt failed: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)
    sys.stdout.buffer.write(plaintext)
```

Save it as `decrypt-yyslide-backup.py` and run:

```bash
python3 decrypt-yyslide-backup.py mybackup.enc 'mypassword' > mybackup.json
jq '.connections | length' mybackup.json
```

A successful run leaves you with a JSON document you can grep with
`jq` or paste into another tool. SSH key material is base64-encoded
under `keys[*].privateKeyBytes`; tunnel config text (WireGuard etc.)
is base64-encoded under `tunnels[*].configText`.

## Common failure modes

| You see | Meaning |
|---|---|
| `Not a yySlide backup file (bad magic header)` | The first 15 bytes aren't the compatible `HAVEN_BACKUP_V1` header. The file is either truncated, prefixed with something extra (BOM, transfer-tool header), or not a compatible yySlide backup. |
| `File too short … truncated in transit` | The file is shorter than the minimum envelope (15 + 16 + 12 + 16 = 59 bytes). Re-export or re-transfer. |
| `InvalidTag` from `cryptography` | Either the password is wrong **or** the ciphertext was modified. Compare `sha256sum` on both devices to rule out transit corruption. |

## Verifying transfer integrity

If the in-app importer fails after moving a file between devices,
check the file is byte-identical on both ends before suspecting the
app:

```bash
# on the source device (or via adb pull)
sha256sum mybackup.enc
# on the destination device (or via adb pull)
sha256sum mybackup.enc
```

A mismatch means a sync app, mailer, or filesystem rewrote the bytes
in transit — the most common root cause behind a "valid password,
import still fails" report.

## Security notes

- The 100,000-iteration PBKDF2 on a strong (≥ 12-character random)
  password is comfortable against opportunistic attackers but not
  resistant to a well-funded attacker with custom hardware. If you're
  worried about that adversary, use a passphrase, not a password.
- AES-GCM is authenticated — any modification to ciphertext, salt, or
  IV is detected on decrypt. There's no plaintext-known-prefix /
  oracle attack surface here.
- The plaintext JSON contains stored SSH key material and any saved
  passwords. Treat the decrypted output the same way you'd treat
  `~/.ssh/id_*` plus your password manager export. Don't leave it on
  disk.
