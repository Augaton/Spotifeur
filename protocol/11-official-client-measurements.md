# Measurements on the official app

Black-box observations of the official Spotify app on an ARM64 phone running Android 17 (API 37), over ADB, without root and without modifying the app. The installed app was 9.1.94 (build 147600575); its signing certificate matched the 9.2.0.26 APKM, and the relevant native procedures and descriptors were found in both builds. Streaming quality was set to 160 kbit/s on Wi-Fi.

## Decoders

From `dumpsys media.metrics`, filtered on Spotify's UID, over the whole session:

| Platform decoder | Uses | MIME |
| --- | ---: | --- |
| `c2.android.aac.decoder` | 5 | `audio/mp4a-latm` |
| `c2.android.mp3.decoder` | 8 | `audio/mpeg` |
| Secure decoder (`.secure`) or `MediaCrypto` | 0 | |

Every decoder Spotify opened was an ordinary one, including for catalog tracks played cold. A track played in Lossless opened no platform decoder at all: FLAC is decoded in Spotify's own code. Widevine with a platform decoder would need a secure codec or a `MediaDrm` session, which would show here. So for music on this device and account, protection is handled inside the app process.

Limits: media metrics log codec use, not in-process processing. Reading `/proc/<pid>/maps` is not allowed without root. Other content (video, other outputs, other markets) may still use the Widevine path present in the binary.

## Start and transitions

| Scenario | Result |
| --- | --- |
| Cold start | `am start -W` reported `COLD`, 219 ms to launch the activity. First state sample after pressing play, +0.96 s: `PLAYING` at 319 ms. |
| Seek to 72% of a track | First sample +0.93 s: `PLAYING` at the new position. |
| Next | First sample +0.90 s: `PLAYING` at 329 ms. The manual queue plays before the rest of the album. |
| Natural transition | `BUFFERING` at 0 ms, then `PLAYING` at 164 ms 0.48 s later. The AAudio output stayed `started`. |

Positions are those published by Android MediaSession. `buffered position` stayed 0 and cannot be used to compute Spotify's buffer.

## Buffering and network loss

| Measurement | Result |
| --- | --- |
| Initial burst on a new track | 13.26 MB received on `wlan0` in 1.1 s when starting a 26-minute movement. Only 22 kB more in the next 55 s. |
| What the burst covers | At 160 kbit/s, 13.26 MB is about 11 minutes of audio. For a 26-minute file (about 31 MB) that is 43%. The client fills a buffer of about 13 MB, not the whole file. |
| Network cut for Spotify only | Firewall rule 32 s into a 35-minute movement never played on this account. Playback continued 641 s, then went to `BUFFERING` at 11:12. After the network came back, `PLAYING` resumed 37 s later at the same position. No error was published. |
| No continuous refill | After the burst, only a few kB per 5 s were seen until the end of the track. The refill threshold was never reached during these tests. |

Practical consequence: a network drop of a few minutes is not audible, and Android's network state says nothing about playback. Follow the player's own `BUFFERING` state.

## Traffic accounting

Perfetto's `android.network_packets` attributes packets to UIDs but under-counts bursts: over the same 15.5 s window, `wlan0` in `/proc/net/dev` received 13,256,941 bytes while Perfetto attributed 3,663,986 bytes to all UIDs. Use it for timing and attribution, and `wlan0` for sizes. Neither separates audio from artwork, metadata or prefetch.

| Window | Duration | Received (Perfetto) | Sent |
| --- | ---: | ---: | ---: |
| Local file, before seek | 6.53 s | 20,976 | 18,760 |
| Opening an album, before play | 5.29 s | 391,865 | 58,330 |
| After pressing play on the album's first track | 10 s | 5,705,808 | 60,390 |
| Seek in a track already playing | 3.86 s | 7,454 | 6,032 |
| First track of a public playlist | 5.02 s | 2,462,204 | 33,357 |
| Downloaded track in Spotify's offline mode | 4.19 s | 112 | 684 |

A Cronet `netlog` capture limited to Spotify produced no usable slices, so hosts, URLs and headers were not observed this way.
