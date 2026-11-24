#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
output=$(mktemp -d)
trap 'rm -rf "$output"' EXIT
kotlinc core/src/main/kotlin/dev/alixer/bafang/protocol/*.kt tools/ProtocolSmoke.kt -include-runtime -d "$output/smoke.jar"
java -jar "$output/smoke.jar"