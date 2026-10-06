# shellcheck shell=bash
# Shared settings for ./dev (sourced, not executed).

# Tagged with a hash of docker/, so a changed Dockerfile or lock file builds a fresh image.
IMAGE=mocap-camera-app:$(cat "$(dirname "${BASH_SOURCE[0]}")"/../docker/* | sha256sum | cut -c1-12)
ADB_CONTAINER=mocap-camera-app-adb
ADB_KEYS_VOLUME=mocap-camera-app-adb-keys
PKG=dev.mocapstudio.camera
APK=app/build/outputs/apk/debug/app-debug.apk
CONTRACTS=third_party/mocap-contracts/gen/java
