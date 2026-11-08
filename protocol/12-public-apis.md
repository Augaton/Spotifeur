# Public SDKs and Web API

Spotify offers public integration points. They are documented by Spotify and serve different needs from the internal services above.

| Surface | What it does | What it cannot do |
| --- | --- | --- |
| [Android SDK, App Remote](https://developer.spotify.com/documentation/android) | Controls playback in the installed Spotify app over a local connection (`app-remote-control` scope): play a URI, pause, skip, read player state. | Play audio itself. The official app must be installed and does the playback. |
| [Android SDK, Auth](https://developer.spotify.com/documentation/android/tutorials/getting-started) | Gets a user token for a registered developer app. | |
| [Web API](https://developer.spotify.com/documentation/web-api) | Catalog, library and playlist data with the user's authorization (Authorization Code with PKCE on mobile). | Stream audio. |
| [Commercial hardware eSDK](https://developer.spotify.com/documentation/commercial-hardware) | Playback for certified partner devices, with a Media Delivery API. | Open to partners only. |
| [Spotify Soloist](https://developer.spotify.com/documentation/soloist) | Headless player for Linux, macOS on Apple Silicon and Windows x86-64. | Not for Android apps. |

## Access conditions

- A developer app in development mode needs an active Premium subscription on the owner's account and is limited to five authorized users.
- Since July 2026, a developer account can create up to 25 client IDs in development mode; they share one quota. Exceeding it returns `429` with `reason=QUOTA_EXCEEDED`. ([Quota modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes), [July 2026 update](https://developer.spotify.com/blog/2026-07-23-web-api-quota-updates))
- Extended quota requires a registered organization, a launched service and at least 250,000 monthly active users.
- In development mode, playlist items can only be read for playlists the user owns or collaborates on ([Get Playlist Items](https://developer.spotify.com/documentation/web-api/reference/get-playlists-items)).
- Web API redirect URIs must be HTTPS, except HTTP on a literal loopback IP ([redirect URIs](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri)).

## Difference with the internal services

The Android app authenticates with Login5 using its own client ID, and reaches spclient services the Web API does not expose: Playlist4, Collection v2, Extended Metadata, HLS manifests and the native media engine. A Web API token from a developer app was not tested against those services. These notes and Spotifeur use the internal services only.
