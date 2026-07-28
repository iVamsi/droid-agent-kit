"use strict";
/*
 * Stands in for GitHub Releases in smoke-test.sh, so the npm launcher's auto-fetch/checksum/
 * cache logic can be exercised without a real network call or a real published release.
 * Usage: node mock-release-server.js <good|badchecksum|404>
 * Prints MOCK_PORT=<port> to stdout once listening.
 */
const http = require("node:http");
const crypto = require("node:crypto");

const mode = process.argv[2] || "good";
const fakeJar = Buffer.from("droidagentkit smoke-test placeholder jar bytes\n".repeat(200));
const goodHash = crypto.createHash("sha256").update(fakeJar).digest("hex");
const wrongHash = "0".repeat(64);

const server = http.createServer((req, res) => {
  process.stderr.write(`REQUEST ${req.url}\n`);
  if (req.url.endsWith(".sha256")) {
    const hash = mode === "badchecksum" ? wrongHash : goodHash;
    res.end(`${hash}  droidagent-cli-test.jar\n`);
    return;
  }
  if (mode === "404") {
    res.statusCode = 404;
    res.end("not found");
    return;
  }
  res.end(fakeJar);
});

server.listen(0, "127.0.0.1", () => {
  process.stdout.write(`MOCK_PORT=${server.address().port}\n`);
});
