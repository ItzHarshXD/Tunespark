package com.tunespark.music.ui.screens

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AccountInfo
import com.tunespark.music.AppScreen
import com.tunespark.music.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AccountScreen(
    accountInfo: AccountInfo?,
    isLoadingProfile: Boolean,
    profileError: String?,
    onAccountInfoChange: (AccountInfo?) -> Unit,
    onIsLoadingProfileChange: (Boolean) -> Unit,
    onProfileErrorChange: (String?) -> Unit,
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var showWebView by remember { mutableStateOf(false) }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val onSecondaryColor = MaterialTheme.colorScheme.onSecondary

    if (showWebView) {
        YouTubeSignInWebView(
            onCookieExtracted = { cookies ->
                SessionManager.saveCookie(context, cookies)
                onIsLoadingProfileChange(true)
                onProfileErrorChange(null)
                coroutineScope.launch(Dispatchers.IO) {
                    val result = YouTube.accountInfo()
                    withContext(Dispatchers.Main) {
                        onIsLoadingProfileChange(false)
                        if (result.isSuccess) {
                            val info = result.getOrNull()
                            if (info != null) {
                                onAccountInfoChange(info)
                                SessionManager.saveAccountInfo(context, info)
                            }
                        } else {
                            onProfileErrorChange("Authentication failed. Please sign in again.")
                        }
                    }
                }
                showWebView = false
            },
            onCancel = {
                showWebView = false
            }
        )
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(backgroundColor)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.Start
        ) {
            SettingsHeader(title = "Account", onBack = { onNavigate(AppScreen.SETTINGS) })

            if (profileError != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = profileError,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "✕",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { onProfileErrorChange(null) }
                                .padding(horizontal = 8.dp)
                        )
                    }
                }
            }

            if (isLoadingProfile) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFFFF0000))
                }
            } else {
                val info = accountInfo
                if (info != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Colored avatar with initials
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .background(Color(0xFFFF0000), shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = info.name.take(1).uppercase(),
                                color = Color.White,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = info.name,
                            color = textColor,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (!info.email.isNullOrEmpty()) {
                            Text(
                                text = info.email ?: "",
                                color = Color.Gray,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        if (!info.channelHandle.isNullOrEmpty()) {
                            Text(
                                text = info.channelHandle ?: "",
                                color = Color.Gray,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = secondaryColor)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "Subscription Status",
                                    color = Color.Gray,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Linked to YouTube Music via active session",
                                    color = onSecondaryColor,
                                    fontSize = 16.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = {
                                SessionManager.clearSession(context)
                                onAccountInfoChange(null)
                                CookieManager.getInstance().removeAllCookies(null)
                                CookieManager.getInstance().flush()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                            shape = RoundedCornerShape(30.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                        ) {
                            Text("Sign Out", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        
                        Spacer(modifier = Modifier.height(110.dp))
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(32.dp))

                        // Placeholder Avatar Icon
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .background(secondaryColor, shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "👤",
                                fontSize = 40.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Unlock Your Personal Library",
                            color = textColor,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Sign in to YouTube Music optionally to stream your liked songs, custom playlists, listening history, and enjoy tailored recommended feeds.",
                            color = Color.Gray,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = { showWebView = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                            shape = RoundedCornerShape(30.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                        ) {
                            Text("Sign In with YouTube Music", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        
                        Spacer(modifier = Modifier.height(110.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun YouTubeSignInWebView(
    onCookieExtracted: (String) -> Unit,
    onCancel: () -> Unit
) {
    var webPageLoading by remember { mutableStateOf(true) }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val onSecondaryColor = MaterialTheme.colorScheme.onSecondary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Premium, Trustworthy Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(secondaryColor)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFFF0000), shape = CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Cancel and Back",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = "Sign In",
                    color = onSecondaryColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🔒 accounts.google.com",
                        color = Color(0xFF4CAF50), // Secure Green
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }

        // Web page loading indicator
        if (webPageLoading) {
            LinearProgressIndicator(
                color = Color(0xFFFF0000),
                trackColor = backgroundColor,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            HorizontalDivider(color = secondaryColor, thickness = 1.dp)
        }

        // Native Secure Web Container
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            webPageLoading = true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            webPageLoading = false
                            if (url != null && (url.contains("music.youtube.com") || url.contains("youtube.com"))) {
                                val cookieManager = CookieManager.getInstance()
                                val cookies = cookieManager.getCookie("https://music.youtube.com")
                                if (cookies != null && cookies.contains("SAPISID")) {
                                    onCookieExtracted(cookies)
                                }
                            }
                        }
                    }
                    loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https://music.youtube.com/")
                }
            },
            modifier = Modifier.weight(1f)
        )
    }
}
