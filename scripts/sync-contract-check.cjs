const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

const root = path.resolve(__dirname, "..");
const serverPath = path.join(root, "server", "yycloud.cjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "yyslide-sync-"));
const port = 19000 + Math.floor(Math.random() * 20000);
const baseUrl = `http://127.0.0.1:${port}`;

function base32Decode(input) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const char of String(input).toUpperCase().replace(/=+$/g, "")) {
    const value = alphabet.indexOf(char);
    if (value < 0) continue;
    bits += value.toString(2).padStart(5, "0");
  }
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    bytes.push(parseInt(bits.slice(i, i + 8), 2));
  }
  return Buffer.from(bytes);
}

function hotp(secret, counter) {
  const key = base32Decode(secret);
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(counter));
  const hmac = require("crypto").createHmac("sha1", key).update(msg).digest();
  const offset = hmac[hmac.length - 1] & 0x0f;
  const code = ((hmac[offset] & 0x7f) << 24) |
    ((hmac[offset + 1] & 0xff) << 16) |
    ((hmac[offset + 2] & 0xff) << 8) |
    (hmac[offset + 3] & 0xff);
  return String(code % 1000000).padStart(6, "0");
}

function totp(secret) {
  return hotp(secret, Math.floor(Date.now() / 30000));
}

async function json(method, pathname, body, token) {
  const response = await fetch(`${baseUrl}${pathname}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : {};
  if (!response.ok) {
    const error = new Error(data.error || `${method} ${pathname} failed: ${response.status}`);
    error.status = response.status;
    error.data = data;
    throw error;
  }
  return data;
}

function dummyBox(seed) {
  return {
    type: "aes-256-gcm",
    salt: Buffer.from(`salt-${seed}`).toString("base64"),
    iv: Buffer.from(`iv-${seed}`.padEnd(12, ".")).toString("base64"),
    ciphertext: Buffer.from(`cipher-${seed}`).toString("base64"),
    tag: Buffer.from(`tag-${seed}`.padEnd(16, ".")).toString("base64")
  };
}

function dummyVault(seed) {
  return {
    type: "aes-256-gcm",
    iv: Buffer.from(`vault-${seed}`.padEnd(12, ".")).toString("base64"),
    ciphertext: Buffer.from(`payload-${seed}`).toString("base64"),
    tag: Buffer.from(`tag-${seed}`.padEnd(16, ".")).toString("base64")
  };
}

async function waitForServer(child) {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    if (child.exitCode != null) {
      throw new Error(`server exited early with ${child.exitCode}`);
    }
    try {
      await json("GET", "/health");
      return;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 200));
    }
  }
  throw new Error("server did not become ready");
}

function waitForExit(child) {
  if (child.exitCode != null) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    child.once("exit", () => resolve());
    setTimeout(resolve, 1500);
  });
}

function removeTempDir(dir) {
  for (let attempt = 0; attempt < 5; attempt += 1) {
    try {
      fs.rmSync(dir, { recursive: true, force: true });
      return;
    } catch (error) {
      if (attempt === 4) {
        console.warn(`Could not remove temp dir ${dir}: ${error.message}`);
        return;
      }
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 200);
    }
  }
}

async function main() {
  const childEnv = {
    ...process.env,
    NODE_ENV: "test",
    YYSLIDE_CLOUD_HOST: "127.0.0.1",
    YYSLIDE_CLOUD_PORT: String(port),
    YYSLIDE_CLOUD_DATA: tempDir,
    YYSLIDE_CLOUD_DB: path.join(tempDir, "sync-contract.sqlite"),
    YYSLIDE_SERVER_SECRET: "sync-contract-test-secret",
    YYSLIDE_INSECURE_DEV: "0"
  };
  delete childEnv.YYSLIDE_SERVER_SECRET_FILE;
  const child = spawn(process.execPath, [serverPath], {
    cwd: root,
    env: childEnv,
    stdio: ["ignore", "pipe", "pipe"]
  });
  child.stdout.on("data", (chunk) => process.stdout.write(chunk));
  child.stderr.on("data", (chunk) => process.stderr.write(chunk));

  try {
    await waitForServer(child);
    const username = `sync_${Date.now()}`;
    const password = "TestPass123";
    const start = await json("POST", "/v1/auth/register/start", { username });
    const finish = await json("POST", "/v1/auth/register/finish", {
      username,
      password,
      totpCode: totp(start.totpSecret),
      wrappedVaultKey: dummyBox("vault-key"),
      recoveryKeys: Array.from({ length: 10 }, (_, i) => ({
        code: `TEST${i}-CODE${i}`,
        keyBox: dummyBox(`recovery-${i}`)
      }))
    });

    const first = await json("PUT", "/v1/vault", {
      vault: dummyVault("client-a"),
      baseUpdatedAt: null
    }, finish.token);
    if (!first.vault?.updatedAt) {
      throw new Error("first vault save did not return updatedAt");
    }

    let staleRejected = false;
    try {
      await json("PUT", "/v1/vault", {
        vault: dummyVault("stale-client"),
        baseUpdatedAt: null
      }, finish.token);
    } catch (error) {
      staleRejected = error.status === 409 && Boolean(error.data?.vault?.updatedAt);
    }
    if (!staleRejected) {
      throw new Error("stale vault write was not rejected with current vault");
    }

    const current = await json("GET", "/v1/vault", null, finish.token);
    const second = await json("PUT", "/v1/vault", {
      vault: dummyVault("client-b-after-merge"),
      baseUpdatedAt: current.vault.updatedAt
    }, finish.token);
    if (second.vault.updatedAt === first.vault.updatedAt) {
      throw new Error("second vault save did not advance updatedAt");
    }

    console.log("sync contract ok: stale writes are rejected and retry writes advance vault updatedAt");
  } finally {
    child.kill();
    await waitForExit(child);
    removeTempDir(tempDir);
  }
}

main().catch((error) => {
  console.error(error.stack || error.message || String(error));
  process.exit(1);
});
