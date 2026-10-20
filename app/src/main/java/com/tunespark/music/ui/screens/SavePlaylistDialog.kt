package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Save-playlist popup — pick where a YouTube Music / community playlist should be
 * saved: an on-device **local playlist** (always available, works signed out, never
 * synced) or **YouTube Music** (adds it to the signed-in account's library; disabled
 * when signed out). Mirrors [CreatePlaylistDialog]'s styling.
 *
 * Shared by the Home community playlist card and the playlist detail overflow menu.
 * State resets fresh on every open (the dialog unmounts when hidden).
 */
@Composable
fun SavePlaylistDialog(
    show: Boolean,
    playlistName: String,
    isSignedIn: Boolean,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (asLocal: Boolean) -> Unit
) {
    if (!show) return
    val context = LocalContext.current
    val view = LocalView.current
    val playSoundAndHaptic = {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    var asLocal by remember { mutableStateOf(!isSignedIn) }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val isDialogDark = backgroundColor == Color.Black

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        modifier = Modifier.border(
            1.dp,
            if (isDialogDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f),
            RoundedCornerShape(28.dp)
        ),
        shape = RoundedCornerShape(28.dp),
        containerColor = if (isDialogDark) Color(0xFF1E1E22) else Color(0xFFF2F2F5),
        title = { Text(text = "Save playlist", color = textColor, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = playlistName.ifBlank { "This playlist" },
                    color = Color.Gray,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(text = "Save to", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)

                Spacer(modifier = Modifier.height(8.dp))

                // Local playlist option — always available, never synced.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Gray.copy(alpha = if (asLocal) 0.25f else 0.12f))
                        .clickable {
                            if (!isSaving) {
                                playSoundAndHaptic()
                                asLocal = true
                            }
                        }
                        .padding(12.dp)
                ) {
                    RadioButton(
                        selected = asLocal,
                        onClick = if (!isSaving) {
                            {
                                playSoundAndHaptic()
                                asLocal = true
                            }
                        } else null,
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF0000))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Local playlist", color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(text = "Stored on this device. Never synced.", color = Color.Gray, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // YouTube Music option — needs a signed-in account.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Gray.copy(alpha = if (!asLocal) 0.25f else 0.12f))
                        .clickable(enabled = isSignedIn && !isSaving) {
                            playSoundAndHaptic()
                            asLocal = false
                        }
                        .padding(12.dp)
                ) {
                    RadioButton(
                        selected = !asLocal,
                        enabled = isSignedIn,
                        onClick = if (isSignedIn && !isSaving) {
                            {
                                playSoundAndHaptic()
                                asLocal = false
                            }
                        } else null,
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF0000))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "YouTube Music",
                            color = if (isSignedIn) textColor else textColor.copy(alpha = 0.45f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isSignedIn) "Adds it to your account's library." else "Sign in to save to your account.",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving,
                onClick = { onSave(asLocal) }
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        color = Color(0xFFFF0000),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(text = "Save", color = Color(0xFFFF0000), fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!isSaving) onDismiss() }) {
                Text(text = "Cancel", color = textColor)
            }
        }
    )
}
