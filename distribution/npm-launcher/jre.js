"use strict";
/*
 * Finds a usable JVM, provisioning one if the machine has none.
 *
 * JDK 17+ was the single hard prerequisite the launcher could not solve for the user: the server
 * is a JVM tool, so "npx and you're done" was only true for people who already had Java. This
 * resolves, in order: DROIDAGENT_JAVA, JAVA_HOME, a new-enough `java` on PATH, an
 * already-provisioned JRE in the cache, and finally a pinned Eclipse Temurin JRE downloaded and
 * verified against jre-manifest.json.
 *
 * The download is install-time only and checksum-verified with the same fail-closed discipline as
 * the CLI jar fetch in index.js: a mismatch deletes the partial download and throws rather than
 * unpacking anything. Nothing here runs while the MCP server is serving.
 */

const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const https = require("node:https");
const http = require("node:http");
const crypto = require("node:crypto");

const MANIFEST = require("./jre-manifest.json");
const MIN_JAVA = 17;
const REQUEST_TIMEOUT_MS = 120_000;
const MAX_REDIRECTS = 5;

function platformKey(platform = process.platform, arch = process.arch) {
  return `${platform}-${arch}`;
}

/**
 * Extracts the major version from a `java -version` banner.
 *
 * Handles both the modern `"17.0.9"` scheme and the legacy `"1.8.0_392"` one, where the major
 * version is the second component. Returns null when the text is not a banner at all, which is
 * what a missing binary produces.
 */
function majorVersionFromBanner(text) {
  if (!text) return null;
  const match = /version "([^"]+)"/.exec(text);
  if (!match) return null;
  const parts = match[1].split(/[._]/);
  const first = Number.parseInt(parts[0], 10);
  if (Number.isNaN(first)) return null;
  if (first === 1) {
    const second = Number.parseInt(parts[1], 10);
    return Number.isNaN(second) ? null : second;
  }
  return first;
}

function defaultProbe(command) {
  return spawnSync(command, ["-version"], { encoding: "utf8" });
}

function probeMajor(probe, command) {
  let result;
  try {
    result = probe(command);
  } catch {
    return null;
  }
  if (!result || result.status !== 0) return null;
  return majorVersionFromBanner(`${result.stderr || ""}${result.stdout || ""}`);
}

function javaExecutableName(platform) {
  return platform === "win32" ? "java.exe" : "java";
}

function cacheRoot(env) {
  return env.DROIDAGENT_JRE_CACHE_DIR || path.join(os.homedir(), ".droidagentkit", "jre");
}

function httpModuleFor(url) {
  return new URL(url).protocol === "http:" ? http : https;
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
      const out = fs.createWriteStream(destPath);
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
  return crypto.createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

/**
 * Unpacks with the platform's own tooling rather than a bundled archive library, keeping the
 * launcher dependency-free. `tar` ships on macOS and Linux; Windows 10+ ships `tar` too, but
 * PowerShell's Expand-Archive is the reliable path for zip there.
 */
function extract(archivePath, destDir, kind, platform) {
  fs.mkdirSync(destDir, { recursive: true });
  const result =
    kind === "zip" && platform === "win32"
      ? spawnSync(
          "powershell",
          ["-NoProfile", "-NonInteractive", "-Command", `Expand-Archive -LiteralPath '${archivePath}' -DestinationPath '${destDir}' -Force`],
          { encoding: "utf8" },
        )
      : spawnSync("tar", ["-xf", archivePath, "-C", destDir], { encoding: "utf8" });
  if (result.error || result.status !== 0) {
    throw new Error(`could not extract ${archivePath}: ${result.error?.message || result.stderr || `exit ${result.status}`}`);
  }
}

/**
 * Temurin archives contain a single top-level directory, and on macOS the runtime sits under
 * Contents/Home. Rather than encode either layout, find the `bin/java` that was actually written.
 */
function findJavaBinary(root, platform) {
  const wanted = javaExecutableName(platform);
  const stack = [root];
  while (stack.length > 0) {
    const dir = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) stack.push(full);
      else if (entry.name === wanted && path.basename(dir) === "bin") return full;
    }
  }
  return null;
}

