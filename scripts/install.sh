#!/usr/bin/env bash
set -euo pipefail

REPO="${AGENT47_REPOSITORY:-rmonvfer/agent47}"
INSTALL_DIR="${AGENT47_INSTALL_DIR:-$HOME/.local/bin}"
DOWNLOAD_ROOT="https://github.com/${REPO}/releases/download"
TEMP_DIR=""
STAGED_PATH=""

die() {
    echo "error: $1" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

download() {
    curl --fail --silent --show-error --location --retry 3 --retry-delay 1 "$@"
}

cleanup() {
    [ -z "$STAGED_PATH" ] || rm -f "$STAGED_PATH"
    [ -z "$TEMP_DIR" ] || rm -rf "$TEMP_DIR"
}

detect_platform() {
    local os arch

    case "$(uname -s)" in
        Darwin) os="darwin" ;;
        Linux)  os="linux" ;;
        *)      die "unsupported OS: $(uname -s)" ;;
    esac

    case "$(uname -m)" in
        x86_64|amd64)  arch="x86_64" ;;
        arm64|aarch64) arch="arm64" ;;
        *)             die "unsupported architecture: $(uname -m)" ;;
    esac

    echo "${os}-${arch}"
}

get_version() {
    if [ -n "${AGENT47_VERSION:-}" ]; then
        echo "$AGENT47_VERSION"
        return
    fi

    local latest
    latest=$(download "https://api.github.com/repos/${REPO}/releases/latest" | sed -nE 's/.*"tag_name": *"([^"]+)".*/\1/p' | head -n 1)

    if [ -z "$latest" ]; then
        die "failed to fetch latest version from GitHub"
    fi

    echo "$latest"
}

verify_version() {
    [[ "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || die "invalid release version: $1"
}

checksum_file() {
    local file="$1"

    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{ print $1 }'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{ print $1 }'
    else
        die "sha256sum or shasum is required to verify the download"
    fi
}

main() {
    local platform version asset url checksums_url downloaded expected actual

    require_command curl
    require_command mktemp
    require_command install

    platform=$(detect_platform)
    version=$(get_version)
    verify_version "$version"
    asset="agent47-${platform}"
    url="${DOWNLOAD_ROOT}/${version}/${asset}"
    checksums_url="${DOWNLOAD_ROOT}/${version}/checksums-sha256.txt"
    TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/agent47-install.XXXXXXXX")
    trap cleanup EXIT
    downloaded="${TEMP_DIR}/${asset}"

    echo "Installing agent47 ${version} for ${platform}..."

    download --output "$downloaded" "$url" || die "failed to download agent47 from ${url}"
    download --output "${TEMP_DIR}/checksums-sha256.txt" "$checksums_url" || die "failed to download release checksums"

    expected=$(awk -v asset="$asset" '$2 == asset || $2 == "*" asset { print $1 }' "${TEMP_DIR}/checksums-sha256.txt")
    [ -n "$expected" ] || die "release checksums do not contain ${asset}"
    [[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] || die "invalid SHA-256 checksum for ${asset}"

    actual=$(checksum_file "$downloaded")
    [ "$actual" = "$expected" ] || die "checksum verification failed for ${asset}"

    mkdir -p "$INSTALL_DIR"
    STAGED_PATH="${INSTALL_DIR}/.agent47.install.$$"
    install -m 0755 "$downloaded" "$STAGED_PATH"
    mv -f "$STAGED_PATH" "${INSTALL_DIR}/agent47"
    STAGED_PATH=""

    echo "Installed agent47 to ${INSTALL_DIR}/agent47"

    if ! echo "$PATH" | tr ':' '\n' | grep -qx "$INSTALL_DIR"; then
        echo ""
        echo "WARNING: ${INSTALL_DIR} is not in your PATH."
        echo "Add it to your shell profile:"
        echo ""
        echo "  export PATH=\"${INSTALL_DIR}:\$PATH\""
        echo ""
    fi

    echo "Run 'agent47' to get started."
}

main
