# Offline playback

This page describes what a third-party client using the native engine can keep on the device to play without network, and what it cannot. The official app's own offline system (`Offline/AddDownload`, device licenses) is different and is described in [07-audio-delivery.md](07-audio-delivery.md#queue-and-downloads-in-the-official-engine).

## What has to be stored

Playing a mode 4 track without network needs four things on the device:

| Item | Why |
| --- | --- |
| The `audio_id` and HLS playlists (master and media) | To rebuild the manifest without spclient. CDN URLs in them expire, so key the cached segments by `file_id` without the signature. |
| The media bytes, as delivered (still encrypted) | Init segment and all byte ranges. |
| The engine's response to `GET /playplay/v1/key/{file_id}` | So that `GetMediaHandleId` can produce a handle without network. |
| The last account attributes | The engine reads them at startup. |

The key response is opaque. Store it as received, encrypted at rest (for example AES-GCM with an Android Keystore key per account, authenticating account and `file_id`), and hand it back to the engine through the HTTP factory when it asks for the same path. Never store it in clear, never write HTTP errors over a good copy, and bound its size.

Storing these responses does not decrypt anything. The audio stays encrypted on disk and is only processed by the engine at playback time.

## Expiry inside the engine

The engine's media context has its own internal deadline, separate from the network tokens. When starting offline, give the media context a deadline that lets it interpret the local response, without waiting for a network renewal that cannot happen. This internal deadline is not a new right granted by Spotify: once back online, the client must renew Login5 and ClientToken for real before any network call.

## Rate limits

`GetMediaHandleId` triggers a network request for every file without a stored key response. Preparing a large queue at once leads to HTTP 429. Space the requests, honour `Retry-After`, reuse stored responses even when online, and release subscriptions for files that were only being cached.

## Verification

**Live**, on an ARM64 phone, measuring decoded PCM:

- Same process, network cut after caching: signal past the lead-in, after a seek, and on the next track.
- Two explicit downloads finished, then offline: signal at 20 s, after a seek, and on the second track.
- **New process, built without the INTERNET permission** (a socket cannot be opened), with expired token deadlines and an invalid bootstrap bearer: signal after 20 s, after a seek to 50%, on the next track, and after a seek to 75%.
- Same, then reconnect: real renewal through Login5, and signal on a third track.

These show that stored responses can be replayed. They do not show how long Spotify accepts them, or that every account and device behaves the same. Clearing the app's data or its Keystore key makes them unusable.
