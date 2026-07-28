#!/usr/bin/env bash
# Emit a minimal CycloneDX 1.5 SBOM for the published CLI fat jar's declared runtime
# third-party dependencies. toolbox-core has zero third-party deps; the fat jar pulls in
# kotlinx-serialization-json and sqlite-jdbc via mcp-server / storage-inspector / network-core / cli.
set -euo pipefail
version="${1:?usage: generate-sbom.sh <version>}"
out="${2:?usage: generate-sbom.sh <version> <output-path>}"

cat >"$out" <<EOF
{
  "bomFormat": "CycloneDX",
  "specVersion": "1.5",
  "version": 1,
  "metadata": {
    "component": {
      "type": "application",
      "name": "droidagent-cli",
      "version": "$version",
      "bom-ref": "pkg:maven/com.droidagentkit/droidagent-cli@$version"
    }
  },
  "components": [
    {
      "type": "library",
      "name": "kotlinx-serialization-json",
      "version": "1.11.0",
      "bom-ref": "pkg:maven/org.jetbrains.kotlinx/kotlinx-serialization-json@1.11.0",
      "purl": "pkg:maven/org.jetbrains.kotlinx/kotlinx-serialization-json@1.11.0"
    },
    {
      "type": "library",
      "name": "sqlite-jdbc",
      "version": "3.53.2.0",
      "bom-ref": "pkg:maven/org.xerial/sqlite-jdbc@3.53.2.0",
      "purl": "pkg:maven/org.xerial/sqlite-jdbc@3.53.2.0"
    }
  ]
}
EOF
echo "Wrote CycloneDX SBOM to $out"
