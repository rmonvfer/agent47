#!/usr/bin/env bash
set -euo pipefail

REPO="rmonvfer/agent47"
INSTALL_DIR="${AGENT47_INSTALL_DIR:-$HOME/.local/bin}"

die() {
    echo "error: $1" >&2
    exit 1
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
    latest=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" | grep '"tag_name"' | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')

    if [ -z "$latest" ]; then
        die "failed to fetch latest version from GitHub"
    fi

    echo "$latest"
}

main() {
    local platform version url

    platform=$(detect_platform)
    version=$(get_version)
    url="https://github.com/${REPO}/releases/download/${version}/agent47-${platform}"

    echo "Installing agent47 ${version} for ${platform}..."

    mkdir -p "$INSTALL_DIR"

    if ! curl -fsSL -o "${INSTALL_DIR}/agent47" "$url"; then
        die "failed to download agent47 from ${url}"
    fi

    chmod +x "${INSTALL_DIR}/agent47"

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
