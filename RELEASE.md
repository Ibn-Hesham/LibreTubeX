# Release instructions
Every push to `main` gets its own release. Tagged releases are optional milestones.

## Prerequirements
- The following actions secrets have to be set (see [ilharp/sign-android-release](https://github.com/ilharp/sign-android-release)):
  - `ANDROID_RELEASE_SIGNING_KEY`
  - `ANDROID_RELEASE_KEY_ALIAS`
  - `ANDROID_RELEASE_KEYSTORE_PASSWORD`
  - `ANDROID_RELEASE_KEY_PASSWORD`
- If you don't have a keystore yet, create one, e.g. via `keytool -genkey -v -keystore release.keystore -alias libretubex -keyalg RSA -keysize 2048 -validity 10000`, and store it base64-encoded (`base64 -w0 release.keystore`) as the `ANDROID_RELEASE_SIGNING_KEY` secret.
- Additionally, GitHub actions workflows must be configured to have "Read and write permissions" in the repository's settings.

## Automatic releases
Every push to `main` automatically builds, signs and publishes a release tagged `v<versionName>.<versionCode>` (e.g. `v32.1.81`): a real release build (optimized, package id `com.github.libretubex`) with an ever-increasing `versionCode` (`72 + CI run number`), so Android and Obtainium treat every push as an update. Each release is marked as latest, so `releases/latest` and Obtainium always resolve to the newest build. The workflow keeps only the **10 most recent per-build releases** — older ones (and their tags) are deleted automatically. Manually tagged releases are never pruned. No action needed.

Debug builds are still compiled by CI as a build check (and can be signed too by additionally setting the `ANDROID_SIGNING_KEY`, `ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD` secrets), but they are only uploaded as workflow artifacts, not published.

### Downgrading
Android refuses to install an APK with a lower `versionCode` over an installed one. To roll back to one of the older releases, either uninstall the app first (downloads and local playlists live in the app's external files directory and survive; settings and history do not) or install it via `adb install -d libretubex.apk`.

## Tagged releases (optional)
Milestone tags follow upstream: after merging an upstream release that bumps the `versionName`, you can tag the result.

1. Set `versionCode` in `build.gradle.kts` to a value **higher than the versionCode of the latest automatic release** (shown on the release page), so users can upgrade to the tag.
2. Create a file `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and write a short summary about the release into this file.
3. Commit the changes to `main` and push.
4. Create a new tag from the `main` branch, e.g. via `git tag v33.0 && git push --tags`. The release workflow builds, signs and publishes the release automatically.

Use a **two-part tag** (e.g. `v33.0`): per-build releases use three-part tags (`v32.1.81`), and only those are pruned — tagged releases are kept permanently.

Note that the next automatic release after the tag will again use `72 + CI run number` — if that ends up lower than the tagged release's `versionCode`, adjust the offset in `.github/workflows/ci.yml` accordingly.
