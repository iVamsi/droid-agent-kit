#!/usr/bin/env node
/*
 * DroidAgentKit MCP launcher.
 *
 * Default behavior: downloads (once, then caches) the `droidagent-cli` fat jar matching this
 * launcher's own version from GitHub Releases, verifies its SHA-256, and runs it with
 * `java -jar ... serve-mcp --transport stdio --project auto`.
 *
 * Set DROIDAGENT_BIN to an existing `droidagent` CLI to skip auto-fetch entirely (e.g. a local
 * `./gradlew :cli:installDist` build). Node is an install-time shim only; the MCP server itself
 * is pure JVM and has no Node runtime dependency. The launcher fails closed on any error rather
 * than silently degrading the MCP connection.
 */
"use strict";

const { spawn, spawnSync } = require("node:child_process");
const { existsSync, mkdirSync, renameSync, unlinkSync, createWriteStream, readFileSync } = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const http = require("node:http");
const https = require("node:https");
const crypto = require("node:crypto");

const LAUNCHER_VERSION = require("./package.json").version;

const GITHUB_OWNER = "iVamsi";
const GITHUB_REPO = "droid-agent-kit";
const DEFAULT_RELEASE_BASE_URL = `https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}/releases/download`;
const MAX_REDIRECTS = 5;
const REQUEST_TIMEOUT_MS = 60_000;

function cacheDir() {
  return process.env.DROIDAGENT_CACHE_DIR || path.join(os.homedir(), ".droidagentkit", "cli");
}

function jarFileName(version) {
  return `droidagent-cli-${version}.jar`;
}

// Overridable only for tests (see smoke-test.sh); production always uses the real GitHub
// Releases URL for this repo.
function releaseAssetUrl(version, fileName) {
  const base = process.env.DROIDAGENT_RELEASE_BASE_URL || DEFAULT_RELEASE_BASE_URL;
  return `${base}/v${version}/${fileName}`;
}

function httpModuleFor(url) {
  return new URL(url).protocol === "http:" ? http : https;
}

function fetchBuffer(url, redirectsLeft = MAX_REDIRECTS) {
  return new Promise((resolve, reject) => {
    const req = httpModuleFor(url).get(url, { timeout: REQUEST_TIMEOUT_MS }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirectsLeft > 0) {
        res.resume();
        resolve(fetchBuffer(res.headers.location, redirectsLeft - 1));
        return;
      }
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error(`HTTP ${res.statusCode} fetching ${url}`));
        return;
      }
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => resolve(Buffer.concat(chunks)));
      res.on("error", reject);
    });
    req.on("timeout", () => req.destroy(new Error(`timed out fetching ${url}`)));
    req.on("error", reject);
  });
}

function downloadToFile(url, destPath, redirectsLeft = MAX_REDIRECTS) {
  return new Promise((resolve, reject) => {
    const req = httpModuleFor(url).get(url, { timeout: REQUEST_TIMEOUT_MS }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirectsLeft > 0) {
        res.resume();
        resolve(downloadToFile(res.headers.location, destPath, redirectsLeft - 1));
        return;
      }
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error(`HTTP ${res.statusCode} fetching ${url}`));
        return;
      }
      const out = createWriteStream(destPath);
      res.pipe(out);
      out.on("finish", () => out.close(() => resolve()));
      out.on("error", reject);
      res.on("error", reject);
    });
    req.on("timeout", () => req.destroy(new Error(`timed out fetching ${url}`)));
    req.on("error", reject);
  });
}

function sha256File(filePath) {
  return crypto.createHash("sha256").update(readFileSync(filePath)).digest("hex");
}

function parseChecksum(buffer) {
  const text = buffer.toString("utf8").trim();
  const hash = text.split(/\s+/)[0] ?? "";
  if (!/^[0-9a-f]{64}$/i.test(hash)) {
    throw new Error(`unrecognized checksum file format: ${text.slice(0, 80)}`);
  }
  return hash.toLowerCase();
}

