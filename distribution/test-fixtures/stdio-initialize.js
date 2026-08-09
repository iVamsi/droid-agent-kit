#!/usr/bin/env node
// Drives one MCP `initialize` round-trip over stdio against the launcher and exits.
//
// This exists because the shell version needed `timeout` to keep the server's stdio loop from
// hanging the script forever, and `timeout` is not present on macOS. That made the single most
// valuable assertion in the smoke test skip itself on an entire platform. Node is already
// required to run the launcher at all, so using it as the hang guard removes the dependency
// rather than documenting it.
//
// Usage: node stdio-initialize.js <launcher.js> [timeoutMs]
// Env:   DROIDAGENT_BIN -- passed through to the launcher.
// Exit:  0 and prints the response line on success; 1 with a reason otherwise.

const { spawn } = require("node:child_process");

const launcher = process.argv[2];
const timeoutMs = Number(process.argv[3] || 30000);

if (!launcher) {
  console.error("usage: stdio-initialize.js <launcher.js> [timeoutMs]");
  process.exit(2);
}

const request = {
  jsonrpc: "2.0",
  id: 1,
  method: "initialize",
  params: {
    protocolVersion: "2025-11-25",
    capabilities: {},
    clientInfo: { name: "smoke", version: "0" },
  },
};

const child = spawn(process.execPath, [launcher], {
  stdio: ["pipe", "pipe", "pipe"],
  env: process.env,
});

let stdout = "";
let stderr = "";
let settled = false;

// The child keeps the stdio loop open after answering, so this never resolves by the child
// exiting -- it resolves when a well-formed response to id 1 shows up, or when time runs out.
const finish = (code, message) => {
  if (settled) return;
  settled = true;
  clearTimeout(timer);
  child.kill("SIGKILL");
  if (message) {
    if (code === 0) console.log(message);
    else console.error(message);
  }
  process.exit(code);
};

const timer = setTimeout(() => {
  finish(1, `no initialize response within ${timeoutMs}ms\nstdout: ${stdout}\nstderr: ${stderr}`);
}, timeoutMs);

child.stdout.on("data", (chunk) => {
  stdout += chunk.toString();
  let newline;
  while ((newline = stdout.indexOf("\n")) !== -1) {
    const line = stdout.slice(0, newline).trim();
    stdout = stdout.slice(newline + 1);
    if (!line) continue;
    let parsed;
    try {
      parsed = JSON.parse(line);
    } catch {
      continue; // not a JSON-RPC frame; the server may log around them
    }
    if (parsed.id !== 1) continue;
    if (parsed.error) {
      finish(1, `initialize returned an error: ${JSON.stringify(parsed.error)}`);
      return;
    }
    const result = parsed.result;
    if (!result || !result.serverInfo || !result.capabilities || !result.protocolVersion) {
      finish(1, `initialize result missing serverInfo/capabilities/protocolVersion: ${line}`);
      return;
    }
    finish(0, `initialize answered: ${result.serverInfo.name} ${result.serverInfo.version} (protocol ${result.protocolVersion})`);
    return;
  }
});

child.stderr.on("data", (chunk) => {
  stderr += chunk.toString();
});

child.on("error", (error) => finish(1, `failed to spawn launcher: ${error.message}`));
child.on("exit", (code) => {
  // Only a failure if it died before answering.
  finish(1, `launcher exited (code ${code}) before answering initialize\nstderr: ${stderr}`);
});

child.stdin.write(JSON.stringify(request) + "\n");
