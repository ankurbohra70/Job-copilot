#!/bin/sh
set -eu

wrapper_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.mvn/wrapper" && pwd)"
maven_home="$wrapper_dir/apache-maven-3.9.11"
archive="$wrapper_dir/apache-maven-3.9.11-bin.zip"
url="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip"

if [ ! -x "$maven_home/bin/mvn" ]; then
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o "$archive"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$url" -O "$archive"
  else
    echo "curl or wget is required to download Maven." >&2
    exit 1
  fi
  command -v unzip >/dev/null 2>&1 || {
    echo "unzip is required to install Maven." >&2
    exit 1
  }
  unzip -q -o "$archive" -d "$wrapper_dir"
  rm -f "$archive"
fi

exec "$maven_home/bin/mvn" "$@"
