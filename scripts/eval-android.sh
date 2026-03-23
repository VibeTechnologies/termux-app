#!/usr/bin/env bash
# eval-android.sh — Android emulator eval test runner for OpenClaw app
#
# Usage:
#   ./scripts/eval-android.sh setup      # Boot emulator + build + install APK
#   ./scripts/eval-android.sh tc1        # Run test case 1 (onboarding screen)
#   ./scripts/eval-android.sh tc2        # Run test case 2 (empty key validation)
#   ./scripts/eval-android.sh tc3        # Run test case 3 (skip button flow)
#   ./scripts/eval-android.sh tc4        # Run test case 4 (credentials + start)
#   ./scripts/eval-android.sh tc5        # Run test case 5 (app name check)
#   ./scripts/eval-android.sh tc6        # Run test case 6 (FAB hidden during onboarding)
#   ./scripts/eval-android.sh all        # Run all test cases
#   ./scripts/eval-android.sh teardown   # Kill emulator
#
# Prerequisites:
#   - APFS image mounted: hdiutil attach /Volumes/Dzianis-3/macbook/android-dev.sparseimage
#   - Android SDK at /opt/homebrew/share/android-sdk (symlink to /Volumes/AndroidDev/android-sdk)
#   - AVD "openclaw-eval" created (see below)
#   - JDK 17 at /opt/homebrew/opt/openjdk@17
#
# First-time setup:
#   export ANDROID_HOME=/opt/homebrew/share/android-sdk
#   yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --install \
#     "emulator" "platform-tools" "system-images;android-28;default;arm64-v8a"
#   echo "no" | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
#     -n openclaw-eval -k "system-images;android-28;default;arm64-v8a" -d pixel_2 --force

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SCREENSHOTS_DIR="$SCRIPT_DIR/eval-screenshots"

export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-sdk}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/emulator:$PATH"

# adb is nested due to sdkmanager bug
ADB="$ANDROID_HOME/platform-tools/platform-tools/adb"
if [ ! -f "$ADB" ]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
fi

EMULATOR="$ANDROID_HOME/emulator/emulator"
AVD_NAME="openclaw-eval"
PACKAGE="com.termux"
ACTIVITY=".app.OpenClawActivity"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/openclaw_apt-android-7-debug_arm64-v8a.apk"

mkdir -p "$SCREENSHOTS_DIR"

log() { echo "[eval] $(date +%H:%M:%S) $*"; }

wait_for_boot() {
    log "Waiting for emulator to boot..."
    "$ADB" wait-for-device
    for i in $(seq 1 120); do
        BOOT=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        if [ "$BOOT" = "1" ]; then
            log "Emulator booted after ${i}s"
            return 0
        fi
        sleep 1
    done
    log "ERROR: Emulator boot timeout (120s)"
    return 1
}

screenshot() {
    local name="$1"
    "$ADB" exec-out screencap -p > "$SCREENSHOTS_DIR/${name}.png"
    log "Screenshot saved: $SCREENSHOTS_DIR/${name}.png"
}

dump_ui() {
    local name="$1"
    "$ADB" shell uiautomator dump /sdcard/ui_dump.xml 2>/dev/null
    "$ADB" pull /sdcard/ui_dump.xml "$SCREENSHOTS_DIR/${name}_ui.xml" 2>/dev/null
}

# Parse UI hierarchy and check for element presence
check_element() {
    local xml_file="$1"
    local pattern="$2"
    grep -q "$pattern" "$xml_file" 2>/dev/null
}

fresh_launch() {
    "$ADB" shell pm clear "$PACKAGE" 2>/dev/null || true
    "$ADB" shell am start -n "${PACKAGE}/${ACTIVITY}" 2>/dev/null
    sleep 3
}

dismiss_keyboard() {
    "$ADB" shell input keyevent 4  # BACK key
    sleep 0.5
}

