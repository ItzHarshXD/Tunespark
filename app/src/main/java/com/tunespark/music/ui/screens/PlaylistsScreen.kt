package com.tunespark.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Context
import android.widget.Toast
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.stylusHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.*
import com.metrolist.innertube.models.response.*
import com.tunespark.music.AppScreen
import com.tunespark.music.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.ktor.client.call.body

data class LibraryGridItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String? = null,
    val isLiked: Boolean = false,
    val rawItem: YTItem? = null,
    val authorName: String? = null,
    val authorAvatarUrl: String? = null
)

@Composable
fun PlaylistsScreen(
    initialPlaylistId: String? = null,
    initialPlaylistName: String? = null,
    initialPlaylistThumbnail: String? = null,
    initialPlaylistSongCountText: String? = null,
    initialPlaylistIsLiked: Boolean = false,
    initialPlaylistRawItem: YTItem? = null,
    initialPlaylistAuthorName: String? = null,
    initialPlaylistAuthorAvatarUrl: String? = null,
    initialPlaylistSongs: List<SongItem> = emptyList(),
    onPlayPlaylist: (String, List<SongItem>, Int) -> Unit,
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary

    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val view = LocalView.current

    val playSoundAndHaptic = {
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    var selectedTab by remember { mutableStateOf("Playlists") }

    var isRefreshing by remember { mutableStateOf(false) }
    var playlistsRefreshTrigger by remember { mutableStateOf(0) }

    val handleRefresh = {
        isRefreshing = true
        coroutineScope.launch {
            playlistsRefreshTrigger++
            delay(1500)
            isRefreshing = false
        }
    }

    // Sorting parameters persistence
    val sharedPrefs = remember { context.getSharedPreferences("tunespark_playlists_prefs", Context.MODE_PRIVATE) }
    var sortBy by remember {
        mutableStateOf(sharedPrefs.getString("sort_by", "Date added") ?: "Date added")
    }
    var sortAscending by remember {
        mutableStateOf(sharedPrefs.getBoolean("sort_ascending", false))
    } // False -> descending (↓), True -> ascending (↑)
    var sortMenuExpanded by remember { mutableStateOf(false) }

    // Search parameters
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    var activePlaylistId by remember { mutableStateOf(initialPlaylistId) }
    var activePlaylistName by remember { mutableStateOf(initialPlaylistName ?: "") }
    var activePlaylistThumbnail by remember { mutableStateOf(initialPlaylistThumbnail) }
    var activePlaylistSongCountText by remember { mutableStateOf(initialPlaylistSongCountText ?: "") }
    var activePlaylistIsLiked by remember { mutableStateOf(initialPlaylistIsLiked) }
    var activePlaylistRawItem by remember { mutableStateOf(initialPlaylistRawItem) }
    var activePlaylistAuthorName by remember { mutableStateOf(initialPlaylistAuthorName) }
    var activePlaylistAuthorAvatarUrl by remember { mutableStateOf(initialPlaylistAuthorAvatarUrl) }

    var playlistSongs by remember { mutableStateOf(initialPlaylistSongs) }
    var isSongsLoading by remember { mutableStateOf(false) }

    BackHandler {
        playSoundAndHaptic()
        if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else if (activePlaylistId != null) {
            activePlaylistId = null
            playlistSongs = emptyList()
        } else {
            onNavigate(AppScreen.HOME)
        }
    }

    var gridItems by remember { mutableStateOf<List<LibraryGridItem>>(emptyList()) }
    var isLoadingGrid by remember { mutableStateOf(false) }

    val fallbackPlaylists = remember {
        listOf(
            LibraryGridItem(id = "LM", title = "Liked", subtitle = "Your favorites", isLiked = true),
            LibraryGridItem(id = "PL4fGSI1pDJn5kI81J1fYxT5m68Sg_w60R", title = "Today's Hits", subtitle = "Global Hits", thumbnailUrl = "https://images.unsplash.com/photo-1501386761578-eac5c94b800a?q=80&w=300&auto=format&fit=crop"),
            LibraryGridItem(id = "PL4fGSI1pDJn6aHyS3qHcQz3nldSok0aH4", title = "Chill Hits", subtitle = "Relaxing Pop", thumbnailUrl = "https://images.unsplash.com/photo-1494232410401-ad00d5433cfa?q=80&w=300&auto=format&fit=crop"),
            LibraryGridItem(id = "PL4fGSI1pDJn5kS967H6_988L07-7TfF0n", title = "Lo-Fi Beats", subtitle = "Study & Focus", thumbnailUrl = "https://images.unsplash.com/photo-1518609878373-06d740f60d8b?q=80&w=300&auto=format&fit=crop"),
            LibraryGridItem(id = "PL4fGSI1pDJn7_07H6yv7zZ-H_W-tZfE0P", title = "Workout Energy", subtitle = "Upbeat Tracks", thumbnailUrl = "https://images.unsplash.com/photo-1517838277536-f5f99be501cd?q=80&w=300&auto=format&fit=crop"),
            LibraryGridItem(id = "PL4fGSI1pDJn6V7_S9X_t1Fm_WzP_YkYfD", title = "Party Starter", subtitle = "Dance Beats", thumbnailUrl = "https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?q=80&w=300&auto=format&fit=crop")
        )
    }

    val fallbackAlbums = remember {
        listOf(
            LibraryGridItem(id = "album_1", title = "Trending Mix", subtitle = "10 songs"),
            LibraryGridItem(id = "album_2", title = "Popular Hits", subtitle = "10 songs"),
            LibraryGridItem(id = "album_3", title = "Chill Vibes", subtitle = "10 songs")
        )
    }

    val fallbackArtists = remember {
        listOf(
            LibraryGridItem(id = "artist_1", title = "The Weeknd", subtitle = "Artist"),
            LibraryGridItem(id = "artist_2", title = "Ed Sheeran", subtitle = "Artist"),
            LibraryGridItem(id = "artist_3", title = "Dua Lipa", subtitle = "Artist")
        )
    }

    val realPlayableSongs = remember {
        listOf(
            SongItem("4NRXx6U8ABQ", "Blinding Lights", listOf(Artist("The Weeknd", null)), thumbnail = "https://img.youtube.com/vi/4NRXx6U8ABQ/0.jpg"),
            SongItem("JGwWNGJdvx8", "Shape of You", listOf(Artist("Ed Sheeran", null)), thumbnail = "https://img.youtube.com/vi/JGwWNGJdvx8/0.jpg"),
            SongItem("kTJczUoc5G4", "Stay", listOf(Artist("The Kid LAROI", null)), thumbnail = "https://img.youtube.com/vi/kTJczUoc5G4/0.jpg"),
            SongItem("H5v3kku4y6Q", "As It Was", listOf(Artist("Harry Styles", null)), thumbnail = "https://img.youtube.com/vi/H5v3kku4y6Q/0.jpg"),
            SongItem("34Na4j8AVgA", "Starboy", listOf(Artist("The Weeknd", null)), thumbnail = "https://img.youtube.com/vi/34Na4j8AVgA/0.jpg"),
            SongItem("G7KNmW9a75Y", "Flowers", listOf(Artist("Miley Cyrus", null)), thumbnail = "https://img.youtube.com/vi/G7KNmW9a75Y/0.jpg"),
            SongItem("TUVcZfQe-Kw", "Levitating", listOf(Artist("Dua Lipa", null)), thumbnail = "https://img.youtube.com/vi/TUVcZfQe-Kw/0.jpg"),
            SongItem("7wtfhZwyrcc", "Believer", listOf(Artist("Imagine Dragons", null)), thumbnail = "https://img.youtube.com/vi/7wtfhZwyrcc/0.jpg"),
            SongItem("2Vv-BfVoq4g", "Perfect", listOf(Artist("Ed Sheeran", null)), thumbnail = "https://img.youtube.com/vi/2Vv-BfVoq4g/0.jpg"),
            SongItem("zABLecsR5UE", "Someone You Loved", listOf(Artist("Lewis Capaldi", null)), thumbnail = "https://img.youtube.com/vi/zABLecsR5UE/0.jpg")
        )
    }

    val generatePlaylistSongs = { count: Int ->
        if (count <= 0) {
            emptyList<SongItem>()
        } else {
            val songs = mutableListOf<SongItem>()
            for (i in 0 until count) {
                val baseSong = realPlayableSongs[i % realPlayableSongs.size]
                val uniqueId = if (i >= realPlayableSongs.size) "${baseSong.id}_$i" else baseSong.id
                songs.add(SongItem(id = uniqueId, title = baseSong.title, artists = baseSong.artists, thumbnail = baseSong.thumbnail))
            }
            songs
        }
    }

    LaunchedEffect(selectedTab, playlistsRefreshTrigger) {
        val userSignedIn = SessionManager.isUserSignedIn(context)
        if (!userSignedIn) {
            gridItems = when (selectedTab) {
                "Playlists" -> fallbackPlaylists
                "Albums" -> fallbackAlbums
                else -> fallbackArtists
            }
            return@LaunchedEffect
        }

        isLoadingGrid = true
        coroutineScope.launch(Dispatchers.IO) {
            val browseId = when (selectedTab) {
                "Playlists" -> "FEmusic_liked_playlists"
                "Albums" -> "FEmusic_library_detail_albums"
                else -> "FEmusic_library_detail_artists"
            }

            try {
                // Instantiating InnerTube with the signed-in user's cookies
                val innerTube = com.metrolist.innertube.InnerTube().apply {
                    cookie = com.metrolist.innertube.YouTube.cookie
                }
                
                val response = innerTube.browse(
                    client = com.metrolist.innertube.models.YouTubeClient.WEB_REMIX,
                    browseId = browseId,
                    setLogin = true
                ).body<com.metrolist.innertube.models.response.BrowseResponse>()

                val sectionContents = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents
                    ?: response.contents?.sectionListRenderer?.contents
                    ?: response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents
                    ?: response.continuationContents?.sectionListContinuation?.contents

                val fetchedItems = mutableListOf<LibraryGridItem>()
                if (selectedTab == "Playlists") {
                    fetchedItems.add(LibraryGridItem(id = "LM", title = "Liked", subtitle = "Your liked songs", isLiked = true))
                }

                sectionContents?.forEach { content ->
                    // 1. Check for gridRenderer (common for album/artist detail grid view)
                    content.gridRenderer?.items?.forEach { gridItem ->
                        val renderer = gridItem.musicTwoRowItemRenderer
                        if (renderer != null) {
                            val isItemAlbum = renderer.navigationEndpoint.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_ALBUM" ||
                                              renderer.navigationEndpoint.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_AUDIOBOOK"
                            val isItemArtist = renderer.navigationEndpoint.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_ARTIST" ||
                                               renderer.navigationEndpoint.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_LIBRARY_ARTIST" ||
                                               renderer.navigationEndpoint.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL"

                            if (selectedTab == "Albums" && isItemAlbum) {
                                val bId = renderer.navigationEndpoint.browseEndpoint?.browseId
                                val pId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                                    ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchPlaylistEndpoint?.playlistId
                                    ?: renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                                    ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.playlistId
                                    ?: renderer.navigationEndpoint.watchPlaylistEndpoint?.playlistId
                                    ?: renderer.navigationEndpoint.watchEndpoint?.playlistId
                                    ?: bId?.removePrefix("MPREb_")
                                if (bId != null && pId != null) {
                                    val title = renderer.title.runs?.firstOrNull()?.text ?: ""
                                    val subtitle = renderer.subtitle?.runs?.joinToString("") { it.text } ?: "Album"
                                    val thumb = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl()
                                    val albumItem = AlbumItem(
                                        browseId = bId,
                                        playlistId = pId,
                                        title = title,
                                        artists = null,
                                        thumbnail = thumb ?: ""
                                    )
                                    fetchedItems.add(LibraryGridItem(id = bId, title = title, subtitle = subtitle, thumbnailUrl = thumb, rawItem = albumItem))
                                }
                            } else if (selectedTab == "Artists" && isItemArtist) {
                                val bId = renderer.navigationEndpoint.browseEndpoint?.browseId
                                if (bId != null) {
                                    val title = renderer.title.runs?.firstOrNull()?.text ?: ""
                                    val thumb = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl()
                                    val artistItem = ArtistItem(
                                        id = bId,
                                        title = title,
                                        thumbnail = thumb,
                                        shuffleEndpoint = null,
                                        radioEndpoint = null
                                    )
                                    fetchedItems.add(LibraryGridItem(id = bId, title = title, subtitle = "Artist", thumbnailUrl = thumb, rawItem = artistItem))
                                }
                            }
                        }
                    }

                    // 2. Check for musicShelfRenderer (common for list views)
                    content.musicShelfRenderer?.contents?.forEach { shelfContent ->
                        val renderer = shelfContent.musicResponsiveListItemRenderer
                        if (renderer != null) {
                            val pageType = renderer.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                            val flexPageType = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                                ?.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType

                            val isItemAlbum = pageType == "MUSIC_PAGE_TYPE_ALBUM" || pageType == "MUSIC_PAGE_TYPE_AUDIOBOOK" ||
                                              flexPageType == "MUSIC_PAGE_TYPE_ALBUM" || flexPageType == "MUSIC_PAGE_TYPE_AUDIOBOOK"
                            val isItemArtist = pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_LIBRARY_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" ||
                                               flexPageType == "MUSIC_PAGE_TYPE_ARTIST" || flexPageType == "MUSIC_PAGE_TYPE_LIBRARY_ARTIST" || flexPageType == "MUSIC_PAGE_TYPE_USER_CHANNEL"

                            val bId = renderer.navigationEndpoint?.browseEndpoint?.browseId
                                ?: renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.navigationEndpoint?.browseEndpoint?.browseId

                            if (selectedTab == "Albums" && isItemAlbum && bId != null) {
                                val pId = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content
                                    ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchPlaylistEndpoint?.playlistId
                                    ?: renderer.overlay?.musicItemThumbnailOverlayRenderer?.content
                                    ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.playlistId
                                    ?: renderer.navigationEndpoint?.watchPlaylistEndpoint?.playlistId
                                    ?: renderer.navigationEndpoint?.watchEndpoint?.playlistId
                                    ?: renderer.menu?.menuRenderer?.items?.firstNotNullOfOrNull {
                                        it.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint?.playlistId
                                        ?: it.menuNavigationItemRenderer?.navigationEndpoint?.watchEndpoint?.playlistId
                                    }
                                    ?: bId.removePrefix("MPREb_")

                                val title = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text ?: ""
                                val subtitle = renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.joinToString("") { it.text } ?: "Album"
                                val thumb = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                                val albumItem = AlbumItem(
                                    browseId = bId,
                                    playlistId = pId,
                                    title = title,
                                    artists = null,
                                    thumbnail = thumb ?: ""
                                )
                                fetchedItems.add(LibraryGridItem(id = bId, title = title, subtitle = subtitle, thumbnailUrl = thumb, rawItem = albumItem))
                            } else if (selectedTab == "Artists" && isItemArtist && bId != null) {
                                val title = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text ?: ""
                                val thumb = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                                val artistItem = ArtistItem(
                                    id = bId,
                                    title = title,
                                    thumbnail = thumb,
                                    shuffleEndpoint = null,
                                    radioEndpoint = null
                                )
                                fetchedItems.add(LibraryGridItem(id = bId, title = title, subtitle = "Artist", thumbnailUrl = thumb, rawItem = artistItem))
                            }
                        }
                    }
                }

                // If selectedTab is Playlists, let's also fetch playlists via YouTube.library as it already works perfectly!
                if (selectedTab == "Playlists") {
                    val playlistsResult = YouTube.library(browseId)
                    if (playlistsResult.isSuccess) {
                        val libPage = playlistsResult.getOrNull()
                        libPage?.items?.forEach { ytItem ->
                            if (ytItem is PlaylistItem) {
                                val titleLower = ytItem.title.lowercase()
                                if (titleLower != "liked music" && titleLower != "episodes for later") {
                                    fetchedItems.add(LibraryGridItem(
                                        id = ytItem.id,
                                        title = ytItem.title,
                                        subtitle = ytItem.songCountText ?: "Songs",
                                        thumbnailUrl = ytItem.thumbnail,
                                        rawItem = ytItem,
                                        authorName = ytItem.author?.name,
                                        authorAvatarUrl = ytItem.authorAvatarUrl
                                    ))
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    isLoadingGrid = false
                    if (fetchedItems.isEmpty() || (selectedTab == "Playlists" && fetchedItems.size <= 1)) {
                        gridItems = when (selectedTab) {
                            "Playlists" -> fallbackPlaylists
                            "Albums" -> fallbackAlbums
                            else -> fallbackArtists
                        }
                    } else {
                        gridItems = fetchedItems
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isLoadingGrid = false
                    gridItems = when (selectedTab) {
                        "Playlists" -> fallbackPlaylists
                        "Albums" -> fallbackAlbums
                        else -> fallbackArtists
                    }
                }
            }
        }
    }

    LaunchedEffect(activePlaylistId, playlistsRefreshTrigger) {
        val playlistId = activePlaylistId ?: return@LaunchedEffect
        
        // If we already have the songs from the initial playlist, don't clear or reload them
        if (playlistsRefreshTrigger == 0 && playlistId == initialPlaylistId && playlistSongs.isNotEmpty()) {
            return@LaunchedEffect
        }
        
        isSongsLoading = true
        playlistSongs = emptyList()

        coroutineScope.launch(Dispatchers.IO) {
            try {
                var tracks = emptyList<SongItem>()
                val rawItem = activePlaylistRawItem

                if (activePlaylistIsLiked || playlistId == "LM") {
                    val playlistResult = YouTube.playlist("LM")
                    if (playlistResult.isSuccess) tracks = playlistResult.getOrNull()?.songs.orEmpty()
                } else if (rawItem is PlaylistItem || playlistId.startsWith("PL") || playlistId.startsWith("RD")) {
                    val playlistResult = YouTube.playlist(playlistId)
                    if (playlistResult.isSuccess) {
                        val playlistPage = playlistResult.getOrNull()
                        tracks = playlistPage?.songs.orEmpty()
                        playlistPage?.playlist?.let { playlistMeta ->
                            withContext(Dispatchers.Main) {
                                if (activePlaylistName == "Today's Hits" || activePlaylistName == "Chill Hits" || activePlaylistName == "Lo-Fi Beats" || activePlaylistName == "Workout Energy" || activePlaylistName == "Party Starter") {
                                    activePlaylistName = playlistMeta.title
                                    if (playlistMeta.thumbnail != null) activePlaylistThumbnail = playlistMeta.thumbnail
                                    activePlaylistSongCountText = playlistMeta.songCountText ?: "${tracks.size} songs"
                                }
                            }
                        }
                    }
                } else if (rawItem is AlbumItem) {
                    val albumResult = YouTube.albumSongs(rawItem.playlistId, rawItem)
                    if (albumResult.isSuccess) tracks = albumResult.getOrNull().orEmpty()
                } else if (rawItem is ArtistItem) {
                    val searchResult = YouTube.search(activePlaylistName, YouTube.SearchFilter.FILTER_SONG)
                    if (searchResult.isSuccess) tracks = searchResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                }

                if (tracks.isEmpty()) {
                    val countStr = activePlaylistSongCountText.filter { it.isDigit() }
                    tracks = generatePlaylistSongs(countStr.toIntOrNull() ?: 15)
                }

                withContext(Dispatchers.Main) {
                    playlistSongs = tracks
                    isSongsLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    val countStr = activePlaylistSongCountText.filter { it.isDigit() }
                    playlistSongs = generatePlaylistSongs(countStr.toIntOrNull() ?: 15)
                    isSongsLoading = false
                }
            }
        }
    }


    // Filter and sorting derived states
    val filteredGridItems = remember(gridItems, searchQuery) {
        if (searchQuery.isBlank()) gridItems
        else gridItems.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.subtitle.contains(searchQuery, ignoreCase = true)
        }
    }

    val sortedGridItems = remember(filteredGridItems, sortBy, sortAscending) {
        val likedItem = filteredGridItems.firstOrNull { it.isLiked }
        val restOfItems = filteredGridItems.filter { !it.isLiked }

        val sortedRest = when (sortBy) {
            "Name" -> restOfItems.sortedBy { it.title.lowercase() }
            "Date updated" -> restOfItems.sortedBy { it.id }
            else -> restOfItems // Date added (original list order)
        }

        val fullySorted = if (likedItem != null) {
            listOf(likedItem) + if (sortAscending) sortedRest else sortedRest.reversed()
        } else {
            if (sortAscending) sortedRest else sortedRest.reversed()
        }
        fullySorted
    }

    val filteredSongs = remember(playlistSongs, searchQuery) {
        if (searchQuery.isBlank()) playlistSongs
        else playlistSongs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artists.any { artist -> artist.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    val sortedSongs = remember(filteredSongs, sortBy, sortAscending) {
        val sorted = when (sortBy) {
            "Name" -> filteredSongs.sortedBy { it.title.lowercase() }
            "Date updated" -> filteredSongs.sortedBy { it.id }
            else -> filteredSongs // Date added (as fetched)
        }
        if (sortAscending) sorted else sorted.reversed()
    }

//    for individual playlist
    if (activePlaylistId != null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                if (isSearchActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                playSoundAndHaptic()
                                isSearchActive = false
                                searchQuery = ""
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

                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search songs...", color = textColor.copy(alpha = 0.5f)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = textColor,
                                unfocusedIndicatorColor = textColor.copy(alpha = 0.5f),
                                focusedTextColor = textColor,
                                unfocusedTextColor = textColor
                            ),
                            shape = RoundedCornerShape(30.dp),
                            modifier = Modifier.weight(1f).height(60.dp)
                        )

                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    playSoundAndHaptic()
                                    searchQuery = ""
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = textColor
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                playSoundAndHaptic()
                                activePlaylistId = null
                                playlistSongs = emptyList()
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
                            text = "Playlist View",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = textColor
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        IconButton(
                            onClick = {
                                playSoundAndHaptic()
                                isSearchActive = true
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = textColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { handleRefresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(180.dp)
                                    .clip(RoundedCornerShape(32.dp))
                                    .background(
                                        color = if (activePlaylistIsLiked) Color(0xFFFF0000) else textColor
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (activePlaylistIsLiked) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = "Heart",
                                        tint = Color.White,
                                        modifier = Modifier.size(76.dp)

                                    )
                                } else if (!activePlaylistThumbnail.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = activePlaylistThumbnail,
                                        contentDescription = activePlaylistName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = activePlaylistName,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Medium,
                                color = textColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            val accountInfo = remember(context) { SessionManager.getCachedAccountInfo(context) }
                            val authorName = if (activePlaylistIsLiked || activePlaylistId == "LM") accountInfo?.name ?: "You" else activePlaylistAuthorName ?: "TuneSpark"
                            val authorAvatarUrl = if (activePlaylistIsLiked || activePlaylistId == "LM") accountInfo?.thumbnailUrl else activePlaylistAuthorAvatarUrl

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color.Gray.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!authorAvatarUrl.isNullOrEmpty()) {
                                        AsyncImage(
                                            model = authorAvatarUrl,
                                            contentDescription = "Author Avatar",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            text = if (authorName.isNotEmpty()) authorName.take(1).uppercase() else "T",
                                            color = textColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = "By $authorName • ${sortedSongs.size} songs",
                                    fontSize = 14.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                val isSaved = remember(activePlaylistId, gridItems) {
                                    activePlaylistId == "LM" || activePlaylistIsLiked || gridItems.any { it.id == activePlaylistId }
                                }
                                var isLocallySaved by remember(activePlaylistId) { mutableStateOf(false) }
                                val showSaveOption = activePlaylistId != null && activePlaylistId != "LM" && !isSaved && !isLocallySaved

                                Button(
                                    onClick = {
                                        playSoundAndHaptic()
                                        if (sortedSongs.isNotEmpty()) {
                                            onPlayPlaylist(activePlaylistName, sortedSongs, 0)
                                            onNavigate(AppScreen.RADIO)
                                        }
                                    },
                                    shape = RoundedCornerShape(30.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = textColor,
                                        contentColor = backgroundColor
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(60.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Play", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                Button(
                                    onClick = {
                                        playSoundAndHaptic()
                                        if (sortedSongs.isNotEmpty()) {
                                            val shuffled = sortedSongs.shuffled()
                                            onPlayPlaylist(activePlaylistName, shuffled, 0)
                                            onNavigate(AppScreen.RADIO)
                                        }
                                    },
                                    shape = RoundedCornerShape(30.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.Gray.copy(alpha = 0.2f),
                                        contentColor = textColor
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(60.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Shuffle", modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Shuffle", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                val currentActiveId = activePlaylistId
                                if (currentActiveId != null) {
                                    var menuExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(
                                            onClick = {
                                                playSoundAndHaptic()
                                                menuExpanded = true
                                            },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(Color.Gray.copy(alpha = 0.2f))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "More options",
                                                tint = textColor,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = menuExpanded,
                                            onDismissRequest = { menuExpanded = false },
                                            modifier = Modifier
                                                .background(backgroundColor)
                                                .border(1.dp, textColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                        ) {
                                            if (showSaveOption) {
                                                DropdownMenuItem(
                                                    text = { Text("Save to library", color = textColor) },
                                                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = "Save", tint = textColor) },
                                                    onClick = {
                                                        playSoundAndHaptic()
                                                        menuExpanded = false
                                                        if (!SessionManager.isUserSignedIn(context)) {
                                                            Toast.makeText(context, "Please sign in to your account first.", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            coroutineScope.launch {
                                                                val result = withContext(Dispatchers.IO) {
                                                                    YouTube.likePlaylist(currentActiveId, true)
                                                                }
                                                                if (result.isSuccess) {
                                                                    isLocallySaved = true
                                                                    Toast.makeText(context, "Saved '$activePlaylistName' to library!", Toast.LENGTH_SHORT).show()
                                                                } else {
                                                                    Toast.makeText(context, "Failed to save playlist to library.", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        }
                                                    }
                                                )
                                            } else {
                                                DropdownMenuItem(
                                                    text = { Text("Already saved", color = textColor.copy(alpha = 0.5f)) },
                                                    leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = "Saved", tint = textColor.copy(alpha = 0.5f)) },
                                                    enabled = false,
                                                    onClick = {}
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }

                    if (isSongsLoading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = textColor)
                            }
                        }
                    } else if (sortedSongs.isEmpty()) {
                        item {
                            Text(
                                text = "No songs found in this playlist.",
                                color = Color.Gray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 24.dp)
                            )
                        }
                    } else {
                        itemsIndexed(sortedSongs) { index, song ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        playSoundAndHaptic()
                                        onPlayPlaylist(activePlaylistName, sortedSongs, index)
                                        onNavigate(AppScreen.RADIO)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            ) {


                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Gray.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!song.thumbnail.isNullOrEmpty()) {
                                        AsyncImage(
                                            model = song.thumbnail,
                                            contentDescription = song.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text("🎵", fontSize = 18.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = textColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song.artists.joinToString(", ") { it.name },
                                        fontSize = 13.sp,
                                        color = Color.Gray,
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
    } else {
        // ── GRID VIEW ────────────────────────────────────────────────────
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 0.dp)
        ) {
            if (isSearchActive) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            playSoundAndHaptic()
                            isSearchActive = false
                            searchQuery = ""
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

                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search playlists...", color = textColor.copy(alpha = 0.5f)) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = textColor,
                            unfocusedIndicatorColor = textColor.copy(alpha = 0.5f),
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor
                        ),
                        shape = RoundedCornerShape(30.dp),
                        modifier = Modifier.weight(1f).height(60.dp)
                    )

                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                playSoundAndHaptic()
                                searchQuery = ""
                            }
                        ) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", tint = textColor)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    playSoundAndHaptic()
                                    sortMenuExpanded = true
                                }
                            ) {
                                Text(text = sortBy, color = textColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (sortAscending) "↑" else "↓",
                                color = textColor,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        playSoundAndHaptic()
                                        val newVal = !sortAscending
                                        sortAscending = newVal
                                        sharedPrefs.edit().putBoolean("sort_ascending", newVal).apply()
                                    }
                                    .padding(horizontal = 4.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                            modifier = Modifier.background(backgroundColor).border(1.dp, textColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        ) {
                            listOf("Date added", "Name", "Date updated").forEach { param ->
                                DropdownMenuItem(
                                    text = { Text(param, color = textColor, fontWeight = if (sortBy == param) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = {
                                        playSoundAndHaptic()
                                        sortBy = param
                                        sharedPrefs.edit().putString("sort_by", param).apply()
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            playSoundAndHaptic()
                            isSearchActive = true
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = textColor, modifier = Modifier.size(24.dp))
                    }
                }
            }

            if (isLoadingGrid && sortedGridItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = textColor)
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { handleRefresh() },
                    modifier = Modifier.weight(1f)
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                    items(sortedGridItems) { item ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().clickable {
                                playSoundAndHaptic()
                                activePlaylistId = item.id
                                activePlaylistName = item.title
                                activePlaylistThumbnail = item.thumbnailUrl
                                activePlaylistSongCountText = item.subtitle
                                activePlaylistIsLiked = item.isLiked
                                activePlaylistRawItem = item.rawItem
                                activePlaylistAuthorName = item.authorName
                                activePlaylistAuthorAvatarUrl = item.authorAvatarUrl
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(color = if (item.isLiked) Color(0xFFFF0000) else textColor),
                                contentAlignment = Alignment.Center
                            ) {
                                if (item.isLiked) {
                                    Icon(imageVector = Icons.Default.Favorite, contentDescription = "Heart", tint = Color.White, modifier = Modifier.size(44.dp))
                                } else if (!item.thumbnailUrl.isNullOrEmpty()) {
                                    AsyncImage(model = item.thumbnailUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(text = item.title, color = textColor, fontWeight = FontWeight.Medium, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            Text(text = item.subtitle, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
                }
            }
        }
    }
}
