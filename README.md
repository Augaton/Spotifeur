<div align="center">
  <!-- Logo: to be added (docs/assets/img/icon.png). -->
  <h1>Spotifeur</h1>
  <p><strong>An independent Spotify client for Android, for music only.</strong></p>
  <p>
    Spotifeur signs in to your Spotify account and plays your library with Spotify's own Android protocols and audio engine.
    It has no podcasts, videos, Canvas loops or promoted content, a Material 3 Expressive interface, and more settings than the official app.
  </p>
</div>

<p align="center">
  <a href="https://github.com/jean-voila/Spotifeur/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/jean-voila/Spotifeur/build.yml?branch=main&style=flat-square&label=build"></a>
  <a href="https://github.com/jean-voila/Spotifeur/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/jean-voila/Spotifeur?style=flat-square&label=release&color=10a37f"></a>
  <a href="https://github.com/jean-voila/Spotifeur/releases/latest"><img alt="Download APK, total downloads" src="https://img.shields.io/github/downloads/jean-voila/Spotifeur/total?style=flat-square&label=Download%20APK&color=10a37f"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-6b7280?style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7f52ff?style=flat-square&logo=kotlin&logoColor=white">
</p>

<p align="center">
  <a href="https://github.com/sponsors/jean-voila"><img alt="Support on GitHub Sponsors" src="https://img.shields.io/badge/GitHub%20Sponsors-Support%20the%20Project-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white"></a>
  <br><br>
  <a href="https://buymeacoffee.com/jean_voila"><img alt="Buy Me A Coffee" src="https://img.shields.io/badge/Buy%20Me%20A%20Coffee-Support%20the%20Project-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black"></a>
</p>
<p align="center">
  <img src="https://komarev.com/ghpvc/?username=jean-voila-spotifeur&label=Views&color=gray&style=flat" alt="Views">
</p>

<p align="center">
  <a href="https://github.com/jean-voila/Spotifeur/releases/latest"><strong>Download APK</strong></a>
  ·
  <a href="https://jean-voila.github.io/Spotifeur/">Website</a>
  ·
  <a href="#build-from-source">Build from source</a>
  ·
  <a href="protocol/">Protocol notes</a>
  ·
  <a href="docs/FAQ.md">FAQ</a>
  ·
  <a href="docs/PRIVACY.md">Privacy</a>
  ·
  <a href="https://github.com/sponsors/jean-voila">Support</a>
  ·
  <a href="https://github.com/jean-voila/Spotifeur/issues">Issues</a>
</p>

## Preview

<!-- Mockups to be added: home, player, lyrics, queue, album, playlist, playlist creation, settings. Sources in design/screens/. -->

## Compared with the official app

| | Official Spotify app | Spotifeur |
| --- | --- | --- |
| Your library, playlists, saved tracks, albums and artists | Yes | Yes, same account, synced both ways |
| Full-length playback, seek, queue, shuffle, repeat | Yes | Yes, with Spotify's own audio engine |
| Search, radios, lyrics, playlist recommendations | Yes | Yes |
| Create and edit playlists, custom covers | Yes | Yes |
| Offline downloads and audio cache | Yes | Yes, with an adjustable cache size and prefetch depth |
| Podcasts, audiobooks, videos | Yes | No |
| Canvas loops, promoted content, ads banners | Yes | No |
| Themes, player styles, navigation bar, equalizer, gestures | Few options | Many options |

Spotifeur uses the same services as the official Android app, so what you save, follow or edit in one shows up in the other.

## Features

- **Home and library.** Pinned items first, then your playlists, albums, artists and saved tracks. Pins are stored on the device only.
- **Player.** Full-screen player with seek bar, queue, shuffle, repeat, sleep timer, and the codec and bitrate of the current track if you want them.
- **Lyrics.** Synced lyrics from Spotify, with an immersive mode. When Spotify has none, Spotifeur can look them up on LRCLIB.
- **Queue.** Reorder by dragging, swipe to remove, optional history of played tracks.
- **Playlists.** Create a playlist on your Spotify account or on the device, rename it, set a cover from your photos, publish it on your profile, add tracks from anywhere, and add Spotify's recommendations.
- **Albums and artists.** Album pages, artist pages with top tracks, albums and related artists, and radios based on a track, an album, an artist or a playlist.
- **Search.** Tracks, albums, artists and playlists, with paging.
- **Offline.** Download albums, playlists or tracks. Recently played and upcoming tracks are kept in an encrypted cache (512 MB to 32 GB, up to 20 tracks ahead). An offline mode cuts all network access from the app.
- **Background playback.** Media notification, lock screen and Bluetooth controls, resume when headphones reconnect.
- **Equalizer.** Presets, custom presets, bass boost, virtualizer and loudness.
- **Appearance.** Light, dark or system theme, dynamic colors from the album art with several palette styles, floating or full-width navigation bar, home collage patterns, carousel style, blur on or off, smooth corners, English or French.
- **Cast.** Google Cast for local files.
- **Backup.** Export and import your settings, pins and local playlists.

