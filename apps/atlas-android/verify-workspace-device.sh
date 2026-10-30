#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"
./gradlew connectedSecureDebugAndroidTest
verification_status=$?
mkdir -p app/build/device-evidence
adb logcat -d > app/build/device-evidence/logcat.txt
if ! adb pull /sdcard/Download/workspace-live-device.png app/build/device-evidence/workspace-live-device.png; then
  rm -f app/build/device-evidence/workspace-live-device.png
fi
if ! adb pull /sdcard/Download/workspace-isolated-device.png app/build/device-evidence/workspace-isolated-device.png; then
  rm -f app/build/device-evidence/workspace-isolated-device.png
fi
exit "$verification_status"