// Downloads and verifies the droidagent-cli fat jar for `version` into the cache dir, or
// reuses it if already present. Fails closed: a checksum mismatch deletes the partial download
// and throws rather than falling back to an unverified jar. Verification only happens on a
// fresh download; a cache hit is trusted on the strength of that earlier verification, so a
// GitHub outage doesn't block an already-working install.
async function ensureCliJar(version) {
  const dir = cacheDir();
  const finalPath = path.join(dir, jarFileName(version));
  if (existsSync(finalPath)) {
    return finalPath;
  }

  mkdirSync(dir, { recursive: true });
  const fileName = jarFileName(version);
  const jarUrl = releaseAssetUrl(version, fileName);
  const checksumUrl = `${jarUrl}.sha256`;
  const tmpPath = path.join(dir, `.${fileName}.${process.pid}.tmp`);

  let expectedHash;
  try {
    expectedHash = parseChecksum(await fetchBuffer(checksumUrl));
  } catch (err) {
    throw new Error(`could not fetch checksum from ${checksumUrl}: ${err.message}`);
  }

  try {
    await downloadToFile(jarUrl, tmpPath);
    const actualHash = sha256File(tmpPath);
    if (actualHash !== expectedHash) {
      throw new Error(`checksum mismatch: expected ${expectedHash}, got ${actualHash}`);
    }
    renameSync(tmpPath, finalPath);
  } catch (err) {
    if (existsSync(tmpPath)) unlinkSync(tmpPath);
    throw err;
  }
  return finalPath;
}

function javaAvailable() {
  return !spawnSync("java", ["-version"]).error;
}

function spawnServer(command, args) {
  const child = spawn(command, args, { stdio: "inherit" });
  child.on("error", (err) => {
    process.stderr.write(`droidagent-mcp: failed to launch '${command}': ${err.message}\n`);
    process.exit(127);
  });
  child.on("exit", (code, signal) => {
    if (signal) process.exit(1);
    process.exit(code ?? 1);
  });
}

function printHelp() {
  process.stdout.write(
    "Usage: droidagent-mcp [--version | --help]\n" +
      "       (no args)  fetch (if needed) and run the DroidAgentKit MCP server over stdio\n" +
      "\n" +
      "Env:\n" +
      "  DROIDAGENT_BIN        absolute path to an existing droidagent CLI (skips auto-fetch)\n" +
      "  DROIDAGENT_CACHE_DIR  override the jar download cache (default ~/.droidagentkit/cli)\n"
  );
}

function resolveOverrideBin() {
  if (process.env.DROIDAGENT_BIN && existsSync(process.env.DROIDAGENT_BIN)) {
    return process.env.DROIDAGENT_BIN;
  }
  return null;
}

async function main(argv) {
  if (argv.includes("--version") || argv.includes("-v")) {
    process.stdout.write(`droidagent-mcp launcher ${LAUNCHER_VERSION}\n`);
    return 0;
  }
  if (argv.includes("--help") || argv.includes("-h")) {
    printHelp();
    return 0;
  }

  const overrideBin = resolveOverrideBin();
  if (overrideBin) {
    spawnServer(overrideBin, ["serve-mcp", "--transport", "stdio", "--project", "auto"]);
    return null;
  }

  if (!javaAvailable()) {
    process.stderr.write(
      "droidagent-mcp: no working `java` found on PATH.\n" +
        "DroidAgentKit's server is a JVM tool and needs a JDK 17+ runtime (e.g. https://adoptium.net).\n" +
        "Alternatively, set DROIDAGENT_BIN to a prebuilt droidagent CLI.\n"
    );
    process.exit(1);
  }

  let jarPath;
  try {
    jarPath = await ensureCliJar(LAUNCHER_VERSION);
  } catch (err) {
    process.stderr.write(
      `droidagent-mcp: could not fetch droidagent-cli ${LAUNCHER_VERSION}: ${err.message}\n` +
        "Set DROIDAGENT_BIN to a prebuilt droidagent CLI to bypass auto-fetch.\n"
    );
    process.exit(1);
  }

  spawnServer("java", ["-jar", jarPath, "serve-mcp", "--transport", "stdio", "--project", "auto"]);
  return null;
}

main(process.argv.slice(2))
  .then((code) => {
    if (code !== null && code !== undefined) process.exit(code);
  })
  .catch((err) => {
    process.stderr.write(`droidagent-mcp: unexpected error: ${err.message}\n`);
    process.exit(1);
  });
