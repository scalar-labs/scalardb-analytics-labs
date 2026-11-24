#!/usr/bin/env bash
# Copyright Scalar, Inc.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "usage: $0 <build-directory> <extension-version> <duckdb-version> <cache-directory>" >&2
  exit 2
fi

readonly SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly MODULE_DIRECTORY="$(cd "$SCRIPT_DIRECTORY/../.." && pwd)"
readonly BUILD_DIRECTORY="$1"
readonly EXTENSION_VERSION="$2"
readonly DUCKDB_VERSION="v$3"
readonly CACHE_DIRECTORY="$4"
readonly RELEASE_BASE_URL="https://github.com/duckdb/duckdb/releases/download/$DUCKDB_VERSION"
readonly VENDOR_DIRECTORY="$BUILD_DIRECTORY/vendor"
readonly SOURCE_DIRECTORY="$VENDOR_DIRECTORY/libduckdb-src"
readonly OUTPUT_DIRECTORY="$BUILD_DIRECTORY/output"
readonly EXTENSION_NAME="scalardb_analytics"
readonly EXTENSION_FILE="$OUTPUT_DIRECTORY/$EXTENSION_NAME.duckdb_extension"

# shellcheck source=common.sh
source "$SCRIPT_DIRECTORY/common.sh"

# Checksums are pinned per DuckDB release, so changing the version catalog without pinning the new
# release's artifacts fails here instead of building against unverified inputs.
case "$DUCKDB_VERSION" in
  v1.5.6)
    readonly DUCKDB_SOURCE_SHA256="0e358481ffb1188a8eb48a844cdcefae130ffb520f467143f8b8c14972e39518"
    case "$DUCKDB_PLATFORM" in
      osx_arm64)
        readonly STATIC_LIBRARIES_SHA256="e77c382fba15e3f5c0b3265d12e3a4de534699fdb91bcc9d10d4a857a58103d5"
        ;;
      linux_arm64)
        readonly STATIC_LIBRARIES_SHA256="56249751117a4c04289764e06eb431352ce33fb3f8e2f30401122d4d0e184139"
        ;;
      linux_amd64)
        readonly STATIC_LIBRARIES_SHA256="ab3d1d33951b8cb8bdcae740d8e06adf66bfb7ee880a3f7052d7a35088e16d2d"
        ;;
    esac
    ;;
  *)
    fail "no pinned DuckDB $DUCKDB_VERSION artifacts in $0; pin its source and static library" \
      "checksums before changing the DuckDB version"
    ;;
esac

case "$DUCKDB_PLATFORM" in
  osx_arm64) readonly ARCHITECTURE_FLAGS=("-arch" "arm64") ;;
  *) readonly ARCHITECTURE_FLAGS=() ;;
esac
readonly PLATFORM_ASSET="${DUCKDB_PLATFORM/_/-}"
readonly STATIC_DIRECTORY="$VENDOR_DIRECTORY/static-libs-$PLATFORM_ASSET"

append_field() {
  local value="$1"
  local length="${#value}"
  if [ "$length" -gt 32 ]; then
    echo "extension metadata field is longer than 32 bytes: $value" >&2
    exit 1
  fi
  printf '%s' "$value"
  dd if=/dev/zero bs=1 count="$((32 - length))" 2>/dev/null
}

mkdir -p "$VENDOR_DIRECTORY" "$OUTPUT_DIRECTORY"

SOURCE_ARCHIVE="$(cached_download "$RELEASE_BASE_URL/libduckdb-src.zip" \
  "$DUCKDB_SOURCE_SHA256" "$CACHE_DIRECTORY")"
STATIC_ARCHIVE="$(cached_download "$RELEASE_BASE_URL/static-libs-$PLATFORM_ASSET.zip" \
  "$STATIC_LIBRARIES_SHA256" "$CACHE_DIRECTORY")"

# The vendor directories are recreated from the verified archives so that a stale extraction of
# another release is never compiled against.
rm -rf "$SOURCE_DIRECTORY" "$STATIC_DIRECTORY"
mkdir -p "$SOURCE_DIRECTORY" "$STATIC_DIRECTORY"
unzip -oq "$SOURCE_ARCHIVE" -d "$SOURCE_DIRECTORY"
unzip -oq "$STATIC_ARCHIVE" -d "$STATIC_DIRECTORY"

readonly CPP_DIRECTORY="$MODULE_DIRECTORY/src/main/cpp"
# The host ABI is the only interface besides the DuckDB entry point that the extension exports.
readonly EXPORTED_SYMBOLS=(
  "${EXTENSION_NAME}_duckdb_cpp_init"
  "scalardb_analytics_register_host"
  "scalardb_analytics_unregister_host"
)

