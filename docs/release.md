# Releases

A release is a signed APK on GitHub Releases, made by the `Release` workflow
(`.github/workflows/release.yml`) when a tag `vX.Y.Z` is pushed:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The workflow runs the unit tests, builds `assembleRelease` with
`-PversionName=X.Y.Z` (versionCode = X·10000 + Y·100 + Z), checks the
signature with `apksigner`, and publishes the APK and its sha256.

## Signing key

The key is never in git (`*.jks` and `*.keystore` are in `.gitignore`). The
workflow reads it from four repository secrets:

| Secret | What |
|---|---|
| `KEYSTORE_B64` | The keystore file, base64 (`base64 -w0 release.jks`) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Without `KEYSTORE_B64` the workflow stops with an error; it never publishes an
unsigned APK. Keep a backup of the keystore outside GitHub: a lost key means
users must uninstall the app before they can install a new version.

To make a key:

```bash
keytool -genkeypair -v -keystore release.jks -alias release \
  -keyalg RSA -keysize 4096 -validity 10000
```

Set the secrets with `gh secret set NAME -R code6x/android-otel-device-agent`
(it reads the value from stdin).

A local signed build works the same way: set `KEYSTORE_FILE`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` in the environment and run
`./gradlew :app:assembleRelease -PversionName=X.Y.Z`. Without `KEYSTORE_FILE`
the release APK is unsigned.

## Reproducible build

The same source gives the same APK, byte for byte, apart from the signature.
So anyone (and F-Droid or IzzyOnDroid) can check that a release APK was made
from the tagged source.

What makes it so:

- Fixed versions of all dependencies and plugins, and of Gradle itself
  (`gradle-wrapper.properties` has the `distributionSha256Sum`).
- No dependency info block in the APK (`dependenciesInfo` is off in
  `app/build.gradle.kts`): it is encrypted with a Google key, so no one else
  could rebuild it.
- No git info in the APK (`vcsInfo` is off for release). Without it, a
  build from a folder with no `.git` gave a different
  `META-INF/version-control-info.textproto`.
- No build time or build path goes into the APK.

Checked on 2026-11-28: two clean `assembleRelease` builds of the same commit,
in two different folders, gave unsigned APKs with the same sha256. A rebuild
of v0.1.0 from a folder with no `.git` was the same as the released APK in
every file but `META-INF/version-control-info.textproto`.

To check a release (from v0.2.0 on; v0.1.0 was built before this was done):

```bash
git checkout vX.Y.Z
./gradlew :app:assembleRelease -PversionName=X.Y.Z   # JDK 17, no KEYSTORE_FILE
gh release download vX.Y.Z -R code6x/android-otel-device-agent -p '*.apk'
pip install apksigcopier   # it needs apksigner from the Android build-tools in PATH
apksigcopier compare --unsigned android-otel-device-agent-X.Y.Z.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk && echo same
```

`apksigcopier compare` copies the signature of the release APK onto your
build and checks that the result is the same file.