async function provision(installDir, { platform, arch, manifest = MANIFEST }) {
  const key = platformKey(platform, arch);
  const entry = manifest.platforms[key];
  if (!entry) {
    throw new Error(
      `no pinned JRE for platform ${key}. Install a JDK ${MIN_JAVA}+ (https://adoptium.net) and ` +
        "re-run, or set DROIDAGENT_JAVA to its java binary.",
    );
  }

  const parent = path.dirname(installDir);
  fs.mkdirSync(parent, { recursive: true });
  // Unpack into a per-process staging directory and rename into place only once the checksum has
  // passed, so two concurrent launches can never observe a half-extracted runtime.
  const staging = `${installDir}.${process.pid}.tmp`;
  const archivePath = path.join(parent, `.jre-${key}.${process.pid}.${entry.archive}`);

  try {
    await downloadToFile(entry.url, archivePath);
    const actual = sha256File(archivePath);
    if (actual !== entry.sha256) {
      throw new Error(`JRE checksum mismatch for ${key}: expected ${entry.sha256}, got ${actual}`);
    }
    extract(archivePath, staging, entry.archive, platform);
    const staged = findJavaBinary(staging, platform);
    if (!staged) throw new Error(`extracted JRE for ${key} contains no bin/${javaExecutableName(platform)}`);

    if (fs.existsSync(installDir)) fs.rmSync(installDir, { recursive: true, force: true });
    fs.renameSync(staging, installDir);
  } catch (error) {
    fs.rmSync(staging, { recursive: true, force: true });
    throw error;
  } finally {
    if (fs.existsSync(archivePath)) fs.rmSync(archivePath, { force: true });
  }

  const resolved = findJavaBinary(installDir, platform);
  if (!resolved) throw new Error(`provisioned JRE at ${installDir} contains no java binary`);
  return resolved;
}

/**
 * Returns an absolute path (or the bare name "java") for a JVM meeting the version floor.
 *
 * `probe` and `download` are injected so resolution order can be tested without a real JVM or a
 * 40 MB network fetch.
 */
async function resolveJava(options = {}) {
  const env = options.env || process.env;
  const probe = options.probe || defaultProbe;
  const platform = options.platform || process.platform;
  const arch = options.arch || process.arch;
  const manifest = options.manifest || MANIFEST;

  // Explicit configuration is never second-guessed: if the operator named a java and it is not
  // there, that is an error. Silently running a different JVM would be worse than failing.
  if (env.DROIDAGENT_JAVA) {
    if (!fs.existsSync(env.DROIDAGENT_JAVA)) {
      throw new Error(`DROIDAGENT_JAVA points at ${env.DROIDAGENT_JAVA}, which does not exist`);
    }
    return env.DROIDAGENT_JAVA;
  }

  if (env.JAVA_HOME) {
    const candidate = path.join(env.JAVA_HOME, "bin", javaExecutableName(platform));
    if (fs.existsSync(candidate)) {
      const major = probeMajor(probe, candidate);
      if (major !== null && major >= MIN_JAVA) return candidate;
    }
  }

  const pathMajor = probeMajor(probe, "java");
  if (pathMajor !== null && pathMajor >= MIN_JAVA) return "java";

  const key = platformKey(platform, arch);
  const installDir = path.join(cacheRoot(env), `${manifest.release}-${key}`);
  const cached = findJavaBinary(installDir, platform);
  if (cached) return cached;

  if (!manifest.platforms[key]) {
    throw new Error(
      `no pinned JRE for platform ${key}. Install a JDK ${MIN_JAVA}+ (https://adoptium.net) and ` +
        "re-run, or set DROIDAGENT_JAVA to its java binary.",
    );
  }

  const download = options.download || ((dest) => provision(dest, { platform, arch, manifest }));
  return await download(installDir, { platform, arch, manifest });
}

module.exports = { majorVersionFromBanner, platformKey, resolveJava, provision, MIN_JAVA };
