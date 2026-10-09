# De'Longhi My Coffee Lounge 1.5.0: Arabic Android beta (unofficial)

## Current APK

- Branch: `delonghi-arabic-talkback-apk` (isolated from `main`)
- File: `releases/DeLonghi_MyCoffeeLounge_Arabic_ARM64_APK_Repaired_Experimental.apk`
- Current SHA-256: `0851210de6a15511eac1dc77c8d6a4ce64cde942c0b5b15a8ac86bcd7c3067d6`
- Official Android SDK `apksigner` verification: **v2 and v3 present** for minSdk24, and **v3 verifies** for minSdk36 (Android 16). Refer to `releases/DeLonghi_Android16_Signing_Verification.txt`.
- 45 native Android strings added in an `ar-SA` configuration in the Android resource table, including 'Close', 'Search', 'Copy Link' and other accessibility-related texts.
- The AndroidManifest already has `supportsRtl=true`.

## Important limitations

**This is not a comprehensive TalkBack/Flutter accessibility repair.**
Flutter routes, widget semantics, focus order and genuine RTL layout live inside compiled Dart AOT `libapp.so`, which we have NOT modified. The Flutter `en-US.json` strings were overwritten with Arabic in an earlier beta. This does **not** register the locale as `ar-SA`, nor does it reverse layout direction. Some server-rendered text and native dialog strings can stay English.

Signature verification is not a guarantee that Android PackageManager will install successfully. Installation, account login, the actual machine pairing and screen reader experience all remain untested on a real ARM64 Android 16 handset. OAuth/Firebase sign-in may depend on the manufacturer's certificate SHA-256. The modified APK cannot be updated in place over an officially signed version.

Do not remove your official working app until you can restore its sign-in and local data. Test only on your own device; be aware that this is an unofficial repackaging of proprietary code.

## Build and verification scripts

- `tools/patch_native_arabic.py`: inject native Android `ar-SA` resources into the single APK's resource table.
- `.github/workflows/patch-arabic-sign.yml`: applies those resources, verifies payload changes, executes `aapt`, `zipalign`, and official `apksigner` verification for Android 16.
- `releases/DeLonghi_Android16_Signing_Verification.txt`: actual GitHub Actions SDK verification results.

## Download

Use the raw link to `releases/DeLonghi_MyCoffeeLounge_Arabic_ARM64_APK_Repaired_Experimental.apk` or clone this branch. Avoid the old Catbox link, which contains an earlier invalid-signature artifact.
