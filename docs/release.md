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
