"use strict";

const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const fs = require("node:fs");
const os = require("node:os");

const { majorVersionFromBanner, platformKey, resolveJava } = require("../jre.js");

function tempDir(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

/** A stand-in for spawnSync's result, so version detection is testable without a real JVM. */
function banner(text, status = 0) {
  return () => ({ status, stderr: text, stdout: "" });
}

test("parses the major version out of a java -version banner", () => {
  // `java -version` writes to stderr, and the format differs across vendors and eras.
  assert.strictEqual(majorVersionFromBanner('openjdk version "17.0.9" 2023-10-17'), 17);
  assert.strictEqual(majorVersionFromBanner('openjdk version "21.0.2" 2024-01-16'), 21);
  assert.strictEqual(majorVersionFromBanner('java version "1.8.0_392"'), 8);
  assert.strictEqual(majorVersionFromBanner('openjdk version "17" 2021-09-14'), 17);
  assert.strictEqual(majorVersionFromBanner("not a version banner"), null);
  assert.strictEqual(majorVersionFromBanner(""), null);
});

test("platform key covers the manifest's supported set", () => {
  const manifest = require("../jre-manifest.json");
  assert.ok(manifest.platforms[platformKey("darwin", "arm64")], "darwin-arm64 must be pinned");
  assert.ok(manifest.platforms[platformKey("linux", "x64")], "linux-x64 must be pinned");
  assert.ok(manifest.platforms[platformKey("win32", "x64")], "win32-x64 must be pinned");
  assert.strictEqual(platformKey("sunos", "sparc"), "sunos-sparc");
});

test("DROIDAGENT_JAVA wins over everything else", async () => {
  const dir = tempDir("dak-jre-explicit-");
  const explicit = path.join(dir, "java");
  fs.writeFileSync(explicit, "#!/bin/sh\n");
  fs.chmodSync(explicit, 0o755);

  const resolved = await resolveJava({
    env: { DROIDAGENT_JAVA: explicit },
    probe: banner('openjdk version "17.0.9"'),
    download: () => assert.fail("must not download when DROIDAGENT_JAVA is set"),
  });

  assert.strictEqual(resolved, explicit);
});

test("an explicitly configured java that does not exist is an error, not a silent fallback", async () => {
  // Falling back would run a *different* JVM than the operator named, which is worse than failing.
  await assert.rejects(
    () =>
      resolveJava({
        env: { DROIDAGENT_JAVA: path.join(tempDir("dak-jre-missing-"), "nope") },
        probe: banner('openjdk version "17.0.9"'),
        download: () => assert.fail("must not download"),
      }),
    /DROIDAGENT_JAVA/,
  );
});

test("a PATH java of 17 or newer is used as-is", async () => {
  let downloaded = false;
  const resolved = await resolveJava({
    env: {},
    probe: banner('openjdk version "17.0.9" 2023-10-17'),
    download: () => {
      downloaded = true;
    },
  });

  assert.strictEqual(resolved, "java");
  assert.strictEqual(downloaded, false, "a usable JVM must not trigger a download");
});

test("a PATH java older than 17 is rejected and provisioning takes over", async () => {
  let downloadedTo = null;
  const resolved = await resolveJava({
    env: {},
    probe: banner('java version "11.0.20"'),
    download: async (dest) => {
      downloadedTo = dest;
      return path.join(dest, "bin", "java");
    },
  });

  assert.ok(downloadedTo, "java 11 must not satisfy the JDK 17 floor");
  assert.ok(resolved.endsWith(path.join("bin", "java")));
});

test("JAVA_HOME is consulted before falling back to a download", async () => {
  const home = tempDir("dak-jre-home-");
  fs.mkdirSync(path.join(home, "bin"), { recursive: true });
  const javaBin = path.join(home, "bin", "java");
  fs.writeFileSync(javaBin, "#!/bin/sh\n");
  fs.chmodSync(javaBin, 0o755);

  const resolved = await resolveJava({
    env: { JAVA_HOME: home },
    probe: (cmd) => (cmd === javaBin ? { status: 0, stderr: 'openjdk version "17.0.9"', stdout: "" } : { status: 1, stderr: "", stdout: "" }),
    download: () => assert.fail("must not download when JAVA_HOME is usable"),
  });

  assert.strictEqual(resolved, javaBin);
});

test("a cached provisioned JRE is reused without downloading again", async () => {
  const cache = tempDir("dak-jre-cache-");
  const manifest = require("../jre-manifest.json");
  const key = platformKey(process.platform, process.arch);
  if (!manifest.platforms[key]) return; // unsupported platform: nothing to cache

  const installDir = path.join(cache, `${manifest.release}-${key}`);
  const binDir = path.join(installDir, "bin");
  fs.mkdirSync(binDir, { recursive: true });
  const javaBin = path.join(binDir, process.platform === "win32" ? "java.exe" : "java");
  fs.writeFileSync(javaBin, "#!/bin/sh\n");
  fs.chmodSync(javaBin, 0o755);

  const resolved = await resolveJava({
    env: { DROIDAGENT_JRE_CACHE_DIR: cache },
    probe: banner("", 127), // no java anywhere on this machine
    download: () => assert.fail("a cached JRE must not be re-downloaded"),
  });

  assert.strictEqual(resolved, javaBin);
});

test("an unsupported platform fails with a clear message instead of downloading garbage", async () => {
  await assert.rejects(
    () =>
      resolveJava({
        env: {},
        probe: banner("", 127),
        download: () => assert.fail("must not download"),
        platform: "sunos",
        arch: "sparc",
      }),
    /sunos-sparc/,
  );
});

test("a JRE whose checksum does not match is refused and leaves nothing behind", async () => {
  // The provisioning path executes what it downloads, so this is the security-critical property:
  // a mismatch must abort before extraction and must not leave a half-installed runtime that a
  // later run would treat as a valid cache hit.
  const http = require("node:http");
  const payload = Buffer.from("this is not a JRE");
  const server = http.createServer((_req, res) => res.end(payload));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;

  const cache = tempDir("dak-jre-badsum-");
  const installDir = path.join(cache, "fake-release-test-platform");
  const manifest = {
    release: "fake-release",
    platforms: {
      "test-platform": {
        url: `http://127.0.0.1:${port}/jre.tar.gz`,
        sha256: "0".repeat(64), // deliberately wrong
        archive: "tar.gz",
      },
    },
  };

  try {
    const { provision } = require("../jre.js");
    await assert.rejects(
      () => provision(installDir, { platform: "test", arch: "platform", manifest }),
      /checksum mismatch/,
    );
    assert.ok(!fs.existsSync(installDir), "must not leave an install directory behind");
    const leftovers = fs.readdirSync(cache);
    assert.deepStrictEqual(leftovers, [], `must not leave temp files behind, found: ${leftovers}`);
  } finally {
    server.close();
  }
});
