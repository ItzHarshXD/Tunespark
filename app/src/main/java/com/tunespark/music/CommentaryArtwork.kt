package com.tunespark.music

import android.content.Context
import android.net.Uri

/**
 * Resolves one of the companion commentary thumbnails (dropped by the app into
 * `app/src/main/res/drawable/`) to an `android.resource://` URI that Coil
 * ([androidx.compose]'s AsyncImage) and Media3's `MediaMetadata.artworkUri`
 * can render on the Radio disc, song thumbnail, and Up Next queue rows.
 *
 * The resource is looked up by its drawable file name at runtime (e.g.
 * `"session_opener"` for `session_opener.png`), so the code stays safe even if
 * a particular PNG hasn't been added yet — it returns null and the UI simply
 * falls back to the default commentary icon.
 */
fun commentaryArtworkUri(context: Context, drawableName: String): Uri? {
    val resId = context.resources.getIdentifier(drawableName, "drawable", context.packageName)
    return if (resId != 0) Uri.parse("android.resource://${context.packageName}/$resId") else null
}