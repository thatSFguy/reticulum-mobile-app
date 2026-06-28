#!/usr/bin/env bash
# Build the vendored libopus (third_party/opus, pinned submodule) as a
# per-target static library for each iOS Kotlin/Native target. macOS-only —
# requires Xcode + CMake (both present on the GitHub macos runners).
# Invoked by Gradle's buildIosOpus task before the Kotlin/Native link step
# that consumes libopus.a via the `opus` cinterop.
#
# CMake (>=3.14) has first-class iOS cross-compile support, which is far
# less error-prone than driving libopus's autotools by hand. We build one
# slice per Kotlin/Native target name, matching the -L paths that
# shared/build.gradle.kts sets on each target's binaries:
#   shared/build/iosOpus/iosArm64/libopus.a
#   shared/build/iosOpus/iosSimulatorArm64/libopus.a
#   shared/build/iosOpus/iosX64/libopus.a

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHARED_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${SHARED_DIR}/.." && pwd)"
OPUS_SRC="${REPO_DIR}/third_party/opus"
OUT_BASE="${SHARED_DIR}/build/iosOpus"

if [ ! -f "${OPUS_SRC}/CMakeLists.txt" ]; then
    echo "[iosOpus] ERROR: ${OPUS_SRC} is empty — run 'git submodule update --init'." >&2
    exit 1
fi

build_one() {
    local kotlin_target="$1"
    local arch="$2"
    local sysroot="$3"
    local cmake_build="${OUT_BASE}/${kotlin_target}/cmake"
    local out="${OUT_BASE}/${kotlin_target}"

    mkdir -p "$out"
    # Configure for this iOS slice. BUILD_SHARED_LIBS=OFF → static libopus.a.
    # Programs/tests/docs off to keep the build minimal. Unknown -D options
    # only warn (unused variable), they don't fail the configure.
    cmake -S "$OPUS_SRC" -B "$cmake_build" -G Xcode \
        -DCMAKE_SYSTEM_NAME=iOS \
        -DCMAKE_OSX_ARCHITECTURES="$arch" \
        -DCMAKE_OSX_SYSROOT="$sysroot" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
        -DBUILD_SHARED_LIBS=OFF \
        -DOPUS_BUILD_PROGRAMS=OFF \
        -DOPUS_BUILD_TESTING=OFF

    cmake --build "$cmake_build" --config Release --target opus

    # Locate the produced static library (Xcode generator nests it under a
    # Release-<sysroot> dir) and stage it where the Kotlin -L expects it.
    local lib
    lib="$(find "$cmake_build" -name 'libopus.a' -path '*Release*' 2>/dev/null | head -1)"
    [ -z "$lib" ] && lib="$(find "$cmake_build" -name 'libopus.a' 2>/dev/null | head -1)"
    if [ -z "$lib" ]; then
        echo "[iosOpus] ERROR: libopus.a not found after build for ${kotlin_target}" >&2
        exit 1
    fi
    cp "$lib" "${out}/libopus.a"
    echo "[iosOpus] built ${kotlin_target} → ${out}/libopus.a"
}

build_one iosArm64          arm64  iphoneos
build_one iosSimulatorArm64 arm64  iphonesimulator
build_one iosX64            x86_64 iphonesimulator
