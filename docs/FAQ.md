# FAQ

## Do I need a Spotify account?

No. Without an account, Spotifeur plays the YouTube Music catalog and the audio files on your phone, and Spotify tracks of imported playlists play from their closest YouTube Music match. With an account, it shows your Spotify library and plays what your account is allowed to play. It was tested with a Premium account. Free accounts were not tested.

## Do I need the official Spotify app installed?

No. Spotifeur does not use the installed app. Spotify's audio engine, taken unchanged from the official APK, is packaged inside Spotifeur.

## Where do I get the APK?

From the [latest release](https://github.com/jean-voila/Spotifeur/releases/latest). Each release lists the SHA-256 of the APK and of its signing certificate. You can also build it yourself from the source and your own copy of the official APKM: see [Build from source](../README.md#build-from-source). A build you made yourself is signed with a different key, so uninstall the other one first (this erases the session and downloads).

## Which Spotify version is needed?

Exactly 9.2.0.26 (build 148373686), ARM64. The build checks the SHA-256 of the extracted files and stops if they differ.

## Does it work on x86 emulators or 32-bit phones?

No. The engine is ARM64 only.

## How do I sign in?

Enter the email address of your Spotify account, then the code Spotify emails you. Signing in with a password is also available. The session is kept encrypted on the device and restored at each launch. Settings → Spotify account → Sign out erases it.

## Why no podcasts, audiobooks, videos or Canvas?

Spotifeur is for music only. Those items are filtered out of search, pages and the library.

## Are my changes synced with the official app?

Yes. Saved tracks, albums, followed artists and playlists use the same services as the official app. Pins are the exception: they stay on the device.

## How does offline playback work?

Download an album, a playlist or a track from its page. Recently played and upcoming tracks are also kept in a cache you can size in Settings → Cached storage. Audio stays encrypted on the device. The offline mode switch stops all network access from the app.

## Can I use Spotifeur to save MP3 files or skip Premium?

No. Spotifeur never writes decrypted audio and does not unlock anything your account does not have.

## Where is the protocol documentation?

In [`protocol/`](../protocol/). It can be read without the code.
