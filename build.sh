#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST="$ROOT/dist"
JAR_NAME="sshsqlite-driver-0.1.0-SNAPSHOT-all.jar"

cd "$ROOT"
./gradlew packageRelease

mkdir -p "$DIST"
cp "build/distributions/$JAR_NAME" "$DIST/$JAR_NAME"
cp "build/distributions/sshsqlite_SHA256SUMS" "$DIST/sshsqlite_SHA256SUMS"

printf 'Built %s\n' "$DIST/$JAR_NAME"