## Why

I was tired of paying for a service whose app is slow and keeps pushing podcasts, videos and other content I never asked for. I wanted the same music library in an app that only does music and that I can set up the way I like. Spotifeur still needs a Spotify account: it is a different app for the same service, not a way around it.

## How it works

Spotifeur does not use the public Web API, App Remote, a WebView or the installed Spotify app. It talks to Spotify the way the official Android app does:

- **Sign-in** with Login5 and ClientToken, using a code sent to your email address (or a password). The session is encrypted with Android Keystore.
- **Library and catalog** through Playlist4, Collection v2, Extended Metadata and the search service.
- **Audio** through HLS manifests processed by Spotify's own native engine, loaded unchanged from the official APK you supply at build time.

Everything learned along the way is documented in [`protocol/`](protocol/), as a reference you can read without the code.

## Is it safe?

- There is no Spotifeur server, no analytics and no tracking. The app talks to Spotify, and to [LRCLIB](https://lrclib.net) for lyrics Spotify does not have (title, artist, album and duration only; you can turn it off).
- Your password is never stored. The email-code sign-in does not use one at all.
- The session credential stays on your device, encrypted with Android Keystore.
- Each release lists the SHA-256 of the APK and of its signing certificate. You can also build the APK yourself from this source and your own copy of the official Spotify APK.

## What Spotifeur does not do

- Bypass Premium or play anything your account is not allowed to play.
- Save decrypted audio. Downloads stay encrypted and are only processed by Spotify's engine at playback time.
- Share accounts or proxy your traffic.
- Download anything from Spotify at build time. To build it yourself, you provide the official APK.

## Install

Download `Spotifeur-vX.Y.Z.apk` from the [latest release](https://github.com/jean-voila/Spotifeur/releases/latest) and install it on an ARM64 phone with Android 8.0 or later. Check its SHA-256 against the release notes. Then open Spotifeur, enter your Spotify email address and type the code Spotify sends you.

## Build from source

Requirements: JDK 21, Android SDK with platform 37, an ARM64 Android device (Android 8.0 or later), and the official Spotify Android **APKM, version 9.2.0.26** (build 148373686). The build checks the SHA-256 of the files it extracts and stops if they differ.

```sh
git clone https://github.com/jean-voila/Spotifeur.git
cd Spotifeur
./gradlew :app:assembleDebug -PspotifyArtifact=/path/to/com.spotify.music_9.2.0.26.apkm
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also place the APKM at `native-runtime/artifacts/spotify-9.2.0.26.apkm`. Nothing is downloaded automatically. See [`native-runtime/README.md`](native-runtime/README.md) for details.

Without the APKM, `-PnativeRuntime=false` builds the app and runs the tests, but playback stops after the first seconds of each track. The [CI](.github/workflows/build.yml) uses this mode.

```sh
./gradlew -PnativeRuntime=false :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

A build you make yourself is signed with your own key: uninstall a release build first (this erases the session and downloads).

To publish a release, `scripts/release.sh --apkm /path/to/spotify.apkm` builds, signs, checks and uploads the APK. Run it with `--help` for the options.

## Documentation

- [Protocol notes](protocol/): ClientToken, Login5, catalog, library, playlists, audio delivery, native engine, offline, Spotify Connect
- [Native runtime](native-runtime/README.md)
- [FAQ](docs/FAQ.md)
- [Privacy](docs/PRIVACY.md)
- [Contributing](CONTRIBUTING.md)
- [Code of conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md)

## Credits

- [librespot](https://github.com/librespot-org/librespot) for its public work on Spotify's protocols.
- [AndroidX Media3](https://github.com/androidx/media), [Jetpack Compose](https://developer.android.com/compose), [OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/) and [LRCLIB](https://lrclib.net).

## License

[GNU General Public License v3.0](LICENSE).
