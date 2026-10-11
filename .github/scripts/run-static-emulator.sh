#!/usr/bin/env bash
set -euo pipefail

api=${1:?Expected API level followed by the test command}
shift
case "$api" in 24|35) ;; *) echo "Unsupported Android API: $api" >&2; exit 1 ;; esac
sdk=${ANDROID_HOME:?}
avds=${ANDROID_AVD_HOME:?}
diagnostics=app/build/reports/emulator-diagnostics
mkdir -p "$diagnostics" "$avds/ci.avd"
test -r /dev/kvm && test -w /dev/kvm || { echo 'KVM is not accessible.' >&2; exit 1; }
for resource in "$sdk/emulator/emulator" "$sdk/platform-tools/adb" \
  "$sdk/system-images/android-$api/google_apis/x86_64/system.img"; do
  test -f "$resource" || { echo "Missing emulator resource: $resource" >&2; exit 1; }
done
# API 24 limits size overrides relative to the physical panel. Allow wide reader
# tests, while retaining the original 320x640/160dpi test viewport after boot.
cat > "$avds/ci.ini" <<EOF
avd.ini.encoding=UTF-8
path=$avds/ci.avd
target=android-$api
EOF
cat > "$avds/ci.avd/config.ini" <<EOF
avd.ini.encoding=UTF-8
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2560
hw.lcd.width=1280
hw.lcd.height=720
hw.lcd.density=160
hw.mainKeys=yes
hw.keyboard=no
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
disk.dataPartition.size=6G
image.sysdir.1=system-images/android-$api/google_apis/x86_64/
tag.id=google_apis
target=android-$api
EOF
export ANDROID_SERIAL=emulator-5554
export PATH="$sdk/platform-tools:$sdk/emulator:$PATH"
emulator_pid=''
stop_emulator() {
  timeout 10s adb emu kill >> "$diagnostics/shutdown.txt" 2>&1 || true
  if [[ -n "$emulator_pid" ]]; then
    kill -KILL "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
    emulator_pid=''
  fi
  timeout 5s adb kill-server >> "$diagnostics/shutdown.txt" 2>&1 || true
}
cleanup() {
  status=$?
  trap - EXIT
  stop_emulator
  exit "$status"
}
trap cleanup EXIT
prepare_emulator() {
  echo 'Setting viewport: wm size 320x640'
  timeout 10s adb shell wm size 320x640 || return $?
  for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    echo "Setting $setting=0"
    timeout 10s adb shell settings put global "$setting" 0 || return $?
  done
  echo 'Unlocking emulator: input keyevent 82'
  timeout 10s adb shell input keyevent 82
}
booted=false
readiness_status=1
# Retry only infrastructure startup, before any app test has run. A fresh data
# partition prevents an interrupted first boot from poisoning the second attempt.
for attempt in 1 2; do
  if ! timeout 15s adb start-server > "$diagnostics/adb-start-$attempt.txt" 2>&1; then
    cat "$diagnostics/adb-start-$attempt.txt" >&2
    stop_emulator
    continue
  fi
  emulator -avd ci -port 5554 -accel on -no-window -gpu swiftshader_indirect \
    -no-snapshot -wipe-data -noaudio -no-boot-anim > "$diagnostics/emulator-$attempt.txt" 2>&1 &
  emulator_pid=$!
  deadline=$((SECONDS + 180))
  while ((SECONDS < deadline)); do
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
      echo "Emulator exited before Android boot completed (attempt $attempt)." >&2
      break
    fi
    if [[ "$(timeout 5s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == 1 ]] &&
        timeout 10s adb shell pm path android > "$diagnostics/package-manager-$attempt.txt" 2>&1 &&
        grep -q '^package:' "$diagnostics/package-manager-$attempt.txt"; then
      booted=true
      break
    fi
    sleep 2
  done
  if [[ "$booted" == true ]]; then
    # Android can report boot completion while window/settings services still time out.
    # Include preparation in the bounded startup retry, before invoking any app tests.
    if prepare_emulator > "$diagnostics/prepare-$attempt.txt" 2>&1; then
      break
    else
      readiness_status=$?
    fi
    cat "$diagnostics/prepare-$attempt.txt" >&2
    echo "Emulator preparation failed with exit $readiness_status (attempt $attempt)." >&2
    booted=false
  fi
  timeout 10s adb devices -l > "$diagnostics/devices-$attempt.txt" 2>&1 || true
  cat "$diagnostics/emulator-$attempt.txt" >&2
  stop_emulator
done
if [[ "$booted" != true ]]; then
  echo 'Android boot/ADB/preparation failed after two attempts with 180-second polling windows.' >&2
  exit "$readiness_status"
fi
"$@"
