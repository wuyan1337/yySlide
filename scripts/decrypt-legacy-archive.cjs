"use strict";

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

function fail(message) {
  console.error("Legacy archive restore failed: " + message);
  process.exit(1);
}

function loadSecret() {
  const inlineSecret = process.env.YYSLIDE_SERVER_SECRET || "";
  const secretFile = process.env.YYSLIDE_SERVER_SECRET_FILE || "";
  if (inlineSecret && secretFile) {
    fail("set only one of YYSLIDE_SERVER_SECRET and YYSLIDE_SERVER_SECRET_FILE.");
  }
  if (secretFile) {
    try {
      const stat = fs.statSync(secretFile);
      if (!stat.isFile() || stat.size < 1 || stat.size > 4096
          || process.platform !== "win32" && (stat.mode & 0o077) !== 0) {
        fail("YYSLIDE_SERVER_SECRET_FILE must be a mode-600 regular file no larger than 4096 bytes.");
      }
      return fs.readFileSync(secretFile, "utf8").trim();
    } catch {
      fail("YYSLIDE_SERVER_SECRET_FILE cannot be read.");
    }
  }
  if (!inlineSecret) {
    fail("YYSLIDE_SERVER_SECRET or YYSLIDE_SERVER_SECRET_FILE is required.");
  }
  return inlineSecret;
}

const archiveArg = process.argv[2];
const outputArg = process.argv[3];
if (!archiveArg || !outputArg) {
  fail("usage: node scripts/decrypt-legacy-archive.cjs <archive.enc> <output.json>");
}

const archivePath = path.resolve(archiveArg);
const outputPath = path.resolve(outputArg);
if (archivePath === outputPath) {
  fail("the archive and output paths must be different.");
}
if (fs.existsSync(outputPath)) {
  fail("the output path already exists; refusing to overwrite it.");
}

let envelope;
try {
  const archiveStat = fs.statSync(archivePath);
  if (!archiveStat.isFile() || archiveStat.size < 1 || archiveStat.size > 150 * 1024 * 1024) {
    fail("the archive must be a regular file no larger than 150 MiB.");
  }
  envelope = JSON.parse(fs.readFileSync(archivePath, "utf8"));
} catch {
  fail("the archive is not valid JSON.");
}
if (envelope.format !== "yyslide-legacy-db-json" || envelope.version !== 1
    || !envelope.cipher || envelope.cipher.type !== "aes-256-gcm") {
  fail("the archive format is not supported.");
}

let plaintext;
try {
  const key = crypto.createHash("sha256").update(loadSecret()).digest();
  const cipher = envelope.cipher;
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, Buffer.from(cipher.iv, "base64"));
  decipher.setAAD(Buffer.from("yySlide:legacy-db-json-archive:v1"));
  decipher.setAuthTag(Buffer.from(cipher.tag, "base64"));
  plaintext = Buffer.concat([
    decipher.update(Buffer.from(cipher.ciphertext, "base64")),
    decipher.final()
  ]);
  JSON.parse(plaintext.toString("utf8"));
} catch {
  fail("the archive could not be decrypted with this server secret.");
}

fs.mkdirSync(path.dirname(outputPath), { recursive: true, mode: 0o700 });
const tempPath = outputPath + "." + process.pid + ".tmp";
let handle;
let writeError = null;
try {
  handle = fs.openSync(tempPath, "wx", 0o600);
  fs.writeFileSync(handle, plaintext);
  fs.fsyncSync(handle);
} catch (error) {
  writeError = error;
} finally {
  if (handle != null) {
    fs.closeSync(handle);
  }
}
if (writeError) {
  fs.rmSync(tempPath, { force: true });
  fail("the restored JSON could not be written.");
}
let linkedOutput = false;
try {
  fs.linkSync(tempPath, outputPath);
  linkedOutput = true;
  fs.unlinkSync(tempPath);
} catch {
  if (linkedOutput) {
    try {
      fs.unlinkSync(outputPath);
    } catch {
      // The failure below remains primary; the user can remove the newly-created output manually.
    }
  }
  try {
    fs.rmSync(tempPath, { force: true });
  } catch {
    // The failure below remains primary.
  }
  plaintext.fill(0);
  fail("the restored JSON could not be published without overwriting an existing path.");
}
if (process.platform !== "win32") {
  fs.chmodSync(outputPath, 0o600);
  const directoryHandle = fs.openSync(path.dirname(outputPath), "r");
  try {
    fs.fsyncSync(directoryHandle);
  } finally {
    fs.closeSync(directoryHandle);
  }
}
plaintext.fill(0);
console.log("Restored legacy JSON to " + outputPath + ".");
