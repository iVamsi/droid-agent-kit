"use strict";

const test = require("node:test");
const assert = require("node:assert");

const { resolveInvocation } = require("../index.js");

const DEFAULT_SERVE = ["serve-mcp", "--transport", "stdio", "--project", "auto"];

test("no arguments keeps the historic serve-mcp default", () => {
  // Every existing MCP host config invokes the launcher bare. This must not change.
  assert.deepStrictEqual(resolveInvocation([]), { mode: "spawn", args: DEFAULT_SERVE });
});

test("--version and -h are launcher-local when they lead", () => {
  assert.deepStrictEqual(resolveInvocation(["--version"]), { mode: "version" });
  assert.deepStrictEqual(resolveInvocation(["-v"]), { mode: "version" });
  assert.deepStrictEqual(resolveInvocation(["--help"]), { mode: "help" });
  assert.deepStrictEqual(resolveInvocation(["-h"]), { mode: "help" });
});

test("a subcommand passes through verbatim", () => {
  assert.deepStrictEqual(resolveInvocation(["init", "--profile", "full"]), {
    mode: "spawn",
    args: ["init", "--profile", "full"],
  });
  assert.deepStrictEqual(resolveInvocation(["audit", "--project", "/tmp/app", "--write-agents"]), {
    mode: "spawn",
    args: ["audit", "--project", "/tmp/app", "--write-agents"],
  });
});

test("--help after a subcommand belongs to the CLI, not the launcher", () => {
  // `npx ... audit --help` should describe `audit`, not the launcher. This is why the flags are
  // only launcher-local in leading position rather than anywhere in argv.
  assert.deepStrictEqual(resolveInvocation(["audit", "--help"]), {
    mode: "spawn",
    args: ["audit", "--help"],
  });
  assert.deepStrictEqual(resolveInvocation(["inspect", "--version"]), {
    mode: "spawn",
    args: ["inspect", "--version"],
  });
});

test("an explicit serve-mcp is not merged with the defaults", () => {
  // Passing serve-mcp explicitly means the caller chose the flags; appending the stdio defaults
  // would silently override an intentional --transport http.
  assert.deepStrictEqual(resolveInvocation(["serve-mcp", "--transport", "http", "--port", "8765"]), {
    mode: "spawn",
    args: ["serve-mcp", "--transport", "http", "--port", "8765"],
  });
});
