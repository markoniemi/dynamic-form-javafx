#!/usr/bin/env bash
# Installs the SnapFX release jar into the local Maven repository.
# SnapFX is not published to Maven Central, so this has to run once per machine
# (and again after a version bump or a cleaned ~/.m2) before the client builds.
set -euo pipefail

VERSION="${1:-0.8.0}"
JAR="snapfx-core-${VERSION}-0.jar"
URL="https://github.com/Beowolve/SnapFX/releases/download/v${VERSION}/${JAR}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Downloading $JAR"
curl -fsSL -o "$WORK/$JAR" "$URL"
curl -fsSL -o "$WORK/$JAR.sha256" "$URL.sha256"
(cd "$WORK" && sha256sum -c "$JAR.sha256")

mvn -q org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  -Dfile="$WORK/$JAR" \
  -DgroupId=org.snapfx \
  -DartifactId=snapfx-core \
  -Dversion="$VERSION" \
  -Dpackaging=jar

echo "Installed org.snapfx:snapfx-core:$VERSION"
