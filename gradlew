#!/usr/bin/env sh
set -eu
GRADLE_VERSION=8.8
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
BOOT="$ROOT/.gradle-bootstrap"
DIST="$BOOT/gradle-$GRADLE_VERSION"
ZIP="$BOOT/gradle-$GRADLE_VERSION-bin.zip"
if ! command -v java >/dev/null 2>&1; then
  echo "[SGC] ERROR: Java not found. Forge 1.20.1 requires Java 17." >&2
  exit 1
fi
if [ ! -x "$DIST/bin/gradle" ]; then
  echo "[SGC] Downloading Gradle $GRADLE_VERSION..."
  mkdir -p "$BOOT"
  if command -v curl >/dev/null 2>&1; then
    curl -L "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  else
    echo "[SGC] ERROR: curl or wget is required." >&2
    exit 1
  fi
  unzip -q -o "$ZIP" -d "$BOOT"
  rm -f "$ZIP"
fi
exec "$DIST/bin/gradle" "$@"
