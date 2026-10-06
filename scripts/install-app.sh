#!/usr/bin/env bash
# Installs the APK on the connected tablet (permissions granted) and starts it, declaring the camera.
# Builds first if there is no APK. T=<serial|ip:port> picks the device when several are connected.
# usage: install-app.sh [width height fps kbps camera-id]   (default 1600 1200 30 20000 0; ids in /info)
set -euo pipefail
cd "$(dirname "$0")/.."
./dev devices
./dev install
./dev launch "$@"
