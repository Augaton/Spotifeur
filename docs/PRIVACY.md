# Privacy

Spotifeur has no server, no analytics, no crash reporting and no advertising SDK.

## What the app sends, and to whom

| Destination | What | Why |
| --- | --- | --- |
| Spotify (`*.spotify.com`, `*.scdn.co`, Spotify CDNs) | The same requests as the official Android app: device description for the client token, sign-in, library, catalog, playlists, audio | To use your Spotify account |
| YouTube (`music.youtube.com`, `www.youtube.com`, `*.googlevideo.com`) | Search text, playlist and video ids, an anonymous visitor identifier issued by YouTube; for a Spotify track played without a Spotify account, its title and artist | YouTube Music search, playlists and audio, without a Google account |
| Spotify embed page (`open.spotify.com`) | A playlist id | Import a Spotify playlist without a Spotify account |
| [LRCLIB](https://lrclib.net) | Title, artist, album and duration of a track | Lyrics when Spotify has none. Turn off in Settings → LRCLIB fallback. |

Spotify receives a description of the device when the app registers (screen size, Android version, model, brand, manufacturer, volume steps, installer package), as it does from the official app. See [`protocol/02-client-token.md`](../protocol/02-client-token.md).

Your Spotify credentials are only sent to Spotify's login service. Audio CDNs never receive them.

## What stays on the device

- The session credential, encrypted with an Android Keystore key.
- The library, pages, artwork and lyrics cache.
- Downloaded and cached audio as delivered: encrypted for Spotify, plain for YouTube Music.
- Settings, pins, multi-service playlists, YouTube Music matches of Spotify tracks and search history.

Sign out (Settings → Spotify account) erases the credential and stops playback. Clearing the app's data erases everything.

## Permissions

| Permission | Use |
| --- | --- |
| Internet, network state | Talk to Spotify and YouTube Music, follow connectivity |
| Foreground service, notifications | Background playback and download progress |
| Wake lock | Keep playing with the screen off |
| Bluetooth, nearby Wi-Fi devices, Wi-Fi multicast, location (Android 12 and older only) | Find Cast devices and audio outputs. Location is never read; older Android versions require it for device scans. |
| Read audio / storage | Play local files |