OBJECT_FILES=()
for source in "$CPP_DIRECTORY"/*.cpp; do
  object_file="$OUTPUT_DIRECTORY/$(basename "${source%.cpp}").o"
  clang++ -std=c++17 -O2 -DNDEBUG "${ARCHITECTURE_FLAGS[@]}" -fPIC -fvisibility=hidden \
    -DDUCKDB_BUILD_LIBRARY -DDUCKDB_BUILD_LOADABLE_EXTENSION \
    -I"$SOURCE_DIRECTORY" -I"$CPP_DIRECTORY/include" \
    -c "$source" \
    -o "$object_file"
  OBJECT_FILES+=("$object_file")
done

if [ "$(uname -s)" = "Darwin" ]; then
  EXPORT_FLAGS=()
  for symbol in "${EXPORTED_SYMBOLS[@]}"; do
    EXPORT_FLAGS+=("-Wl,-exported_symbol,_$symbol")
  done
  clang++ -dynamiclib "${ARCHITECTURE_FLAGS[@]}" \
    -install_name "@rpath/$EXTENSION_NAME.duckdb_extension" \
    -Wl,-dead_strip "${EXPORT_FLAGS[@]}" \
    "${OBJECT_FILES[@]}" "$STATIC_DIRECTORY/libduckdb_static.a" \
    -o "$EXTENSION_FILE"
else
  # Exporting only the extension's own symbols keeps the statically linked DuckDB private, as on
  # macOS, so it cannot interpose on the host's DuckDB. Unlike the macOS linker, which drops
  # unreachable functions one by one, section garbage collection cannot drop unreachable DuckDB
  # code that shares a section with reachable code, and that code references the built-in
  # extension loader and the libraries the core links. The linker therefore takes every archive of
  # the release's static libraries as one group, and any symbol still unresolved fails the link
  # instead of the LOAD.
  VERSION_SCRIPT="$OUTPUT_DIRECTORY/exports.map"
  {
    printf '{\n  global:\n'
    for symbol in "${EXPORTED_SYMBOLS[@]}"; do
      printf '    %s;\n' "$symbol"
    done
    printf '  local: *;\n};\n'
  } > "$VERSION_SCRIPT"
  clang++ -shared -Wl,--gc-sections -Wl,--version-script="$VERSION_SCRIPT" -Wl,--no-undefined \
    "${OBJECT_FILES[@]}" \
    -Wl,--start-group "$STATIC_DIRECTORY"/*.a -Wl,--end-group -ldl -pthread \
    -o "$EXTENSION_FILE"
fi

# DuckDB loadable extensions end with a metadata block followed by an empty
# signature. The field order is reversed by DuckDB's extension format.
{
  printf '\000\223\004\020duckdb_signature\200\004'
  append_field ""
  append_field ""
  append_field ""
  append_field "CPP"
  append_field "$EXTENSION_VERSION"
  append_field "$DUCKDB_VERSION"
  append_field "$DUCKDB_PLATFORM"
  append_field "4"
  dd if=/dev/zero bs=256 count=1 2>/dev/null
} >> "$EXTENSION_FILE"

HOST_ABI_VERSION="$(sed -n 's/^#define SCALARDB_ANALYTICS_HOST_ABI_VERSION \([0-9][0-9]*\)u$/\1/p' \
  "$CPP_DIRECTORY/include/scalardb_analytics_host.h")"
if [ -z "$HOST_ABI_VERSION" ]; then
  echo "could not read the host ABI version from scalardb_analytics_host.h" >&2
  exit 1
fi

# Record what the extension was built from so the distribution can report its provenance.
cat > "$OUTPUT_DIRECTORY/$EXTENSION_NAME.properties" <<EOF
extension=$EXTENSION_NAME
extensionVersion=$EXTENSION_VERSION
extensionAbi=CPP
hostAbiVersion=$HOST_ABI_VERSION
platform=$DUCKDB_PLATFORM
duckdbVersion=$DUCKDB_VERSION
duckdbSource=$RELEASE_BASE_URL/libduckdb-src.zip
duckdbSourceSha256=$DUCKDB_SOURCE_SHA256
duckdbStaticLibraries=$RELEASE_BASE_URL/static-libs-$PLATFORM_ASSET.zip
duckdbStaticLibrariesSha256=$STATIC_LIBRARIES_SHA256
duckdbLicense=MIT
EOF

printf '%s\n' "$EXTENSION_FILE"