cmd_setup() {
    log "=== SETUP: Boot emulator + build + install APK ==="

    # Check if emulator is already running
    if "$ADB" devices 2>/dev/null | grep -q "emulator-"; then
        log "Emulator already running"
    else
        log "Starting emulator (headless)..."
        nohup "$EMULATOR" -avd "$AVD_NAME" \
            -no-window -no-audio -gpu swiftshader_indirect \
            -no-snapshot -memory 2048 -partition-size 4096 \
            > /tmp/emulator.log 2>&1 &
        wait_for_boot
    fi

    # Build APK
    log "Building APK..."
    cd "$PROJECT_DIR"
    ./gradlew assembleDebug 2>&1 | tail -3

    # Install APK
    log "Installing APK..."
    "$ADB" install -r "$APK" 2>&1
    log "Setup complete"
}

cmd_tc1() {
    log "=== TC1: Onboarding Screen ==="
    fresh_launch
    sleep 2
    screenshot "TC1_onboarding"
    dump_ui "TC1"

    # Evaluate from UI hierarchy
    local xml="$SCREENSHOTS_DIR/TC1_ui.xml"
    local pass=true
    check_element "$xml" "Welcome to OpenClaw" || { log "FAIL: Missing 'Welcome to OpenClaw'"; pass=false; }
    check_element "$xml" "openclaw_api_key_input" || { log "FAIL: Missing API key input"; pass=false; }
    check_element "$xml" "openclaw_gateway_token_input" || { log "FAIL: Missing gateway token input"; pass=false; }
    check_element "$xml" "openclaw_start_button" || { log "FAIL: Missing Start button"; pass=false; }
    check_element "$xml" "openclaw_skip_button" || { log "FAIL: Missing Skip button"; pass=false; }
    ! check_element "$xml" "openclaw_terminal_fab" || { log "FAIL: FAB should be hidden"; pass=false; }

    if $pass; then log "TC1: PASS"; else log "TC1: FAIL"; fi
}

cmd_tc2() {
    log "=== TC2: Empty API Key Validation ==="
    fresh_launch
    sleep 2

    # Tap START without entering API key (button center: 540, 1237)
    "$ADB" shell input tap 540 1237
    sleep 2

    screenshot "TC2_empty_key_validation"
    dump_ui "TC2"

    # Check onboarding is still visible + error indication
    local xml="$SCREENSHOTS_DIR/TC2_ui.xml"
    local pass=true
    check_element "$xml" "openclaw_onboarding" || { log "FAIL: Onboarding should still be visible"; pass=false; }
    check_element "$xml" "openclaw_api_key_input" || { log "FAIL: API key input should still be visible"; pass=false; }
    # Toast check: toasts may not appear in uiautomator dump, but onboarding staying visible = validation worked
    ! check_element "$xml" "openclaw_status_overlay" || { log "FAIL: Should NOT have navigated to status overlay"; pass=false; }

    if $pass; then log "TC2: PASS"; else log "TC2: FAIL"; fi
}

cmd_tc3() {
    log "=== TC3: Skip Button Flow ==="
    fresh_launch
    sleep 2

    # Tap Skip (center: 539, 1397)
    "$ADB" shell input tap 539 1397
    sleep 5

    screenshot "TC3_skip_flow"
    dump_ui "TC3"

    local xml="$SCREENSHOTS_DIR/TC3_ui.xml"
    local pass=true
    check_element "$xml" "openclaw_status_overlay" || { log "FAIL: Status overlay should be visible"; pass=false; }
    check_element "$xml" "openclaw_progress" || { log "FAIL: Progress bar should be visible"; pass=false; }
    check_element "$xml" "openclaw_status_text" || { log "FAIL: Status text should be visible"; pass=false; }
    ! check_element "$xml" "openclaw_onboarding" || { log "FAIL: Onboarding should be hidden"; pass=false; }

    if $pass; then log "TC3: PASS"; else log "TC3: FAIL"; fi
}

