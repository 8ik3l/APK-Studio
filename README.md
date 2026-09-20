# APK Studio

Android-native APK inspection and processing tool.

## Current real features
- Select an APK from device storage.
- Read package name, version, version code and target SDK.
- Enumerate every ZIP entry inside the APK.
- Decode APK projects with Apktool 3.0.3 when the device/runtime supports the required toolchain.
- Rebuild decoded projects with Apktool.
- Build a debug APK of APK Studio itself through GitHub Actions.

The app reports actual processing failures instead of showing fake success states.

## Build
Open GitHub Actions → Build APK Studio → Run workflow.
