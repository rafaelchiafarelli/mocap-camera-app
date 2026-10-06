#!/usr/bin/env bash
# Installs the APK on the connected tablet (camera permission granted) and starts it.
# Builds first if there is no APK. T=<serial|ip:port> picks the device when several are connected.
set -euo pipefail
cd "$(dirname "$0")/.."
./dev devices
./dev install
./dev launch "$@"