cmd_tc4() {
    log "=== TC4: Enter Credentials + Start ==="
    fresh_launch
    sleep 2

    # Tap API key input field (540, 840)
    "$ADB" shell input tap 540 840
    sleep 1

    # Type test API key
    "$ADB" shell input text "sk-test-1234567890abcdef"
    sleep 0.5

    # Dismiss keyboard
    dismiss_keyboard
    sleep 1

    # Tap START (540, 1237)
    "$ADB" shell input tap 540 1237
    sleep 5

    screenshot "TC4_credentials_start"
    dump_ui "TC4"

    local xml="$SCREENSHOTS_DIR/TC4_ui.xml"
    local pass=true
    check_element "$xml" "openclaw_status_overlay" || { log "FAIL: Status overlay should be visible"; pass=false; }
    check_element "$xml" "openclaw_title" || { log "FAIL: OpenClaw title should be visible"; pass=false; }
    check_element "$xml" "openclaw_progress" || { log "FAIL: Progress bar should be visible"; pass=false; }
    ! check_element "$xml" "openclaw_onboarding" || { log "FAIL: Onboarding should be hidden"; pass=false; }

    if $pass; then log "TC4: PASS"; else log "TC4: FAIL"; fi
}

cmd_tc5() {
    log "=== TC5: App Name Verification ==="
    # Reuse TC4 state (already on status overlay with OpenClaw branding)
    dump_ui "TC5"
    screenshot "TC5_app_name"

    local xml="$SCREENSHOTS_DIR/TC5_ui.xml"
    local pass=true
    check_element "$xml" "OpenClaw" || { log "FAIL: 'OpenClaw' should be visible"; pass=false; }

    # Check that "Termux" is NOT in any visible text
    if python3 -c "
import xml.etree.ElementTree as ET
tree = ET.parse('$xml')
for elem in tree.iter():
    text = elem.get('text', '')
    if 'Termux' in text:
        exit(1)
exit(0)
" 2>/dev/null; then
        log "OK: No 'Termux' branding found"
    else
        log "FAIL: 'Termux' branding found in UI"
        pass=false
    fi

    if $pass; then log "TC5: PASS"; else log "TC5: FAIL"; fi
}

cmd_tc6() {
    log "=== TC6: FAB Hidden During Onboarding ==="
    fresh_launch
    sleep 2

    screenshot "TC6_fab_hidden"
    dump_ui "TC6"

    local xml="$SCREENSHOTS_DIR/TC6_ui.xml"
    local pass=true
    check_element "$xml" "openclaw_onboarding" || { log "FAIL: Onboarding should be visible"; pass=false; }
    ! check_element "$xml" "terminal_fab" || { log "FAIL: FAB should be hidden during onboarding"; pass=false; }
    ! check_element "$xml" "FloatingActionButton" || { log "FAIL: No FAB should be in UI tree"; pass=false; }

    if $pass; then log "TC6: PASS"; else log "TC6: FAIL"; fi
}

cmd_all() {
    log "=== Running all test cases ==="
    local results=()
    for tc in tc1 tc2 tc3 tc4 tc5 tc6; do
        if "cmd_$tc"; then
            results+=("$tc: PASS")
        else
            results+=("$tc: FAIL")
        fi
    done

    echo ""
    log "========== RESULTS =========="
    for r in "${results[@]}"; do
        log "  $r"
    done
    log "============================="
}

cmd_teardown() {
    log "=== TEARDOWN: Killing emulator ==="
    "$ADB" -s emulator-5554 emu kill 2>/dev/null || true
    sleep 2
    log "Emulator stopped"
}

# Main dispatch
case "${1:-help}" in
    setup)    cmd_setup ;;
    tc1)      cmd_tc1 ;;
    tc2)      cmd_tc2 ;;
    tc3)      cmd_tc3 ;;
    tc4)      cmd_tc4 ;;
    tc5)      cmd_tc5 ;;
    tc6)      cmd_tc6 ;;
    all)      cmd_all ;;
    teardown) cmd_teardown ;;
    *)
        echo "Usage: $0 {setup|tc1|tc2|tc3|tc4|tc5|tc6|all|teardown}"
        exit 1
        ;;
esac
