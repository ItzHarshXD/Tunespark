package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
 * Create playlist popup — pick a playlist name AND choose where the playlist lives:
 * an on-device **local playlist** (works signed out, never synced) or a
 * **YouTube Music** playlist (syncs with the signed-in account; requires sign-in).
 *
 * Shared by the Library grid's "+" box and the Quick Action sheet's "Add to
 * playlist" picker, so the exact same creation UX is offered from both entry
 * points. State resets fresh on every open (the dialog unmounts when hidden).
 */
@Composable
fun CreatePlaylistDialog(
    show: Boolean,
    isSignedIn: Boolean,
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, asLocal: Boolean) -> Unit
) {
    if (!show) return
    val context = LocalContext.current
    val view = LocalView.current
    val playSoundAndHaptic = {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    var nameDraft by remember { mutableStateOf("New playlist") }
    var asLocal by remember { mutableStateOf(!isSignedIn) }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val isDialogDark = backgroundColor == Color.Black

    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        modifier = Modifier.border(
            1.dp,
            if (isDialogDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f),
            RoundedCornerShape(28.dp)
        ),
        shape = RoundedCornerShape(28.dp),
        containerColor = if (isDialogDark) Color(0xFF1E1E22) else Color(0xFFF2F2F5),
        title = { Text(text = "Create playlist", color = textColor, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    placeholder = { Text("Playlist name", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF0000),
                        unfocusedBorderColor = textColor.copy(alpha = 0.4f),
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        cursorColor = Color(0xFFFF0000)
                    ),
                    modifier = Modifier.fillMaxWidth()
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
                            if (!isCreating) {
                                playSoundAndHaptic()
                                asLocal = true
                            }
                        }
                        .padding(12.dp)
                ) {
                    RadioButton(
                        selected = asLocal,
                        onClick = if (!isCreating) {
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
                        .clickable(enabled = isSignedIn && !isCreating) {
                            playSoundAndHaptic()
                            asLocal = false
                        }
                        .padding(12.dp)
                ) {
                    RadioButton(
                        selected = !asLocal,
                        enabled = isSignedIn,
                        onClick = if (isSignedIn && !isCreating) {
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
                            text = if (isSignedIn) "Syncs with your account." else "Sign in to sync playlists.",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isCreating,
                onClick = { onCreate(nameDraft, asLocal) }
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        color = Color(0xFFFF0000),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(text = "Create", color = Color(0xFFFF0000), fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!isCreating) onDismiss() }) {
                Text(text = "Cancel", color = textColor)
            }
        }
    )
}