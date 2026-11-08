# Hosts, client identity and headers

## Hosts

| Host | Role |
| --- | --- |
| `clienttoken.spotify.com` | Issues the client token (`POST /v1/clienttoken`). See [02-client-token.md](02-client-token.md). |
| `login5.spotify.com` | Account login (`POST /v3/login`, `POST /v4/login`). See [03-login5.md](03-login5.md). |
| `spclient.wg.spotify.com` | Webgate: catalog, library, playlists, search, lyrics, HLS manifests, licenses. |
| `apresolve.spotify.com` | Endpoint discovery (`/v2`). Categories `accesspoint`, `spclient`, `dealer-g2`. |
| `dealer.g2.spotify.com:443` | WebSocket (`wss://`) used for pub/sub and Spotify Connect. |
| `image-upload.spotify.com` | Playlist cover upload (`/v4/playlist`). |
| `i.scdn.co` | Cover art by image ID. |
| `mosaic.scdn.co` | 2×2 mosaic covers built from up to four image IDs. |
| `audio*.scdn.co`, other CDNs | Audio segments. They never need Spotify credentials. |

Static evidence: `https://spclient.wg.spotify.com` at `0x359e07` is referenced from `0xb52e88` and `0xb52ea0`. The same configuration block loads `wss://dealer.g2.spotify.com:443` at `0xb52e74`. `https://apresolve.spotify.com/v2` at `0x39517d` is referenced from `0x137a728`. `https://clienttoken.spotify.com/v1/clienttoken` at `0x3bd3c8` is referenced from `0xb7f8ec`. The Login5 v3 and v4 URLs at `0x3bd9b5` and `0x3bd9fc` are referenced from `0xba6cac` and `0xba70cc`.

## Client identity

The Android client identifies itself with constants compiled into the package. They are part of every message below and are not a developer registration.

| Constant | Value | Source |
| --- | --- | --- |
| Client ID | `9a8d2f0ce77a4e248bb71fefcb557637` | Set by `p.jmn0.run()` (`classes4.dex`) on the native Login5 configuration |
| Client version | `9.2.0.26` | Version name of the package |
| Platform | `Android` | `App-Platform` header |

The device ID is a random identifier generated once per installation and reused for ClientToken, Login5 and the native session.

## User-Agent

`p.j1l.h` (`classes5.dex`, instruction `0x3c5cb8`) builds the default HTTP user agent passed to `ApplicationScopeConfiguration.setDefaultHTTPUserAgent`:

```text
Spotify/{version} Android/{Build.VERSION.SDK_INT} ({Build.MODEL})
```

For example `Spotify/9.2.0.26 Android/36 (Pixel 9)`. A client that sends the OkHttp default instead is easy to tell apart.

## Request headers on spclient

| Header | Value | Notes |
| --- | --- | --- |
| `Authorization` | `Bearer {access_token}` | Login5 access token |
| `client-token` | ClientToken value | Required alongside the bearer |
| `Accept` | `application/x-protobuf` or `application/protobuf` | Most services answer Protobuf. Search accepts `application/protobuf`. |
| `Spotify-App-Version` | `9.2.0.26` | |
| `X-Client-Id` | client ID above | |
| `App-Platform` | `Android` | |
| `Accept-Language` | BCP 47 tag | Affects localized fields |
| `x-accept-list-items` | `audio-track` | Playlist4 reads, so the server only returns audio items |

Collection v2 uses its own media type for both `Accept` and `Content-Type`: `application/vnd.collection-v2.spotify.proto` (loaded explicitly in the procedure at `0x13c4db4`).

## Credential scope

The ClientToken grant lists the domains it is valid for (`GrantedTokenResponse.domains`, field 4). A careful client checks the host against that list before attaching the token, sends the bearer only to Spotify API hosts, and drops both headers when a request is redirected to a CDN.

On HTTP 401, renew the Login5 token once and retry once. On HTTP 429, honour `Retry-After`.

## Network security config

The manifest references `res/xml/network_security_config.xml`. It allows cleartext in `base-config`, then forbids it for `spotify.com`, `spotify.net`, `spotifyinternal.com`, `scdn.co` and other listed domains, subdomains included. There is no `pin-set` in that file. This says nothing about TLS checks performed inside the native library.
