# Contributing to Spotifeur

Thanks for considering a contribution.

## Ways to help

- Report bugs with clear steps to reproduce.
- Test the app on other Android devices and versions.
- Improve the protocol notes in [`protocol/`](protocol/), with the evidence level stated (static, tested with doubles, or live).
- Improve the build, the CI or the documentation.
- Fix bugs in playback, offline storage, the library or the interface.

## Before opening an issue

Include:

- the Spotifeur version or commit;
- your Android version and device model;
- whether you built it with the native engine or with `-PnativeRuntime=false`;
- steps to reproduce;
- relevant `adb logcat` output, **with tokens, usernames and email addresses removed**.

## Pull requests

1. Keep the change focused and explain why it is needed.
2. Run `./gradlew :app:testDebugUnitTest :app:lintDebug` (add `-PnativeRuntime=false` if you do not have the Spotify APKM).
3. For audio changes, check the decoded signal, not only the player state: past the unencrypted lead-in, after a seek, and across a track transition. See [`native-runtime/README.md`](native-runtime/README.md).
4. Write code, comments and documentation in English. New user-facing strings go in `res/values/` (English) and `res/values-fr/` (French).

## What will not be accepted

- Anything that bypasses Premium restrictions, shares accounts or extracts decrypted audio.
- A switch to the public Web API, App Remote or the installed Spotify app as a dependency.
