# Playlist writes

The official app does not edit playlists with plain HTTP calls. It sends Esperanto requests to the native core, which applies them locally and syncs them to Playlist4 in the background. A third-party client can do the same by loading the native engine (see [08-native-engine.md](08-native-engine.md)) and calling these services through its Cosmos router.

| Service | Method | Purpose |
| --- | --- | --- |
| `spotify.playlist_esperanto.proto.RootlistModificationService` | `Modify` | Create, remove and publish playlists; folders |
| `spotify.playlist_esperanto.proto.PlaylistService` | `Modify` | Add tracks, rename, change attributes |
| `spotify.playlist_esperanto.proto.PlaylistDataService` | `Get` | Read a playlist through the core, with recommendations |

Static anchors: `p.jn7.N/O` (`classes5.dex`) send `PlaylistService/Modify` and `RootlistModificationService/Modify`. `p.zn0.i` (`classes8.dex`) creates a playlist. `p.xyc0` (`classes2.dex`) adds tracks and sets attributes. `p.pp31.d` (`classes2.dex`) sets the attributes of a rootlist row (publishing).

## Modification request

Both `Modify` methods take `{ 1 target_uri, 2 ModificationRequest }`. For the rootlist the target is empty.

`ModificationRequest` (`classes10.dex`):

| Field | Meaning |
| --- | --- |
| 1 | Operation: `create`, `add`, `remove`, `set` |
| 2 | Position: `start` or `end` |
| 4 | Name (create) |
| 5 | `playlist = true` (create a playlist, not a folder) |
| 6 | Attributes: 1 `published`, 3 `name`, 6 `picture` (hex) |
| 7 | Item URIs (repeated) |
| 8 | Row URI (rootlist `set` and `remove`) |
| 9 | Remove flag used with `remove` |
| 14 | Create options: 1 = enum (1 in the official create call) |

The response has 1 = status (1 = code, 2 = message) and 2 = result (2 = URI of a created playlist). Treat codes outside 200–299 as failures. `400` with `not found in rootlist` means the core has not loaded the rootlist yet.

## Operations

**Create.** `RootlistModificationService/Modify` with `{1: "", 2: {1: "create", 2: "start", 4: name, 5: true, 7: uris…, 14: {1: 1}}}`. The response gives the new `spotify:playlist:` URI.

**Add tracks.** `PlaylistService/Modify` with `{1: playlist_uri, 2: {1: "add", 7: uris…}}`. Send at most 100 URIs per request. Order and duplicates are kept.

**Remove tracks.** `PlaylistService/Modify` with `{1: playlist_uri, 2: {1: "remove", 8: row_ids…}}`. Field 8 is `rows` in `modification_request.proto` (`spotify.playlist.cosmos.proto.ModificationRequest`: 1 `operation`, 2 `before`, 3 `after`, 4 `name`, 5 `playlist`, 6 `attributes`, 7 `uris`, 8 `rows`, 9 `contents`, 10 `item_id`). Rows, not URIs, are removed, so one copy of a duplicated track can go alone. Get the row IDs from `PlaylistDataService/Get` with item decoration `{4: {1: uri, 9: row_id}}` (`PlaylistItemDecorationPolicy`) and query `{4: range, 8: show_unavailable}`; each `Item` of the response (`2 → 1`) has 7 `row_id` and 18 `uri`. Wait for Playlist4 over HTTP to show the new length before releasing the engine.

**Rename.** `PlaylistService/Modify` with `{1: "set", 6: {3: name}}`.

**Publish on profile.** Publishing is an attribute of the playlist's row in the user's rootlist (`Playlist4.ItemAttributes` field 10, `public`), not a playlist permission. It is unrelated to collaborative mode. First make the core load the rootlist with `PlaylistDataService/Get` on `spotify:user:{username}:rootlist`, then send `RootlistModificationService/Modify` with `{1: "set", 8: row_uri, 6: {1: published}}`. Read the rootlist over HTTP to confirm.

**Unfollow / delete.** `RootlistModificationService/Modify` with `{1: "remove", 8: row_uri, 9: false}`.

### Acknowledgement is not sync

`Modify` returns once the local core has accepted the transaction. The server sees the change later. A client must keep the native engine alive until Playlist4 over HTTP shows the change (the new playlist exists, or the added items are at the expected positions). If the engine is shut down earlier, the change can be lost. Reading the playlist over HTTP right after `Modify` can also return the old state, so do not overwrite the UI with it.

## Cover image

Static: `SetPlaylistPictureWorker`, `p.t080`, `p.zmu0` (`classes3.dex`).

1. Prepare a square JPEG (the official app uses 640 × 640, honouring EXIF orientation).
2. Upload it:

   ```text
   POST https://image-upload.spotify.com/v4/playlist
   Authorization: Bearer {access_token}
   client-token: {client_token}
   Content-Type: image/jpeg
   Content-Encoding: identity
   ```

   The JSON response has `uploadToken`.
3. Register it:

   ```text
   POST spclient/playlist/v2/playlist/{id}/register-image
   Content-Type: application/protobuf
   body: { 1: upload_token }
   ```

   Field 1 of the response is a 20-byte image ID.
4. Set it with `PlaylistService/Modify` `{1: "set", 6: {6: picture}}`, where `picture` is the **40-character lowercase hex** form of the 20 bytes. Sending the raw bytes does not work.

## Recommendations

`PlaylistDataService/Get` with `load_recommendations` (query field 10 = true) returns suggested tracks in `data.recommendations` (response `2 → 10`). Each `RecommendationItem` has 1 = `TrackMetadata`, whose field 3 is the track URI. Hydrate the URIs with Extended Metadata and drop tracks already in the playlist.

When the service returns nothing (common for new or small playlists), a reasonable fallback is to interleave the radios of the playlist's last few tracks.

## Ownership

`PlaylistDataService/Get` metadata (`2 → 2 → 1`) field 4 is 1 when the current user owns the playlist. Only offer editing for owned playlists.

## Verification

**Live:** one test run on a real account, about 9 s: create a temporary playlist with one saved track, rename it and add a second track, publish and unpublish it (checked by reading the rootlist back), upload and register a cover, read back the name, track order and image from Playlist4, receive 13 native recommendations, then remove the playlist and confirm it left the rootlist. A later run added a duplicate of the first track, removed the second copy by its row ID, and read back the original order from Playlist4.
