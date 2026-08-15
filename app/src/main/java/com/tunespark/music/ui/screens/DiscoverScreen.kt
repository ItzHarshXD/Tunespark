package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.shadow
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
import com.tunespark.music.ai.ArticleSummaryService
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
    onPlaySong: ((Any) -> Unit)? = null,
    // Pending AI summary article (set when user taps AI icon on Home carousel)
    pendingAiSummaryArticle: Article? = null,
    onPendingAiSummaryConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val accentColor = MaterialTheme.colorScheme.primary

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

    // AI summary state: set of expanded article URLs (multiple can be open),
    // generated summaries, loading state
    var expandedSummaryUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var summaryTexts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var summaryLoadingUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var summaryError by remember { mutableStateOf<String?>(null) }

    // LazyListState for auto-scrolling to a pending article
    val listState = rememberLazyListState()

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

    // Auto-scroll to and auto-generate summary for the pending article
    // (set when the user taps the AI icon on the Home screen carousel)
    LaunchedEffect(pendingAiSummaryArticle, discoverArticles) {
        val pending = pendingAiSummaryArticle ?: return@LaunchedEffect
        if (discoverArticles.isEmpty()) return@LaunchedEffect

        val targetIndex = discoverArticles.indexOfFirst { it.url == pending.url }
        if (targetIndex == -1) return@LaunchedEffect

        // Scroll to the article (with a small offset so it's nicely positioned)
        listState.animateScrollToItem(
            index = targetIndex + 1, // +1 for the "Today's Highlights" header item
            scrollOffset = -80
        )

        // Expand the summary and trigger generation
        expandedSummaryUrls = expandedSummaryUrls + pending.url
        if (summaryTexts[pending.url] == null && pending.url !in summaryLoadingUrls) {
            summaryLoadingUrls = summaryLoadingUrls + pending.url
            summaryError = null
            coroutineScope.launch {
                try {
                    val summary = withContext(Dispatchers.IO) {
                        ArticleSummaryService.getOrGenerateSummary(
                            context = context,
                            articleUrl = pending.url,
                            articleTitle = pending.title
                        )
                    }
                    summaryTexts = summaryTexts + (pending.url to summary)
                } catch (e: Exception) {
                    summaryError = e.message ?: "Failed to generate summary"
                } finally {
                    summaryLoadingUrls = summaryLoadingUrls - pending.url
                }
            }
        }

        // Consume the pending article so it doesn't re-trigger
        onPendingAiSummaryConsumed()
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
                    state = listState,
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
                            ArticleCardWithSummary(
                                article = article,
                                textColor = textColor,
                                accentColor = accentColor,
                                expandedSummaryUrls = expandedSummaryUrls,
                                summaryTexts = summaryTexts,
                                summaryLoadingUrls = summaryLoadingUrls,
                                summaryError = summaryError,
                                onToggleSummary = { url ->
                                    playSoundAndHaptic()
                                    expandedSummaryUrls = if (url in expandedSummaryUrls) {
                                        expandedSummaryUrls - url
                                    } else {
                                        expandedSummaryUrls + url
                                    }
                                    if (summaryTexts[url] == null && url !in summaryLoadingUrls) {
                                        summaryLoadingUrls = summaryLoadingUrls + url
                                        summaryError = null
                                        coroutineScope.launch {
                                            try {
                                                val summary = withContext(Dispatchers.IO) {
                                                    ArticleSummaryService.getOrGenerateSummary(
                                                        context = context,
                                                        articleUrl = url,
                                                        articleTitle = article.title
                                                    )
                                                }
                                                summaryTexts = summaryTexts + (url to summary)
                                            } catch (e: Exception) {
                                                summaryError = e.message ?: "Failed to generate summary"
                                            } finally {
                                                summaryLoadingUrls = summaryLoadingUrls - url
                                            }
                                        }
                                    }
                                },
                                onOpenArticle = {
                                    playSoundAndHaptic()
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(article.url)
                                    )
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleCardWithSummary(
    article: Article,
    textColor: Color,
    accentColor: Color,
    expandedSummaryUrls: Set<String>,
    summaryTexts: Map<String, String>,
    summaryLoadingUrls: Set<String>,
    summaryError: String?,
    onToggleSummary: (String) -> Unit,
    onOpenArticle: () -> Unit
) {
    val isExpanded = article.url in expandedSummaryUrls
    val summary = summaryTexts[article.url]
    val isLoading = article.url in summaryLoadingUrls
    val isUnscraped = summary == ArticleSummaryService.UNABLE_TO_GET_DATA

    // Parse bulleted summary text into individual bullet lines
    val summaryBullets = remember(summary) {
        summary?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { line ->
                line.removePrefix("-").trim().takeIf { it.isNotEmpty() }
            }
            .orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onOpenArticle() }
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

        // Padding for all content below the image so it sits nicely inside the card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            // Headline row: title + AI icon button (dedicated space, doesn't affect layout)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = article.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                val isDarkTheme = MaterialTheme.colorScheme.background == Color.Black
                val weatherBgColor = if (isDarkTheme) Color(0xFF16161A) else Color(0xFFF2F2F5)
                val weatherTextColor = if (isDarkTheme) Color.White else Color.Black
                val weatherBorderColor = if (isDarkTheme) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f)

                // AI summary icon button styled matching the weather widget / home screen AI button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .shadow(elevation = 6.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .background(weatherBgColor)
                        .border(1.dp, weatherBorderColor, CircleShape)
                        .clickable { onToggleSummary(article.url) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = weatherTextColor
                        )
                    } else {
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
                            contentDescription = "Generate AI summary",
                            tint = weatherTextColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${article.source} • ${article.timeAgo()}",
                fontSize = 13.sp,
                color = textColor.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Expandable AI summary section - attached to the article tile,
            // no separate boxed block, just flows naturally below the meta line.
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                if (isLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = accentColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Generating summary...",
                            fontSize = 13.sp,
                            color = textColor.copy(alpha = 0.6f)
                        )
                    }
                } else if (isUnscraped) {
                    // Friendly fallback when the article page couldn't be scraped
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⚠️", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Unable to get data for this article.",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "We couldn't read this article's page. You can still open it directly.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = textColor.copy(alpha = 0.65f)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Open Article button - solid accent, full clickable area
                        Button(
                            onClick = { onOpenArticle() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                            modifier = Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            Text(
                                text = "Open Article",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else if (summary != null) {
                    // Render the summary as a quick takeaway line + scannable points
                    if (summaryBullets.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            summaryBullets.forEachIndexed { index, bullet ->
                                if (index == 0) {
                                    // First line: the quick takeaway, no bullet marker
                                    Text(
                                        text = bullet,
                                        fontSize = 15.sp,
                                        lineHeight = 21.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = textColor
                                    )
                                } else {
                                    // Remaining lines: short points with a subtle marker
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 2.dp)
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(accentColor)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = bullet,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            color = textColor,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Fallback: plain text if no bullets were detected
                        Text(
                            text = summary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = textColor
                        )
                    }
                } else if (summaryError != null && article.url in expandedSummaryUrls) {
                    Text(
                        text = summaryError,
                        fontSize = 13.sp,
                        color = Color(0xFFE53935)
                    )
                }
                }
            }
        }
    }
}
