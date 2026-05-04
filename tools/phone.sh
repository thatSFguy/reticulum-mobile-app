#!/usr/bin/env bash
# Token-efficient phone debugging for the Reticulum mobile app.
#
# Wraps the common adb workflows so log captures are FILTERED to the
# ReticulumEngine tag and BOUNDED in size — instead of streaming
# multi-MB logcat dumps into Claude's context.
#
# Usage:
#   ./tools/phone.sh <subcommand> [args]
#
# Run with no args (or `help`) for a full subcommand list.

set -euo pipefail

ACTION="${1:-help}"
shift || true

# App-specific knobs. If you fork the package id, update PKG_ID.
PKG_ID="io.github.thatsfguy.reticulum.native"
ENGINE_TAG="ReticulumEngine"
SERVICE_TAG="ReticulumService"
APKS_DIR="apks"

die() { echo "phone.sh: $*" >&2; exit 1; }

require_adb() {
    command -v adb >/dev/null 2>&1 || die "adb not on PATH — install Android Platform Tools"
    adb devices | grep -q -P '\bdevice\b' || die "no phone in 'adb devices' — check USB cable + debug auth"
}

# Filter expression that grabs ONLY the engine + service tags. Everything
# else is silenced (`*:S`). Pass `-d` for dump-and-exit so we never stream.
LOGCAT_FILTER=( -s "$ENGINE_TAG:V" "$SERVICE_TAG:V" "*:S" )

case "$ACTION" in

    status)
        require_adb
        echo "── adb devices ──"
        adb devices -l
        echo "── installed app version ──"
        adb shell dumpsys package "$PKG_ID" 2>/dev/null \
            | grep -E '^\s*(versionName|firstInstallTime|lastUpdateTime)=' \
            | head -3 || echo "$PKG_ID not installed"
        ;;

    install)
        require_adb
        # Allow explicit path: `phone.sh install path/to/foo.apk`
        APK="${1:-}"
        if [ -z "$APK" ]; then
            APK="$(ls -t $APKS_DIR/*.apk 2>/dev/null | head -1)"
        fi
        [ -z "$APK" ] && die "no APK found — pass path explicitly or drop one in $APKS_DIR/"
        [ -f "$APK" ] || die "APK not found: $APK"
        echo "installing $APK (-r preserves app data)"
        adb install -r "$APK"
        ;;

    start)
        require_adb
        adb shell monkey -p "$PKG_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
        echo "started $PKG_ID"
        ;;

    stop)
        require_adb
        adb shell am force-stop "$PKG_ID"
        echo "stopped $PKG_ID"
        ;;

    restart)
        require_adb
        adb shell am force-stop "$PKG_ID"
        sleep 1
        adb shell monkey -p "$PKG_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
        echo "restarted $PKG_ID"
        ;;

    log-clear)
        require_adb
        adb logcat -c
        echo "logcat cleared"
        ;;

    log-grab)
        # Dump the last N lines of engine-tagged log, optionally grep'd.
        # Default 100 lines is enough for most "what just happened" checks
        # without blowing context.
        require_adb
        LINES="${1:-100}"
        PAT="${2:-}"
        if [ -n "$PAT" ]; then
            adb logcat -d "${LOGCAT_FILTER[@]}" | grep -E "$PAT" | tail -n "$LINES"
        else
            adb logcat -d "${LOGCAT_FILTER[@]}" | tail -n "$LINES"
        fi
        ;;

    log-around)
        # Clear logcat, hold for N seconds (user reproduces the bug),
        # then dump filtered + grep'd output. The single most useful
        # subcommand for "do this thing, what does the engine log say?".
        require_adb
        SECS="${1:-10}"
        PAT="${2:-}"
        adb logcat -c
        echo "capturing for ${SECS}s — perform the action now…"
        sleep "$SECS"
        echo "── captured ──"
        if [ -n "$PAT" ]; then
            adb logcat -d "${LOGCAT_FILTER[@]}" | grep -E "$PAT" || echo "(no matches)"
        else
            adb logcat -d "${LOGCAT_FILTER[@]}"
        fi
        ;;

    log-tail)
        # STREAM filtered log — only use when actively watching. Pipe to
        # head -N if you intend to capture this in a context window.
        require_adb
        adb logcat "${LOGCAT_FILTER[@]}"
        ;;

    screen)
        # Pull a PNG of the current screen.
        require_adb
        OUT="${1:-./phone-screen.png}"
        adb exec-out screencap -p > "$OUT"
        echo "saved $OUT ($(wc -c < "$OUT") bytes)"
        ;;

    tap)
        # Tap (x, y) — useful for scripted UI navigation.
        require_adb
        [ "$#" -eq 2 ] || die "usage: phone.sh tap <x> <y>"
        adb shell input tap "$1" "$2"
        ;;

    text)
        # Type text into the focused field.
        require_adb
        [ -n "${1:-}" ] || die "usage: phone.sh text <string>"
        adb shell input text "$(echo "$1" | sed 's/ /%s/g')"
        ;;

    key)
        # Send a keyevent by name (BACK, ENTER, etc.) or code.
        require_adb
        [ -n "${1:-}" ] || die "usage: phone.sh key <KEYNAME|code>"
        adb shell input keyevent "$1"
        ;;

    help|*)
        cat <<'HELP'
phone.sh — token-efficient phone debugging for the Reticulum app

LIFECYCLE
  status                          Connected device + installed app version
  install [path/to.apk]           Install latest from apks/ (or explicit path); -r preserves data
  start | stop | restart          Lifecycle the app

LOGS (always filter to ReticulumEngine + ReticulumService tags, always -d)
  log-clear                       Clear logcat buffer
  log-grab [N=100] [pattern]      Dump last N filtered lines, optional grep
  log-around [secs=10] [pattern]  Clear, wait while user reproduces, dump filtered + grep'd
  log-tail                        STREAM filtered log (use sparingly — pipe to head -N!)

UI / INPUT
  screen [path=./phone-screen.png]   PNG of current screen
  tap <x> <y>                        Tap a screen coordinate
  text "<string>"                    Type into focused field
  key <KEYNAME|code>                 Send keyevent (BACK, HOME, ENTER, …)

EXAMPLES
  ./tools/phone.sh status
  ./tools/phone.sh install
  ./tools/phone.sh log-around 15 'path\?|unverified|announce|RESPONSE|fetch'
  ./tools/phone.sh log-grab 50 'ERROR|FAILED|timeout'
  ./tools/phone.sh screen ./debug-1.png

NOTES
  - Logcat is FILTERED to ReticulumEngine + ReticulumService tags by default.
    Other tags are silenced — keeps captures small and on-topic.
  - log-around is the heaviest tool; log-grab is the cheapest. Reach for
    log-grab first; only use log-around when you need to bracket a specific
    user action in time.
  - On Windows: run via Git Bash so adb's hex output isn't mangled by cmd.
HELP
        ;;
esac
