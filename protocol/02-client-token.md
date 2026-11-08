# ClientToken

Before any account login, the client obtains a **client token** for the installation. It identifies the app and the device, not the user. Every Login5 and spclient request carries it in the `client-token` header.

```text
POST https://clienttoken.spotify.com/v1/clienttoken
Accept: application/x-protobuf
Content-Type: application/x-protobuf
```

## Schema

Both descriptors are compiled into Orbit. `client_token_http.proto` (package `spotify.clienttoken.http.v0`) covers the exchange; its `FileDescriptorProto` occupies `[0x59bb94, 0x59c882)`. `client_token.proto` (`spotify.clienttoken.serialization.v0`) describes the stored token: `token`, `expires_at_timestamp`, `refresh_at_timestamp`, `domains`.

| Message | Fields |
| --- | --- |
| `ClientTokenRequest` | 1 `request_type` (enum), 2 `client_data` (`ClientDataRequest`), 3 `challenge_answers` (`ChallengeAnswersRequest`), 10 `client_data_derived_value` (bytes), 11 `client_data_derived_value_token` (bytes) |
| `ClientDataRequest` | 1 `client_version`, 2 `client_id`, 3 `connectivity_sdk_data`, `current_token`, `client_data_derived_value_version` |
| `ConnectivitySdkData` | 1 `platform_specific_data`, 2 `device_id` |
| `ChallengeAnswersRequest` | 1 `state`, 2 `answers[]` |
| `ClientTokenResponse` | 1 `response_type`, 2 `granted_token`, 3 `challenges` |
| `GrantedTokenResponse` | 1 `token` (string), 2 `expires_after_seconds` (int32), 3 `refresh_after_seconds` (int32), 4 `domains[]` (`TokenDomain`, field 1 = domain) |
| `ChallengesResponse` | 1 `state`, 2 `challenges[]` |
| `Challenge` | 1 `type`; oneof: `client_secret_parameters` (salt), `evaluate_js_parameters` (`code`, `libraries[]`), 4 `evaluate_hashcash_parameters` (1 `length`, 2 `prefix` as hex) |
| `ChallengeAnswer` | 1 `type`; oneof: `client_secret` (`hmac`), `evaluate_js` (`result`), 4 `hash_cash` (1 `suffix` as hex) |

`request_type` is 1 for a client data request and 2 for challenge answers. Challenge `type` 3 is Hashcash.

### Android device data

`PlatformSpecificData` → field 1 `NativeAndroidData`:

| Field | Meaning |
| --- | --- |
| 1 | `screen_dimensions`: 1 width px, 2 height px, 3 smallest width dp, 4 density dpi, 5 stable density dpi |
| 2 | `os_version` (`Build.VERSION.RELEASE`) |
| 3 | `api_level` (`Build.VERSION.SDK_INT`) |
| 4 | `device_name` (`Build.DEVICE`) |
| 5 | `model_str` (`Build.MODEL`) |
| 6 | `brand` |
| 7 | `manufacturer` |
| 8 | `volume_steps` (max music stream volume) |
| 10 | `installer_name`: the package that installed the app |
| | `device_data_ext`: opaque |

So every token request describes the device and where the app was installed from.

## Exchange

1. Send `request_type = 1` with `client_data` (version, client ID, device data, device ID).
2. If the server answers with `granted_token`, keep it. Otherwise it returns `challenges` with an opaque `state`.
3. Solve each challenge and send `request_type = 2` with `challenge_answers { state, answers[] }`.
4. Repeat at most a few times. A server that keeps renewing the challenge means the request is rejected.

Renew before `min(expires_after_seconds, refresh_after_seconds)`. Only attach the token to hosts in `domains`.

**Live:** a Hashcash challenge followed by a granted token was received with a fresh device ID, without any account.

## Hashcash

ClientToken and Login5 use the same primitive. ClientToken sends `prefix` as hex and expects the suffix as uppercase hex. Login5 sends raw bytes.

- The suffix is 16 bytes: an 8-byte seed + counter, then the 8-byte counter.
- The seed is the last 8 bytes of `SHA-1(context)`, read as a big-endian long. ClientToken uses an empty context. Login5 uses `login_context`.
- A suffix is valid when `SHA-1(prefix ‖ suffix)` has at least `length` trailing zero bits in its last 8 bytes.

This matches [librespot's solver](https://github.com/librespot-org/librespot/blob/dev/core/src/util.rs). Difficulty is low for ClientToken. A 24-bit test vector takes about 5 s on a recent phone with four threads. The server sends no expiry for the challenge, so a client-side timeout is only a local resource bound.

Hashcash only costs CPU time. It does not identify a device or tie the request to a signed app.

## Other challenge types

- **Client secret HMAC.** The client signs a server salt with a secret embedded in the app.
- **EvaluateJS.** The server sends JavaScript `code` and `libraries` and expects the exact `result`. The server can change the code at any time. No JavaScript engine was found in Orbit, and the DEX does not reference `androidx.javascriptengine`. A `WebView` with `evaluateJavascript` exists in the app, but no link to ClientToken was shown. Which component would execute this challenge on Android is unknown. Hashcash is what Android clients were observed to receive.

## Attestation and tamper checks

None of the following is part of the ClientToken Protobuf, but they surround it.

- **Play Integrity.** The DEX references `StandardIntegrityManager.prepareIntegrityToken`, `PrepareIntegrityTokenRequest{cloudProjectNumber=…}`, `requestExpressIntegrityToken`, `warmUpIntegrityToken` and `StandardIntegrityTokenRequest{requestHash=…}`. The module `com.spotify.integrity.integrityimpl` exposes `VerifyRequest`/`VerifyResponse` and `AndroidIntegrityProperties`. Natively, `AppIntegrityLoggerSetupImpl` and `app_integrity_delayed_request` report the signal later. The verdict is signed by Google and bound to the package name, signing certificate and cloud project number of the official app. Where exactly the verdict is attached to a token request was not confirmed at runtime.
- **Encrypted tokens.** `EsClientToken.EncryptedClientToken` shows the token can be stored encrypted.
- **DPoP.** `session_dpop_missing_nonce` and `DpopBoundAuthCodeInfo.pkce_code_challenge` show that some tokens are bound to a key by proof of possession. `ed25519PublicKeyFromSeed` places an Ed25519 key on the client. A captured DPoP-bound token cannot be replayed without that key.
- **Root and instrumentation checks.** `librootChecker.so`, the Ravelin SDK (`RootCheckerNative`, looking for `com.topjohnwu.magisk` and `eu.chainfire.supersu`) and `spotify::drm::collectFridaFeatures()` with the message `frida_detect: passive detection triggered (debug build, not enforced)`.

**Live:** at the time of writing, ClientToken with Hashcash, Login5 and the spclient services described here worked without a Play Integrity verdict.
