# Spotify Connect

Spotify Connect keeps playback state in sync across a user's devices and carries remote-control commands. This page is **static only**: it comes from the descriptors compiled into Orbit (`connect.proto` and `player.proto` in `spotify.connectstate`, `devices.proto` in `spotify.connectstate.devices`). The exact order of messages on the wire was not observed.

## Transport

1. Open `wss://dealer.g2.spotify.com:443`. The dealer sends a connection ID (diagnostic at `0xbd0370`, decorator `connection_id_decorator` at `0x1372dac`).
2. Publish the device state with a PUT to `connect-state/v1/devices/{device_id}` on spclient, with header `X-Spotify-Connection-Id` (literal at `0x3aa60f`, loaded at `0x13737fc`).
3. The server answers with a `Cluster`: the shared state of the account.
4. Commands arrive over the WebSocket on routes like `connect-state/v1/player/command/from/{sender}` and `connect-state/v1/connect/transfer|volume|rename/from/{id}`.

Other routes: `hm://connect-state/v1/connect/logout`, `proxy_wake`, `proxy_transfer`, `volume`, `…/group/add-device`, `…/group/remove-device`, `connect-state/v1/cluster/wake-devices`. Retry logic is logged as `ConnectStateClientImpl PutStateBackoff`, `allow_concurrent_put_states` and `putstate response with … cluster update reason was …`.

## `PutStateRequest`

`callback_url`, `device` (`device_info`, `player_state`, `transfer_data`), `member_type`, `is_active`, `put_state_reason`, `message_id`, `last_command_sent_by_device_id`, `last_command_message_id`, `has_been_playing_for_ms`, `client_side_timestamp`, `only_write_player_state`.

## `DeviceInfo` and `Capabilities`

`DeviceInfo`: `can_play`, `volume`, `name`, `device_software_version`, `device_type`, `spirc_version`, `device_id`, `client_id`, `brand`, `model`, `license`, `is_group`, `audio_output_device_info`, metadata map.

`Capabilities`: `can_be_player`, `is_controllable`, `is_observable`, `supports_transfer_command`, `supports_command_request`, `command_acks`, `supported_types[]`, `supports_playlist_v2`, `volume_steps`, `supports_gapless`, `supports_crossfade`, `supports_hifi`, `supported_audio_quality`, `supports_set_options_command`.

A device can register as a controller only (`can_be_player = false`, `is_controllable` and command requests), which lets it drive the active Spotify device over the backend protocol.

## `Cluster` and `ClusterUpdate`

`Cluster`: `active_device_id`, `player_state`, `device` map, `transfer_data`, `need_full_player_state`, `needs_state_updates`, `started_playing_at_timestamp`.

`ClusterUpdate`: `cluster`, `update_reason`, `ack_id`, `devices_that_changed[]`.

## `PlayerState`

`context_uri`, `context_url`, `play_origin`, `index`, `track` (`ProvidedTrack`), `position_as_of_timestamp`, `duration`, `is_playing`, `is_paused`, `is_buffering`, `options` (shuffle, repeat), `prev_tracks[]`, `next_tracks[]`, `queue_revision`, `playback_quality`, `playback_id`, `session_id`.

The same model is exposed by the Java `com.spotify.player.model.PlayerState` (`classes2.dex`).

## Open questions

Order of connection setup, `PutState` cadence, the exact framing of commands on the WebSocket, and acknowledgements. Spotifeur does not implement Connect.
