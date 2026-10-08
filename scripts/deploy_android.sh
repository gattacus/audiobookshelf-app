#!/usr/bin/env bash
# Update the side-by-side fork in place; never uninstall or clear app data.
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
app_package=com.audiobookshelf.app.debug
apk="$repo_root/android/app/build/outputs/apk/debug/app-debug.apk"
device_serial=
build_mode=full

usage() {
  cat <<'EOF'
Usage: scripts/deploy_android.sh [--native-only | --skip-build] [adb-serial]

Default: generate web assets, sync Android, build debug APK, install and launch.
--native-only  Rebuild Android using already synchronized web assets.
--skip-build   Install the existing debug APK (may contain older changes).

Selects the only authorized physical device automatically. With multiple phones,
pass a serial from `adb devices -l`. USB and wireless ADB are supported.
Updates com.audiobookshelf.app.debug with `adb install -r`, retaining its data.
The Play Store app stays separate; its private data is not copied to the fork.
Node.js 20 is selected automatically when available; NODE20_HOME can specify it.
EOF
}
fail() { echo "Error: $*" >&2; exit 1; }
for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    --native-only|--skip-build)
      [[ $build_mode == full ]] || fail 'Choose only one build option.'
      build_mode=${arg#--} ;;
    -*) fail "Unknown option: $arg" ;;
    *) [[ -z $device_serial ]] || fail 'Pass only one device serial.'
       device_serial=$arg ;;
  esac
done

if [[ $build_mode == full ]]; then
  is_node20() {
    [[ -x $1 ]] && [[ $("$1" -p 'process.versions.node.split(".")[0]' 2>/dev/null) == 20 ]]
  }
  node20_bin=
  if [[ -n ${NODE20_HOME:-} ]]; then
    is_node20 "$NODE20_HOME/bin/node" || fail 'NODE20_HOME must point to a Node.js 20 installation containing bin/node.'
    node20_bin="$NODE20_HOME/bin"
  elif command -v node >/dev/null 2>&1 && is_node20 "$(command -v node)"; then
    node20_bin=$(dirname "$(command -v node)")
  else
    for candidate in \
      /opt/homebrew/opt/node@20/bin /usr/local/opt/node@20/bin \
      "${NVM_DIR:-$HOME/.nvm}"/versions/node/v20*/bin \
      "${FNM_DIR:-$HOME/.local/share/fnm}"/node-versions/v20*/installation/bin \
      "$HOME"/.volta/tools/image/node/20*/bin \
      "$HOME"/.local/share/mise/installs/node/20*/bin \
      "$HOME"/.npm/_npx/*/node_modules/node/bin; do
      if is_node20 "$candidate/node"; then
        node20_bin=$candidate
        break
      fi
    done
  fi
  [[ -n $node20_bin ]] || fail 'Node.js 20 not found. Install it or set NODE20_HOME to its installation directory.'
  export PATH="$node20_bin:$PATH"
  hash -r
  command -v npm >/dev/null 2>&1 && command -v npx >/dev/null 2>&1 || fail 'npm and npx are required for the web build.'
  echo "Using Node.js $(node --version) from $(command -v node)"
fi

# Local SDK configuration also works when platform-tools is absent from PATH.
sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $sdk_root && -f "$repo_root/android/local.properties" ]]; then
  sdk_root=$(sed -n 's/^sdk.dir=//p' "$repo_root/android/local.properties" | tr -d '\r')
fi
if command -v adb >/dev/null 2>&1; then
  adb_bin=$(command -v adb)
elif [[ -n $sdk_root && -x "$sdk_root/platform-tools/adb" ]]; then
  adb_bin="$sdk_root/platform-tools/adb"
else
  fail 'ADB not found. Add platform-tools to PATH or set ANDROID_HOME.'
fi
"$adb_bin" devices -l
device_list=$("$adb_bin" devices)
is_physical() {
  [[ $1 != emulator-* ]] || return 1
  local qemu
  qemu=$("$adb_bin" -s "$1" shell getprop ro.kernel.qemu | tr -d '\r') || return 1
  [[ $qemu != 1 ]]
}
if [[ -z $device_serial ]]; then
  candidates=()
  while read -r serial state rest; do
    if [[ $state == device ]] && is_physical "$serial"; then
      candidates+=("$serial")
    fi
  done <<< "$device_list"
  [[ ${#candidates[@]} -gt 0 ]] || fail 'No authorized physical device. Connect and authorize a phone.'
  [[ ${#candidates[@]} -eq 1 ]] || fail 'Multiple physical devices. Pass the intended adb serial.'
  device_serial=${candidates[0]}
fi
[[ $("$adb_bin" -s "$device_serial" get-state) == device ]] || fail 'Selected device is not authorized or connected.'
is_physical "$device_serial" || fail 'Selected device is an emulator or could not be inspected.'
device_adb() { "$adb_bin" -s "$device_serial" "$@"; }
echo "Deploying Audiobookshelf Fork to $device_serial"

before=$(device_adb shell dumpsys package "$app_package")
installed_paths=$(device_adb shell pm path "$app_package" | tr -d '\r')
was_installed=false
if [[ $installed_paths == *package:* ]]; then
  was_installed=true
  echo "$before" | awk '/versionCode=|versionName=|firstInstallTime=|lastUpdateTime=/'
else
  echo 'First fork installation: it starts with separate app data.'
fi
first_install_time() { sed -n 's/^[[:space:]]*firstInstallTime=//p' | tr -d '\r'; }
original_install_time=$(printf '%s\n' "$before" | first_install_time)
if $was_installed; then
  [[ -n $original_install_time ]] || fail 'Cannot read the existing installation timestamp.'
  if [[ $build_mode != skip-build && ! -f "$HOME/.android/debug.keystore" ]]; then
    fail 'Existing fork found but ~/.android/debug.keystore is missing. Restore the original signing key before building.'
  fi
fi

# Inspect the APK itself, not just a filename or Gradle applicationId.
aapt_bin=$(command -v aapt || true)
if [[ -z $aapt_bin && -n $sdk_root ]]; then
  for candidate in "$sdk_root"/build-tools/*/aapt; do
    [[ ! -x $candidate ]] || aapt_bin=$candidate
  done
