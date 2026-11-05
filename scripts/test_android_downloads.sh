#!/usr/bin/env bash
# Run isolated download regressions without Gradle's target-app uninstall cleanup.
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <adb-serial>" >&2
  adb devices -l
  exit 2
fi
device_serial=$1
app_package=com.audiobookshelf.app.debug
test_package=com.audiobookshelf.app.debug.test
classes=com.audiobookshelf.app.managers.DownloadQueueRecoveryTest,com.audiobookshelf.app.managers.DownloadStorageFailureTest,com.audiobookshelf.app.media.OfflineProgressSyncDeviceTest,com.audiobookshelf.app.media.OfflineSyncNetworkDeviceTest,com.audiobookshelf.app.listening.ListeningStorageDeviceTest
adb devices -l
[[ $(adb -s "$device_serial" get-state) == device ]]
adb -s "$device_serial" shell dumpsys package "$app_package" | awk '/versionCode=|versionName=|firstInstallTime=|lastUpdateTime=/'
"$repo_root/android/gradlew" :app:assembleDebug :app:assembleDebugAndroidTest -p "$repo_root/android" --no-daemon
# -r preserves private data. Never uninstall or clear the target package.
adb -s "$device_serial" install -r "$repo_root/android/app/build/outputs/apk/debug/app-debug.apk"
adb -s "$device_serial" install -r "$repo_root/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
result_log=$(mktemp "${TMPDIR:-/tmp}/abs-download-tests.XXXXXX")
cleanup() {
  # This is only the disposable test runner, never the fork or Play Store app.
  adb -s "$device_serial" uninstall "$test_package" >/dev/null || true
  rm -f "$result_log"
}
trap cleanup EXIT
adb -s "$device_serial" shell am instrument -w -e class "$classes" "$test_package/androidx.test.runner.AndroidJUnitRunner" | tee "$result_log"
# am instrument may exit successfully even when assertions fail.
if ! rg -q '^OK \([0-9]+ tests?\)' "$result_log"; then
  echo "Download instrumentation tests failed; the fork remains installed." >&2
  exit 1
fi
