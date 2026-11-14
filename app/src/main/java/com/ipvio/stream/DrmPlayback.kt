package com.ipvio.stream

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Plays streams with DRM details from the playlist ([DrmLogic]): Widevine, PlayReady and ClearKey
 * (with license server or with local keys "kid:key").
 */
object DrmPlayback {
    /** HTTP headers of the stream being opened (from the playlist), added to every request of the stream. */
    @Volatile private var streamHeaders: Map<String, String> = emptyMap()

    /** DRM details of the last opened protected stream, for the diagnostics page (no keys, no address). */
    @Volatile var last: String? = null
        private set

    /**
     * Turns an address with DRM details into a MediaItem with DRM configuration (keeps subtitles etc. of [item]).
     * Addresses without DRM details are returned unchanged.
     */
    fun prepare(item: MediaItem): MediaItem {
        val uri = item.localConfiguration?.uri?.toString() ?: return item
        val info = DrmLogic.of(uri)
        streamHeaders = info?.streamHeaders ?: emptyMap()
        if (info == null) return item
        last = DrmLogic.describe(info)
        val b = item.buildUpon().setUri(DrmLogic.base(uri)).setTag(info)
        when (info.mime) {
            "mpd" -> b.setMimeType(MimeTypes.APPLICATION_MPD)
            "hls" -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        uuid(info.type)?.let { id ->
            val d = MediaItem.DrmConfiguration.Builder(id)
                .setLicenseRequestHeaders(info.headers)
                // Live channels often rotate keys: always ask for fresh licenses
                .setMultiSession(true)
            info.licenseUrl?.let { d.setLicenseUri(it) }
            b.setDrmConfiguration(d.build())
        }
        return b.build()
    }

    private fun uuid(t: DrmLogic.Type?) = when (t) {
        DrmLogic.Type.CLEARKEY -> C.CLEARKEY_UUID
        DrmLogic.Type.WIDEVINE -> C.WIDEVINE_UUID
        DrmLogic.Type.PLAYREADY -> C.PLAYREADY_UUID
        null -> null
    }

    /**
     * DRM sessions for the player: ClearKey with local keys answers the license request itself (no server);
     * everything else goes through Media3's standard path (license server via the app's HTTP settings).
     */
    fun provider(http: HttpDataSource.Factory): DrmSessionManagerProvider {
        val std = DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(http) }
        return DrmSessionManagerProvider { mi: MediaItem ->
            val info = mi.localConfiguration?.tag as? DrmLogic.Info
            val keys = info?.clearKeys
            if (info?.type == DrmLogic.Type.CLEARKEY && keys != null) localClearKey(keys) else std.get(mi)
        }
    }

    private fun localClearKey(json: String): DrmSessionManager =
        DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(true)
            .build(LocalMediaDrmCallback(json.toByteArray(Charsets.UTF_8)))

    /** Media source factory with DRM support (used by the main player and the multi view). */
    fun factory(ctx: android.content.Context, data: DataSource.Factory, http: HttpDataSource.Factory): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(ctx)
            .setDataSourceFactory(ResolvingDataSource.Factory(data) { spec ->
                val h = streamHeaders
                if (h.isEmpty()) spec else spec.withAdditionalHeaders(h)
            })
            .setDrmSessionManagerProvider(provider(http))
}