fi
[[ -n $aapt_bin ]] || fail 'aapt not found. Install Android SDK build-tools and set ANDROID_HOME.'
cd "$repo_root"
if [[ $build_mode == full ]]; then
  if [[ ! -d node_modules ]]; then
    npm ci --omit=optional
  fi
  npm run generate
  npx --no-install cap sync android
  echo 'Tracked synchronization changes (review before committing):'
  git diff --stat -- android
fi
if [[ $build_mode != skip-build ]]; then
  ./android/gradlew assembleDebug -p android --no-daemon
fi
[[ -f $apk ]] || fail "Debug APK is missing: $apk"
badging=$("$aapt_bin" dump badging "$apk")
apk_package=$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ $apk_package == "$app_package" ]] || fail "Refusing APK with package '$apk_package'; expected $app_package."
printf '%s\n' "$badging" | awk '/^package:/'

# No downgrade, uninstall, data clear, or destructive fallback on any failure.
if ! device_adb install -r "$apk"; then
  fail 'In-place installation failed. No uninstall or data clear was attempted. For a signature mismatch, restore the original signing key.'
fi
after=$(device_adb shell dumpsys package "$app_package")
updated_install_time=$(printf '%s\n' "$after" | first_install_time)
[[ -n $updated_install_time ]] || fail 'Installation finished but package verification failed.'
if $was_installed; then
  [[ $updated_install_time == "$original_install_time" ]] || fail 'Installation timestamp changed unexpectedly; inspect the device.'
  echo 'Existing installation timestamp preserved.'
fi
echo "$after" | awk '/versionCode=|versionName=|firstInstallTime=|lastUpdateTime=/'
launch_result=$(device_adb shell am start -W -n "$app_package/com.audiobookshelf.app.MainActivity")
printf '%s\n' "$launch_result"
if [[ $launch_result == *Error:* || $launch_result == *Exception* ]]; then
  fail 'APK installed, but launching the fork failed.'
fi
echo 'Fork installed and launch requested. Verify playback/downloads on the phone.'
