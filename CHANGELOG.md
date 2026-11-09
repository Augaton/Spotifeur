# Changelog

## Unreleased

Nothing yet.

## v1.2.1

### Fixed

- Opening a playlist that refers to local files no longer blocks the main thread: `TrackMatcher` compiled its regular expressions on every call, and every playlist entry was compared with every device file. The expressions are now compiled once and the device files are indexed once per file list (`LocalFileIndex`). Opening a 1653-track playlist went from a 290 ms frame to 60–110 ms, with the page transition drawn.
- Extended Metadata responses are decoded on `Dispatchers.Default` instead of the caller's thread.
- Player buttons and track changes no longer wait for the queue: `SpotifyPlayer` rebuilt and compared the whole queue on the main thread every 300 ms and at every player event. It now rebuilds it only when the timeline changes.

### Changed

- The player holds a window of the context, 20 tracks before the current one and 100 after it, as Spotify's player does. The rest of the playlist stays in `SpotifyPlayer` and is added as playback moves forward or back. Shuffle still covers the whole context, "Add to queue" still goes after it, and "repeat all" wraps to its start.

## v1.2.0

First public release.

### Added

- Welcome screen: the name, an optional Spotify sign-in card and "Continue without an account", without explanatory text. The media permission is asked once at first launch, so local files are listed immediately.
- Remove tracks from Spotify playlists you own, from the track menu or with a swipe. Spotify removes rows, so one copy of a duplicated track can go alone (`PlaylistService/Modify` with `rows`, see `protocol/06-playlist-writes.md`).
- Swipe on track rows. Right adds to the queue by default, left does nothing. Each direction can be set to add to the queue, play next, save to Liked songs, remove from the playlist, or nothing (Settings → Behavior). A direction set to nothing leaves the gesture to the page, so library tabs still swipe.
- Audio quality settings for Wi-Fi, mobile data and downloads: Automatic, Normal (AAC 128 kbit/s), Very high (AAC 256 kbit/s). The HLS master lists only these two AAC variants; the Vorbis, AAC 24 and FLAC files return 404 on this path (`protocol/07-audio-delivery.md`). A track with a stored copy keeps playing that copy.
- Listening history, from the clock icon on Home and Library: plays recorded in Spotifeur for every source, merged with `recently-played/v3` from the Spotify account, grouped by day. The local part can be cleared.
