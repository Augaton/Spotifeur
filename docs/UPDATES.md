# Application updates

## Behavior

Spotifeur checks the latest public stable release of `jean-voila/Spotifeur` each time
the main activity starts, including a return from the background. Checks run without
blocking startup. Concurrent checks are merged, and an existing update prompt or
download is kept when the activity is recreated. Offline mode suppresses automatic
requests. A failed automatic check stays silent and is retried at the next launch.

If the release tag is newer than the installed version, an English/French dialog
shows the version and GitHub release notes, with **Later** and **Update** actions.
Later dismisses this prompt and saves its version and dismissal time. The same
version is offered again at the first startup at least 24 hours later, including
after a process restart. Each later dismissal starts another 24-hour interval.
A different newer release can be offered immediately. Checks still run at every
startup, and a manual check bypasses the reminder delay. Settings includes
**Check for updates**, which also reports an up-to-date result or a network failure.
Drafts and prereleases are excluded. Versions are compared numerically.

Update downloads the release APK inside Spotifeur, with byte/percentage progress
and cancellation. The download belongs to the application process, so activity
recreation does not restart it. Partial files are deleted on cancellation/failure.
If Android terminates the application process, the next attempt starts a fresh
download. No background service or notification is needed to accept an update.

After the download is verified, **Install** opens Android's confirmation. Android's
per-app installation permission is requested only at this point. Granting permission
returns to the installer; declining it leaves the downloaded update ready to retry.
Installation is never silent. Accounts, downloads and settings stay in the existing
application storage. Canceling Android's installer leaves **Install** available.
Failures offer **Retry** and the release page. A release with no unambiguous APK
offers its GitHub page instead.

After a version change, the installed version's release notes are shown once on a
subsequent successful check when no newer update takes priority. Fresh installs
establish a baseline without showing this dialog. Unpublished versions are skipped;
network failures are retried next launch.

## Download and installation contract

- Public GitHub API only; no embedded GitHub token and no Spotify session headers.
- APK URLs must be HTTPS downloads from this repository. HTTPS-to-HTTP redirects
  are disabled. The expected `Spotifeur-v<version>.apk` asset is preferred; otherwise
  exactly one APK must exist.
- Byte count is checked, along with GitHub's SHA-256 asset digest when supplied.
  Downloads are limited to 512 MiB. Missing digests do not disable the independent
  package/version/certificate checks.
- The downloaded APK must have the installed application ID, a greater Android
  version code, the release tag's version name and exactly the same signing
  certificates as the installed app. Debug-signed builds cannot silently replace
  themselves with a release-signed build.
- A non-exported FileProvider grants the installer temporary read access only to
  the dedicated update cache directory. Android verifies the package when installing.

## Behavioral reference

The reference is FeurStagram's existing user flow, inspected in the local source at
`extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java`:
`check`/`LaunchWatcher` (launch and foreground checks), `checkNow` (manual checks),
`showPrompt` (release notes and Later/Update), `Updater` (download and installation),
and `checkWhatsNew` (notes after an upgrade). `Settings.java` exposes its manual check.
These observations are static source evidence, not an on-device observation of
FeurStagram in this session. Spotifeur implements the requested dialog using its own
Compose components; FeurStagram's fullscreen pages and notification UI are not copied.

The API contract follows [GitHub's releases documentation](https://docs.github.com/en/rest/releases/releases).
The Android distribution flow follows [Android's publishing documentation](https://developer.android.com/studio/publish)
and [FileProvider documentation](https://developer.android.com/reference/androidx/core/content/FileProvider).

## Validation

`GitHubReleaseTest` uses local JSON fixtures to check version ordering, release
filtering, asset selection, origin restrictions and digest validation. These are
unit tests, not proof that a future published APK installs successfully.

`UpdatePromptTest` uses an explicit local release fixture to check the real dialog,
release notes, Update action and Later dismissal on a device, without downloading
or installing an APK:

```sh
adb shell am instrument -w -r -e spotifeurUpdateUi true \
  -e class fr.spotifeur.update.UpdatePromptTest \
  fr.spotifeur.test/androidx.test.runner.AndroidJUnitRunner
```

The full install path needs an older installed build and a newer public GitHub
release signed with the same key. Until that combination has been exercised, it is
implemented and statically checked, rather than claimed as live validated.

**Live, 2026-11-09:** the release dialog fixture passes on the phone, including
notes, the Update action and Later dismissal. A manual check against the public
GitHub API reports the installed v1.2.3 as current. The main app is installed with
its existing signing certificate and keeps its Premium session; the separate
Free-account installation keeps its own session. No GitHub release was published.
