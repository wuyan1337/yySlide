const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const androidRoot = path.join(root, "android", "yyslide-android");

const includeDirs = [
  "app/src/main/kotlin",
  "feature/connections/src/main/kotlin",
  "feature/sftp/src/main/kotlin",
  "feature/settings/src/main/kotlin",
  "feature/terminal/src/main/kotlin",
  "feature/keys/src/main/kotlin",
  "feature/tunnel/src/main/kotlin"
].map((item) => path.join(androidRoot, item));

const allowPatterns = [
  /Log\.[a-z]+\(/,
  /Regex\(/,
  /Intent\(/,
  /ClipData\.newPlainText\(/,
  /SimpleDateFormat\(/,
  /String\.format\(/,
  /DateUtils\./,
  /contentDescription\s*=\s*null/,
  /placeholder\s*=\s*\{\s*Text\("user:group"\)/,
  /Text\("\$\{/,
  /Text\("[^"]*:\/\/[^"]*"\)/,
  /Text\("[A-Z0-9_ .:/@%+<>=~|-]+"\)/,
  /Text\("[0-9.,: /%+-]+"\)/
];

const checks = [
  { name: "Toast hardcoded string", pattern: /Toast\.makeText\([^,\n]+,\s*"[^"]+"/ },
  { name: "Snackbar hardcoded string", pattern: /showSnackbar\(\s*"[^"]+"/ },
  { name: "Compose Text hardcoded string", pattern: /Text\(\s*"[^"$][^"]*"/ },
  { name: "contentDescription hardcoded string", pattern: /contentDescription\s*=\s*"[^"]+"/ },
  { name: "Dialog/AppBar hardcoded Text title", pattern: /title\s*=\s*\{\s*Text\(\s*"[^"]+"/ }
];

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (!["build", ".gradle"].includes(entry.name)) walk(full, out);
    } else if (entry.isFile() && full.endsWith(".kt")) {
      out.push(full);
    }
  }
  return out;
}

const findings = [];
for (const file of includeDirs.flatMap((dir) => walk(dir))) {
  const relative = path.relative(root, file).replace(/\\/g, "/");
  const lines = fs.readFileSync(file, "utf8").split(/\r?\n/);
  lines.forEach((line, index) => {
    if (allowPatterns.some((pattern) => pattern.test(line))) return;
    for (const check of checks) {
      if (check.pattern.test(line)) {
        findings.push({
          file: relative,
          line: index + 1,
          name: check.name,
          text: line.trim()
        });
      }
    }
  });
}

if (findings.length) {
  console.error(`Android copy audit found ${findings.length} possible hardcoded UI strings:\n`);
  for (const item of findings) {
    console.error(`${item.file}:${item.line}  ${item.name}`);
    console.error(`  ${item.text}`);
  }
  process.exit(1);
}

console.log("Android copy audit passed: no obvious hardcoded UI strings in checked modules.");
