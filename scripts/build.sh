#!/usr/bin/env bash
# Runs the JVM unit tests, then builds the debug APK (app/build/outputs/apk/debug/app-debug.apk).
set -euo pipefail
cd "$(dirname "$0")/.."
./dev test
./dev build
