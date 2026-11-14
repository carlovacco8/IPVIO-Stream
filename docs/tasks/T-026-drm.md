# T-026 DRM support for M3U playlists

**Goal:** play protected streams (mostly DASH .mpd) described by Kodi properties in M3U playlists.

**Affected files**
- `DrmLogic.kt` (new, pure Kotlin): reads `#KODIPROP:inputstream.adaptive.*` (license_type, license_key, drm_legacy, manifest_type, license_headers/stream_headers); ClearKey keys → JWK set; details travel with the address as the fragment `#ipvio-drm=…`.
- `DrmPlayback.kt` (new): MediaItem with `DrmConfiguration`; `DrmSessionManagerProvider` with a local ClearKey callback for `kid:key`, Media3 default otherwise (license requests through the app's HTTP factory).
- `Models.kt` (`M3uParser.entries`, `classify`): collects KODIPROP lines per entry; classification uses the address without the fragment.
- `PlayerScreen.kt` (`openStream`, player factory, loaded-address check), `DualView.kt` (factory).
- `TapLogic.tappable`: false for DRM streams (no live buffer, no recording).

**Storage:** no format change. `Item.url` carries the fragment; the cache stores strings unchanged. Old caches have no DRM details until the playlist is refreshed. Item IDs of DRM entries change once (they could not play before).

**Tests run:** `DrmLogicTest` (7) plus PlaylistLogicTest, EpisodeLogicTest, CatchupLogicTest, LoadLogicTest (34) compiled with kotlinc 2.0.21 and run with JUnit 4 outside Gradle: all passed. `DrmPlayback.kt` and the player changes were NOT compiled (no Android SDK).

**Device test steps**
1. Add an M3U with a ClearKey entry (`license_type=clearkey`, `license_key=<kid>:<key>`, .mpd address) → plays.
2. Widevine entry with license server (`license_type=com.widevine.alpha`, `license_key=https://…|User-Agent=…`) → plays (Fire TV / Android TV with Widevine L1/L3).
3. Normal .ts/.m3u8 channels, live buffer and recording still work.
4. Multi view with a DRM channel.

**Risks:** DASH ClearKey with only `default_KID` in the MPD depends on Media3's common PSSH handling (unverified on device). Catch-up URLs built from a DRM address keep the fragment (unverified).

**Status:** implemented, awaiting build and device test.
