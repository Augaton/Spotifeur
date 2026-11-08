# FAQ

## Do I need a Spotify account?

Yes. Spotifeur is a client for your Spotify account: it shows your library and plays what your account is allowed to play. It was tested with a Premium account. Free accounts were not tested.

## Do I need the official Spotify app installed?

No. Spotifeur does not use the installed app. At build time, you supply the official APKM so that Spotify's audio engine can be packaged inside Spotifeur.

## Why is there no APK to download?

The audio engine is Spotify's code, taken from the official APK. It cannot be redistributed in this repository or in releases, so you build Spotifeur yourself with your own copy of the official APKM. See [Build from source](../README.md#build-from-source).

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

## Can my account be banned?

Spotifeur behaves like the official Android client, but it is not the official client. Spotify's terms of service do not allow unofficial clients, and Spotify could restrict an account that uses one. Use it at your own risk.

## Can I use Spotifeur to save MP3 files or skip Premium?

No. Spotifeur never writes decrypted audio and does not unlock anything your account does not have.

## Where is the protocol documentation?

In [`protocol/`](../protocol/). It can be read without the code.
