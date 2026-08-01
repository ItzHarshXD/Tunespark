package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tunespark.music.AppScreen
import com.tunespark.music.rss.Article
import com.tunespark.music.rss.RssRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier,
    // Add dummy callback just in case but we don't have to use it
    onPlaySong: ((Any) -> Unit)? = null
) {
    val context = LocalContext.current
    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground

    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val view = LocalView.current

    val playSoundAndHaptic = {
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // Real RSS-powered articles loaded from the repository
    var discoverArticles by remember { mutableStateOf<List<Article>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    suspend fun loadArticles(forceRefresh: Boolean = false) {
        val articles = withContext(Dispatchers.IO) {
            RssRepository.getArticles(context, forceRefresh)
        }
        discoverArticles = articles
        isLoading = false
        isRefreshing = false
    }

    LaunchedEffect(Unit) {
        isLoading = true
        coroutineScope.launch {
            loadArticles()
        }
    }

    BackHandler {
        onNavigate(AppScreen.HOME)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = backgroundColor,
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        playSoundAndHaptic()
                        onNavigate(AppScreen.HOME)
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(textColor, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = backgroundColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = "Discover",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )

                // Top right button to take user to Discover Feed Settings Screen
                IconButton(
                    onClick = {
                        playSoundAndHaptic()
                        onNavigate(AppScreen.DISCOVER_FEED)
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Discover Feed Settings",
                        tint = textColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    coroutineScope.launch {
                        loadArticles(forceRefresh = true)
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            text = "Today's Highlights",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    if (isLoading) {
                        // Loading skeleton items - full-width rectangle cards
                        items(6) { index ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.Gray.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("📰", fontSize = 32.sp)
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.8f)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Gray.copy(alpha = 0.2f))
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.5f)
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Gray.copy(alpha = 0.15f))
                                )
                            }
                        }
                    } else if (discoverArticles.isEmpty()) {
                        item {
                            Text(
                                text = "No articles available. Please check your Discover feed settings.",
                                fontSize = 15.sp,
                                color = textColor.copy(alpha = 0.55f),
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    } else {
                        items(discoverArticles) { article ->
                            // Full-width rectangle card (Google Discover style)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        playSoundAndHaptic()
                                        // Open the article URL in the browser
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(article.url)
                                        )
                                        context.startActivity(intent)
                                    }
                            ) {
                                // Big rectangle image on top
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.Gray.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (article.thumbnail.isNotEmpty()) {
                                        AsyncImage(
                                            model = article.thumbnail,
                                            contentDescription = article.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text("📰", fontSize = 32.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Text below the image - full headline visible
                                Text(
                                    text = article.title,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColor
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "${article.source} • ${article.timeAgo()}",
                                    fontSize = 13.sp,
                                    color = textColor.copy(alpha = 0.55f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}