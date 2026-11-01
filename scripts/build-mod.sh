#!/usr/bin/env bash
# Build the Aerion mod (mod/, one jar per Minecraft version) and copy the jars to where the
# launcher embeds them from (src-tauri/resources/aerion-ui-<version>.jar). Needs JDK 25: $JAVA_HOME, or the private one that
# `scripts/build-mod.sh setup` unpacks into ~/.cache/luncher-devroot (nothing system-wide).
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="$HOME/.cache/luncher-devroot"
GRADLE_VERSION=9.8.2
if [ "${1:-}" = setup ]; then
  mkdir -p "$BASE/jdk25" "$BASE/gradle"
  url=$(curl -sL "https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse" |
    python3 -c "import json,sys; p=json.load(sys.stdin)[0]['binary']['package']; print(p['link'], p['checksum'])")
  set -- $url
  curl -sL -o "$BASE/jdk25.tar.gz" "$1"
  echo "$2  $BASE/jdk25.tar.gz" | sha256sum -c
  tar -xzf "$BASE/jdk25.tar.gz" -C "$BASE/jdk25" --strip-components=1 && rm "$BASE/jdk25.tar.gz"
  curl -sL -o "$BASE/gradle.zip" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  echo "$(curl -sL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip.sha256")  $BASE/gradle.zip" | sha256sum -c
  python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$BASE/gradle.zip" "$BASE/gradle"
  rm "$BASE/gradle.zip"
  chmod +x "$BASE/gradle/gradle-$GRADLE_VERSION/bin/gradle"
  exit 0
fi
export JAVA_HOME="${JAVA_HOME:-$BASE/jdk25}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$BASE/gradle-home}"
GRADLE="$BASE/gradle/gradle-$GRADLE_VERSION/bin/gradle"
# Old version numbers would otherwise leave several jars to choose from.
rm -f mod/*/build/libs/*.jar
(cd mod && "$GRADLE" --no-daemon -q build)
mkdir -p src-tauri/resources
rm -f src-tauri/resources/aerion-ui*.jar
# One jar per Minecraft version (the remapped one for obfuscated versions).
for dir in mod/v*/; do
  mc=$(sed -n 's/^minecraft_version=//p' "$dir/gradle.properties")
  jar=$(ls "$dir"build/libs/aerion-ui-*[0-9].jar | grep -v -- '-sources' | head -1)
  cp "$jar" "src-tauri/resources/aerion-ui-$mc.jar"
  echo "mod built: src-tauri/resources/aerion-ui-$mc.jar ($(stat -c %s "$jar") bytes)"
done
