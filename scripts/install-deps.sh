#!/usr/bin/env bash
# Installs what the build needs. Only Docker lives on the host; the JDK, Android SDK, Gradle and adb are
# baked into the toolchain image (docker/Dockerfile), which this builds.
set -euo pipefail
cd "$(dirname "$0")/.."

command -v docker >/dev/null || { echo "Docker is required: install Docker Engine (or Docker Desktop with WSL integration)" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker is installed but not reachable (daemon down, or user not in the docker group)" >&2; exit 1; }
./dev image
if grep -qi microsoft /proc/version 2>/dev/null && [ ! -d /dev/bus/usb ]; then
    echo "note: WSL without USB devices. To use a tablet over USB, in an admin PowerShell on Windows:"
    echo "  usbipd list; usbipd bind --busid <BUSID>; usbipd attach --wsl --busid <BUSID>"
fi
echo "toolchain ready (image $(source scripts/env.sh; echo "$IMAGE"))"
