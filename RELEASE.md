# Release instructions
The rolling `nightly` release is the official way LibreTubeX is distributed. Tagged releases are optional milestones.

## Prerequirements
- The following actions secrets have to be set (see [ilharp/sign-android-release](https://github.com/ilharp/sign-android-release)):
  - `ANDROID_RELEASE_SIGNING_KEY`
  - `ANDROID_RELEASE_KEY_ALIAS`
  - `ANDROID_RELEASE_KEYSTORE_PASSWORD`
  - `ANDROID_RELEASE_KEY_PASSWORD`
- If you don't have a keystore yet, create one, e.g. via `keytool -genkey -v -keystore release.keystore -alias libretubex -keyalg RSA -keysize 2048 -validity 10000`, and store it base64-encoded (`base64 -w0 release.keystore`) as the `ANDROID_RELEASE_SIGNING_KEY` secret.
- Additionally, GitHub actions workflows must be configured to have "Read and write permissions" in the repository's settings.

## Rolling release (automatic)
Every push to `main` automatically builds, signs and publishes the rolling release: a real release build (optimized, package id `com.github.libretubex`) with an ever-increasing `versionCode` (`72 + CI run number`), so Android and Obtainium treat every push as an update. The previous APK is replaced. No action needed.

Debug builds are still compiled by CI as a build check (and can be signed too by additionally setting the `ANDROID_SIGNING_KEY`, `ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD` secrets), but they are only uploaded as workflow artifacts, not published.

## Tagged releases (optional)
1. Increment the `versionName` in `build.gradle.kts` and set `versionCode` to a value **higher than the versionCode of the current rolling release** (shown on the release page), so users can upgrade from the rolling release to the tag.
2. Create a file `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and write a short summary about the release into this file.
3. Commit the changes to `main` and push.
4. Create a new tag from the `main` branch, e.g. via `git tag v0.1 && git push --tags`. The release workflow builds, signs and publishes the release automatically.

Note that the next rolling release after the tag will again use `72 + CI run number` — if that ends up lower than the tagged release's `versionCode`, adjust the offset in `.github/workflows/ci.yml` accordingly.
