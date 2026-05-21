## Highlights

- **Fix iOS 17+ "Undeclared permissions" install error** — `PrivacyInfo.xcprivacy` was prepared in `iosApp/AppStore/` for the future App Store submission but never wired into the actual app bundle, so XcodeGen didn't include it in the IPA. iOS 26 / recent AltStore versions reject install of an app that declares Bluetooth + Camera usage descriptions without a matching Privacy Manifest. Moved the file into `iosApp/iosApp/` (the path XcodeGen sweeps for resources). CI validation now asserts `PrivacyInfo.xcprivacy` is present in the .app bundle on every release so it can't regress.
- **No Android counterpart** — Privacy Manifest is an Apple-only file; Android `1.2.12` already shipped the `"epr"` repair from the previous cut and doesn't need a re-release.
