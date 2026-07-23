#!/usr/bin/env node
/*
 * DroidAgentKit MCP launcher.
 *
 * Locates the `droidagent` JVM CLI (on PATH or via DROIDAGENT_BIN), then either:
 *   - prints version metadata and exits 0 when invoked with --version, or
 *   - spawns `droidagent serve-mcp --transport stdio --project auto` and pipes stdio.
 *
 * Node is an install-time shim only. The MCP server itself is pure JVM and has no Node
 * runtime dependency. The launcher fails closed on any error rather than silently
 * degrading the MCP connection.
 */
"use strict";

const { spawn } = require("node:child_process");
const { existsSync } = require("node:fs");

const VERSION = "0.1.0-alpha";

function resolveBin() {
  if (process.env.DROIDAGENT_BIN && existsSync(process.env.DROIDAGENT_BIN)) {
    return process.env.DROIDAGENT_BIN;
  }
  return "droidagent";
}

function main(argv) {
  if (argv.includes("--version") || argv.includes("-v")) {
    process.stdout.write(`droidagent-mcp launcher ${VERSION}\n`);
    return 0;
  }
  if (argv.includes("--help") || argv.includes("-h")) {
    process.stdout.write(
      "Usage: droidagent-mcp [--version | --help]\n" +
        "       (no args)  spawn `droidagent serve-mcp --transport stdio --project auto`\n" +
        "Env: DROIDAGENT_BIN  absolute path to the droidagent CLI\n"
    );
    return 0;
  }

  const bin = resolveBin();
  const child = spawn(
    bin,
    ["serve-mcp", "--transport", "stdio", "--project", "auto"],
    { stdio: "inherit" }
  );
  child.on("error", (err) => {
    process.stderr.write(`droidagent-mcp: failed to launch '${bin}': ${err.message}\n`);
    process.stderr.write("Set DROIDAGENT_BIN to the droidagent CLI, or ensure it is on PATH.\n");
    process.exit(127);
  });
  child.on("exit", (code, signal) => {
    if (signal) process.exit(1);
    process.exit(code ?? 1);
  });
  return 0;
}

process.exit(main(process.argv.slice(2)));
