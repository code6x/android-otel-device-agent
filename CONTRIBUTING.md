# Contributing

Thank you for helping. Bug reports, phone reports and pull requests are welcome.

## Report a bug or a phone

Use the issue templates. Please give:

- the phone model, the Android version and the app version (on the app screen);
- what you expected and what happened;
- the config you use, **without** tokens, header values or private endpoints;
- logcat lines if you have them: `adb logcat -s OtelAgent`.

Do not post secrets, tokens or the address of your own receiver in an issue.

A phone that drops readings (gaps in the data) is useful to know. Say which
setup checklist items are done (README, "Setup checklist").

## Build and test

You need JDK 17 and the Android SDK (compileSdk 36).

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug       # debug APK
./gradlew :app:lintVitalRelease    # lint, as in CI
```

CI runs the same three commands on every pull request.

Test on an emulator first. The README shows how to test managed
configurations and OpAMP on an emulator.

## Pull requests

- One change per pull request. Small is better.
- Add or change unit tests for the code you change.
- A new metric: add it to [`docs/metrics.md`](docs/metrics.md) with its unit,
  type, attributes, collector and source.
- A new config key: add it to the config table in the README.
- Use plain, simple English in docs and messages.

## License

By sending a pull request you agree that your change is under the
[Apache-2.0](LICENSE) license of this project.
