package com.tunespark.music.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Cast SDK configuration.
 *
 * TuneSpark casts to Google's **Default Media Receiver** (the receiver every
 * Chromecast / Google TV / Cast-enabled speaker ships with), so no custom
 * receiver app has to be published or hosted. The receiver is handed a direct
 * audio stream URL plus track metadata by [CastManager].
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(DEFAULT_MEDIA_RECEIVER_ID)
            // Nothing should keep a stale session alive after the user
            // disconnects from the in-app device picker.
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        /** Google's public Default Media Receiver application id. */
        const val DEFAULT_MEDIA_RECEIVER_ID = "CC1AD845"
    }
}
