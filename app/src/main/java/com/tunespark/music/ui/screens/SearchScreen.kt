package com.tunespark.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.tunespark.music.AppScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun SearchScreen(
    searchQuery: String,
    searchResults: List<SongItem>,
    isSearching: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onTriggerSearch: () -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
    }

    BackHandler {
        onNavigate(AppScreen.HOME)
    }

    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var localResults by remember { mutableStateOf<List<SongItem>>(searchResults) }
    var localIsSearching by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            suggestions = emptyList()
            localResults = emptyList()
            onSearchQueryChange("")
            return@LaunchedEffect
        }

        delay(300)
        localIsSearching = true

        try {
            val suggestionRes = withContext(Dispatchers.IO) {
                YouTube.searchSuggestions(searchQuery)
            }
            if (suggestionRes.isSuccess) {
                suggestions = suggestionRes.getOrNull()?.queries.orEmpty()
            }

            val resultsRes = withContext(Dispatchers.IO) {
                YouTube.search(
                    query = searchQuery,
                    filter = YouTube.SearchFilter.FILTER_SONG
                )
            }
            if (resultsRes.isSuccess) {
                val items = resultsRes.getOrNull()?.items.orEmpty()
                localResults = items.filterIsInstance<SongItem>()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            localIsSearching = false
        }
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(top = 16.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = {
                Text(
                    "Search songs, artists, albums...",
                    color = Color.Gray
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search Icon",
                    tint = textColor
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            onSearchQueryChange("")
                            focusRequester.requestFocus()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = textColor
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = textColor,
                unfocusedTextColor = textColor,
                focusedBorderColor = textColor,
                unfocusedBorderColor = textColor,
                focusedContainerColor = backgroundColor,
                unfocusedContainerColor = backgroundColor,
                cursorColor = textColor
            ),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .focusRequester(focusRequester)
        )

        if (localIsSearching && localResults.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = textColor,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (searchQuery.isNotEmpty() && suggestions.isNotEmpty()) {
                items(suggestions.take(3)) { suggestion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                focusManager.clearFocus()
                                onSearchQueryChange(suggestion)
                            }
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Suggestion",
                                tint = textColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = suggestion,
                                color = textColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                        Text(
                            text = "↗",
                            color = textColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            if (localResults.isNotEmpty()) {
                items(localResults) { song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPlaySong(song)
                                onNavigate(AppScreen.RADIO)
                            }
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = song.thumbnail,
                            contentDescription = "Artwork",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondary),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = song.title,
                                fontWeight = FontWeight.Normal,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = song.artists.joinToString(", ") { it.name },
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else if (!localIsSearching && searchQuery.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillParentMaxHeight()
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Explore millions of tracks. Type a query above!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}