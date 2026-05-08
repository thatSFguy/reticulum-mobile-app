#!/usr/bin/env bash
# Build ReticulumCrypto.swift as a static library for each iOS Kotlin/Native
# target. macOS-only — requires the Xcode Swift toolchain. Invoked by Gradle's
# buildIosCryptoBridge task before the Kotlin/Native link step that consumes
# `libReticulumCrypto.a` via cinterop.
#
# Outputs (one per Kotlin/Native target name; matches what shared/build.gradle.kts
# passes as -L on each target's binary):
#   shared/build/iosCryptoBridge/iosArm64/libReticulumCrypto.a
#   shared/build/iosCryptoBridge/iosSimulatorArm64/libReticulumCrypto.a
#   shared/build/iosCryptoBridge/iosX64/libReticulumCrypto.a

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHARED_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUT_BASE="${SHARED_DIR}/build/iosCryptoBridge"
SRC="${SCRIPT_DIR}/ReticulumCrypto.swift"

build_one() {
    local kotlin_target="$1"
    local triple="$2"
    local sdk="$3"
    local out="${OUT_BASE}/${kotlin_target}"

    mkdir -p "$out"
    # `-runtime-compatibility-version none` disables Swift's
    # autolink of `swiftCompatibility56` / `swiftCompatibilityPacks`.
    # Those shims back-port newer Swift runtime features to older OSes
    # but they're only delivered through the Xcode toolchain's lib
    # paths — Kotlin/Native's test-binary link step doesn't see them
    # and fails with "Undefined symbols __swift_FORCE_LOAD_$_swiftCompatibility56".
    # We target iOS 15+, all Swift 5 features are native, no shim needed.
    # Surfaced when `iosSimulatorArm64Test` was added in v1.0.3.
    xcrun -sdk "$sdk" swiftc \
        -emit-library -static \
        -target "$triple" \
        -runtime-compatibility-version none \
        -module-name ReticulumCrypto \
        -emit-module -emit-module-path "${out}/ReticulumCrypto.swiftmodule" \
        -o "${out}/libReticulumCrypto.a" \
        "${SRC}"
    echo "[iosCryptoBridge] built ${kotlin_target} → ${out}/libReticulumCrypto.a"
}

build_one iosArm64           arm64-apple-ios15.0           iphoneos
build_one iosSimulatorArm64  arm64-apple-ios15.0-simulator iphonesimulator
build_one iosX64             x86_64-apple-ios15.0-simulator iphonesimulator
