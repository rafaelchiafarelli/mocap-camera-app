#!/usr/bin/env bash
# Installs the APK on the connected tablet (permissions granted) and starts it, declaring the camera.
# Builds first if there is no APK. T=<serial|ip:port> picks the device when several are connected.
# usage: install-app.sh [key=value ...]   (camera_id defaults to 0; keys as for ./dev launch)
set -euo pipefail
cd "$(dirname "$0")/.."
./dev devices
./dev install
args=("$@")
[[ " ${args[*]} " == *" camera_id="* ]] || args=(camera_id=0 "${args[@]}")
./dev launch "${args[@]}"
