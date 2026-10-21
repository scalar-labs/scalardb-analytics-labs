# Copyright Scalar, Inc.
# SPDX-License-Identifier: Apache-2.0
#
# Helpers shared by the scripts that build or acquire the packaged DuckDB extensions. Source this
# file; it defines functions and the DUCKDB_PLATFORM variable only.

readonly SUPPORTED_PLATFORMS="osx_arm64, linux_amd64, linux_arm64"

fail() {
  echo "$*" >&2
  exit 1
}

# Maps the build host to a DuckDB platform name. Extensions cannot be cross-compiled, so the host
# platform is the only platform a build produces.
case "$(uname -s)/$(uname -m)" in
  Darwin/arm64) readonly DUCKDB_PLATFORM="osx_arm64" ;;
  Linux/aarch64) readonly DUCKDB_PLATFORM="linux_arm64" ;;
  Linux/x86_64) readonly DUCKDB_PLATFORM="linux_amd64" ;;
  *)
    fail "unsupported build platform $(uname -s)/$(uname -m): the packaged DuckDB extensions" \
      "support only $SUPPORTED_PLATFORMS; build on one of those hosts"
    ;;
esac

sha256() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

# Prints the path of a verified copy of <url> in <cache-directory>, downloading it when absent.
# Cache entries are keyed by checksum, so a cached file is reused only for the exact pinned
# artifact. With DUCKDB_ARTIFACTS_OFFLINE=true a missing entry fails instead of being downloaded.
cached_download() {
  local url="$1"
  local expected_sha256="$2"
  local cache_directory="$3"
  local destination="$cache_directory/$expected_sha256-$(basename "$url")"

  if [ -f "$destination" ] && [ "$(sha256 "$destination")" = "$expected_sha256" ]; then
    printf '%s\n' "$destination"
    return
  fi
  if [ "${DUCKDB_ARTIFACTS_OFFLINE:-false}" = "true" ]; then
    fail "$url (sha256 $expected_sha256) is not in $cache_directory (or the cached copy fails its" \
      "checksum) and the build is offline; run the build once with network access to populate it"
  fi

  mkdir -p "$cache_directory"
  local temporary_file
  temporary_file="$(mktemp "$cache_directory/.download.XXXXXX")"
  if ! curl --fail --location --silent --show-error --output "$temporary_file" "$url"; then
    rm -f "$temporary_file"
    fail "could not download $url"
  fi
  local actual_sha256
  actual_sha256="$(sha256 "$temporary_file")"
  if [ "$actual_sha256" != "$expected_sha256" ]; then
    rm -f "$temporary_file"
    fail "checksum mismatch for $url: expected $expected_sha256, got $actual_sha256"
  fi
  mv "$temporary_file" "$destination"
  printf '%s\n' "$destination"
}

# Prints field <index> of a loadable extension's metadata footer. The footer is eight 32-byte
# fields followed by a 256-byte signature; DuckDB writes the fields in reverse order, so index 7
# is the magic value and indexes 3-6 are ABI, extension version, DuckDB version, and platform.
extension_footer_field() {
  tail -c 512 "$1" | dd bs=32 skip="$2" count=1 2>/dev/null | LC_ALL=C tr -d '\000'
}

extension_is_signed() {
  [ -n "$(tail -c 256 "$1" | LC_ALL=C tr -d '\000')" ]
}
