#!/usr/bin/env bash
# Copyright Scalar, Inc.
# SPDX-License-Identifier: Apache-2.0
#
# Acquires DuckDB's official signed PostgreSQL and MySQL scanner extensions for the build host and
# verifies them against pinned checksums and the pinned DuckDB release and scanner commits.

set -euo pipefail

if [ "$#" -ne 5 ]; then
  echo "usage: $0 <output-directory> <duckdb-version> <cache-directory>" \
    "<postgres-scanner-commit> <mysql-scanner-commit>" >&2
  exit 2
fi

readonly SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly OUTPUT_DIRECTORY="$1"
readonly DUCKDB_VERSION="v$2"
readonly CACHE_DIRECTORY="$3"
readonly POSTGRES_SCANNER_COMMIT="$4"
readonly MYSQL_SCANNER_COMMIT="$5"
readonly REPOSITORY_BASE_URL="https://extensions.duckdb.org/$DUCKDB_VERSION"

# shellcheck source=common.sh
source "$SCRIPT_DIRECTORY/common.sh"

# Pinned sha256 of the compressed artifacts DuckDB publishes for each release and platform. DuckDB
# publishes no checksum files, so these were recorded from the first verified download.
scanner_sha256() {
  case "$DUCKDB_VERSION/$DUCKDB_PLATFORM/$1" in
    v1.5.6/osx_arm64/postgres_scanner)
      echo "9d5f9f7b9873bfebd161b88675a5dbcc52355046dadbbc38f774db147a824240" ;;
    v1.5.6/osx_arm64/mysql_scanner)
      echo "e5cd32f2990048fe1cb966f341d96bc2ffe64e7053bf222a6f91053dbf2e4da5" ;;
    v1.5.6/linux_amd64/postgres_scanner)
      echo "f3720b2c67f770b1060f39d01c7b0127ef9135bc045e7f1dbb82de472664e544" ;;
    v1.5.6/linux_amd64/mysql_scanner)
      echo "98a6520486c48986507892c8c61c336264299aa646bee1c81600eeabd0cf0f64" ;;
    v1.5.6/linux_arm64/postgres_scanner)
      echo "381e87ff1bf82d8c41b24256168f8710ff7f5be936caa69a1ce2b4384073339f" ;;
    v1.5.6/linux_arm64/mysql_scanner)
      echo "5deac9d93b6e4054ce7813991f79deb3f010ddc2577c719ec5a6ec104efadeba" ;;
    *)
      fail "no pinned checksum for $1 of DuckDB $DUCKDB_VERSION on $DUCKDB_PLATFORM in $0;" \
        "pin it before changing the DuckDB version"
      ;;
  esac
}

# Third-party components statically linked into each scanner, as found in the published binaries.
# Versions come from the scanners' vcpkg ports and the vcpkg baseline of DuckDB's extension CI.
# Their license texts are packaged under src/main/resources/META-INF/licenses/.
scanner_bundled_licenses() {
  case "$1" in
    postgres_scanner) echo "libpq 18.3 (PostgreSQL), OpenSSL 3.6.0 (Apache-2.0)" ;;
    mysql_scanner)
      echo "MariaDB Connector/C 3.4.7 (LGPL-2.1-or-later), OpenSSL 3.6.0 (Apache-2.0)," \
        "zlib 1.3.1 (Zlib)" ;;
  esac
}

acquire() {
  local name="$1"
  local commit="$2"
  local url="$REPOSITORY_BASE_URL/$DUCKDB_PLATFORM/$name.duckdb_extension.gz"
  local expected_sha256
  expected_sha256="$(scanner_sha256 "$name")"
  local archive
  archive="$(cached_download "$url" "$expected_sha256" "$CACHE_DIRECTORY")"
  local extension_file="$OUTPUT_DIRECTORY/$name.duckdb_extension"

  gunzip -c "$archive" > "$extension_file"

  # DuckDB rejects a mismatched release or platform when loading, but checking here fails the
  # build instead of the deployed application, and also covers the scanner commit, which DuckDB
  # does not check.
  local abi version duckdb_version platform
  abi="$(extension_footer_field "$extension_file" 3)"
  version="$(extension_footer_field "$extension_file" 4)"
  duckdb_version="$(extension_footer_field "$extension_file" 5)"
  platform="$(extension_footer_field "$extension_file" 6)"
  [ "$abi" = "CPP" ] || fail "$name: expected the CPP extension ABI, found '$abi'"
  [ "$duckdb_version" = "$DUCKDB_VERSION" ] ||
    fail "$name: built for DuckDB '$duckdb_version', expected $DUCKDB_VERSION"
  [ "$platform" = "$DUCKDB_PLATFORM" ] ||
    fail "$name: built for platform '$platform', expected $DUCKDB_PLATFORM"
  [ -n "$version" ] && [ "${commit#"$version"}" != "$commit" ] ||
    fail "$name: built from scanner commit '$version', expected $commit"
  extension_is_signed "$extension_file" || fail "$name: the published extension is not signed"

  cat > "$OUTPUT_DIRECTORY/$name.properties" <<EOF
extension=$name
extensionVersion=$version
extensionAbi=$abi
platform=$DUCKDB_PLATFORM
duckdbVersion=$DUCKDB_VERSION
source=$url
sourceSha256=$expected_sha256
sha256=$(sha256 "$extension_file")
upstreamCommit=$commit
signed=true
license=MIT
bundledLicenses=$(scanner_bundled_licenses "$name")
EOF
}

# Start from an empty directory so that no extension from an earlier run is packaged.
rm -rf "$OUTPUT_DIRECTORY"
mkdir -p "$OUTPUT_DIRECTORY"
acquire postgres_scanner "$POSTGRES_SCANNER_COMMIT"
acquire mysql_scanner "$MYSQL_SCANNER_COMMIT"
