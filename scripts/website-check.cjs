"use strict";

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "..");
const website = path.join(root, "website");
const htmlPath = path.join(website, "index.html");
const html = fs.readFileSync(htmlPath, "utf8");
const publicTextPaths = [
  htmlPath,
  path.join(website, "script.js"),
  path.join(website, "styles.css"),
  path.join(website, "accessibility.css"),
  path.join(website, "README.md"),
];

const failures = [];
const htmlLocalReferences = [
  ...html.matchAll(/(?:src|href)="(\.\/[^"?#]+)(?:[?#][^"]*)?"/g),
].map((match) => match[1]);
const cssLocalReferences = publicTextPaths
  .filter((filePath) => filePath.endsWith(".css"))
  .flatMap((filePath) => {
    const css = fs.readFileSync(filePath, "utf8");
    return [...css.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/g)]
      .map((match) => match[1])
      .filter((reference) => !/^(?:data:|https?:|#|%23)/i.test(reference))
      .map((reference) => ({ filePath, reference }));
  });

for (const reference of htmlLocalReferences) {
  const resolved = path.resolve(website, reference);
  if (!resolved.startsWith(`${website}${path.sep}`) || !fs.existsSync(resolved)) {
    failures.push(`Missing or invalid local reference: ${reference}`);
  }
}
for (const { filePath, reference } of cssLocalReferences) {
  const resolved = path.resolve(path.dirname(filePath), reference);
  if (!resolved.startsWith(`${website}${path.sep}`) || !fs.existsSync(resolved)) {
    failures.push(`Missing or invalid CSS reference: ${path.relative(root, filePath)} -> ${reference}`);
  }
}

const rootAbsoluteReferences = [
  ...html.matchAll(/(?:src|href)="(\/(?!\/)[^"#]*)/g),
].map((match) => match[1]);
if (rootAbsoluteReferences.length > 0) {
  failures.push(`Root-absolute references break project-site hosting: ${rootAbsoluteReferences.join(", ")}`);
}

const requiredCspDirectives = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'none'",
];
if (!html.includes("Content-Security-Policy")
    || requiredCspDirectives.some((directive) => !html.includes(directive))) {
  failures.push("index.html must define the required restrictive Content-Security-Policy directives");
}
if (!html.includes('<link rel="canonical" href="https://yyslide.ccwu.cc/"')) {
  failures.push("index.html must declare the production canonical URL");
}
if (!html.includes('<meta property="og:url" content="https://yyslide.ccwu.cc/"')) {
  failures.push("index.html must declare the production Open Graph URL");
}

for (const filePath of publicTextPaths) {
  const content = fs.readFileSync(filePath, "utf8");
  if (content.includes("yyslide.yyhvh.com")) {
    failures.push(`${path.relative(root, filePath)} still references the retired Cloud endpoint`);
  }
}

const insecureRemoteReferences = [
  ...html.matchAll(/(?:src|href)="(http:\/\/[^"#]+)(?:#[^"]*)?"/g),
].map((match) => match[1]);
if (insecureRemoteReferences.length > 0) {
  failures.push(`Public remote references must use HTTPS: ${insecureRemoteReferences.join(", ")}`);
}

const assetsDir = path.join(website, "assets");
for (const entry of fs.readdirSync(assetsDir, { withFileTypes: true })) {
  if (!entry.isFile()) continue;
  const assetPath = path.join(assetsDir, entry.name);
  const size = fs.statSync(assetPath).size;
  if (size === 0) {
    failures.push(`Website asset is empty: assets/${entry.name}`);
  } else if (size > 5 * 1024 * 1024) {
    failures.push(`Website asset exceeds the 5 MiB review limit: assets/${entry.name}`);
  }
}

try {
  new vm.Script(fs.readFileSync(path.join(website, "script.js"), "utf8"), {
    filename: "website/script.js",
  });
} catch (error) {
  failures.push(`Website JavaScript is invalid: ${error.message}`);
}

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exitCode = 1;
} else {
  console.log(
    `Website check passed (${htmlLocalReferences.length + cssLocalReferences.length} local references, `
      + `${fs.readdirSync(assetsDir).length} assets).`,
  );
}
