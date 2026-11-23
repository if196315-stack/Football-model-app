# Android release process

The Android release workflow builds and verifies a release-signed APK, then publishes it as the latest GitHub Release.

## Install or update

1. Open the repository Releases page: https://github.com/if196315-stack/Football-model-app/releases/latest
2. Download `app-release.apk` from the latest release assets.
3. Open the downloaded APK on the Android device and confirm installation.
4. If Android asks to allow installs from this source, enable that permission and retry.

The release uses the persistent Android upload key configured in GitHub Actions. Keep that key and its passwords backed up securely; future app updates must use the same signing certificate.

## Versioning

Increase both `versionCode` and `versionName` in `android/app/build.gradle` for every published update. Android requires a higher versionCode to install an update over an existing installation.
