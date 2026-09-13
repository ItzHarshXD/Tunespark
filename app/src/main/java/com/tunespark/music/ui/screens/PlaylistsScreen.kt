package com.tunespark.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
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
import androidx.compose.ui.draw.shadow
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val authorAvatarUrl: String? = null,
    val isArtist: Boolean = false
)

enum class PlaylistsViewMode {
    LIBRARY_GRID,
    PLAYLIST_DETAIL,
    ARTIST_DETAIL,
    ARTIST_ALL_SONGS
}

private fun String.toHighResArtistThumbnail(): String {
    return when {
        contains("googleusercontent.com") || contains("ggpht.com") -> {
            val wHRegex = "=[ws]\\d+(-h\\d+)?.*".toRegex()
            if (contains(wHRegex)) {
                replace(wHRegex, "=w1024-h1024")
            } else {
                val sRegex = "=s\\d+.*".toRegex()
                if (contains(sRegex)) {
                    replace(sRegex, "=w1024-h1024")
                } else {
                    "${this}=w1024-h1024"
                }
            }
        }
        contains("ytimg.com") -> {
            replace("/default.jpg", "/maxresdefault.jpg")
                .replace("/mqdefault.jpg", "/maxresdefault.jpg")
                .replace("/hqdefault.jpg", "/maxresdefault.jpg")
        }
        else -> this
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    onPlaySong: ((SongItem) -> Unit)? = null,
    onSongLongPress: (SongItem) -> Unit = {},
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

    // Artist navigation state
    var activeArtistId by remember { mutableStateOf<String?>(null) }
    var activeArtistName by remember { mutableStateOf("") }
    var activeArtistThumbnail by remember { mutableStateOf<String?>(null) }
    var activeArtistSubscribers by remember { mutableStateOf<String?>(null) }
    var activeArtistRawItem by remember { mutableStateOf<ArtistItem?>(null) }
    var activeArtistRadioEndpoint by remember { mutableStateOf<WatchEndpoint?>(null) }
    var activeArtistMoreEndpoint by remember { mutableStateOf<BrowseEndpoint?>(null) }

    var artistTopSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isArtistSongsLoading by remember { mutableStateOf(false) }

    var isArtistAllSongsVisible by remember { mutableStateOf(false) }
    var artistAllSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isArtistAllSongsLoading by remember { mutableStateOf(false) }

    BackHandler {
        playSoundAndHaptic()
        if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else if (isArtistAllSongsVisible) {
            isArtistAllSongsVisible = false
        } else if (activeArtistId != null) {
            activeArtistId = null
            artistTopSongs = emptyList()
            artistAllSongs = emptyList()
        } else if (activePlaylistId != null) {
            activePlaylistId = null
            playlistSongs = emptyList()
        } else {
            onNavigate(AppScreen.HOME)
        }
    }

    var gridItems by remember { mutableStateOf<List<LibraryGridItem>>(emptyList()) }
    var isLoadingGrid by remember { mutableStateOf(false) }

    val isUserSignedIn = SessionManager.isUserSignedIn(context)

    LaunchedEffect(selectedTab, playlistsRefreshTrigger, isUserSignedIn) {
        if (!isUserSignedIn) {
            gridItems = emptyList()
            isLoadingGrid = false
            return@LaunchedEffect
        }

        isLoadingGrid = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Fetch playlists and subscribed artists concurrently
                val playlistsDeferred = async {
                    val list = mutableListOf<LibraryGridItem>()
                    try {
                        val playlistsResult = YouTube.library("FEmusic_liked_playlists")
                        if (playlistsResult.isSuccess) {
                            playlistsResult.getOrNull()?.items?.forEach { ytItem ->
                                if (ytItem is PlaylistItem) {
                                    val titleLower = ytItem.title.lowercase()
                                    if (titleLower != "liked music" && titleLower != "episodes for later") {
                                        list.add(
                                            LibraryGridItem(
                                                id = ytItem.id,
                                                title = ytItem.title,
                                                subtitle = ytItem.songCountText ?: "Songs",
                                                thumbnailUrl = ytItem.thumbnail,
                                                rawItem = ytItem,
                                                authorName = ytItem.author?.name,
                                                authorAvatarUrl = ytItem.authorAvatarUrl,
                                                isArtist = false
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    list
                }

                val artistsDeferred = async {
                    val list = mutableListOf<LibraryGridItem>()
                    val seenArtistIds = mutableSetOf<String>()

                    fun addArtist(id: String, title: String, thumb: String?, raw: ArtistItem?) {
                        if (id.isNotBlank() && title.isNotBlank() && seenArtistIds.add(id)) {
                            val highResThumb = thumb?.toHighResArtistThumbnail()
                            list.add(
                                LibraryGridItem(
                                    id = id,
                                    title = title,
                                    subtitle = "Artist",
                                    thumbnailUrl = highResThumb,
                                    rawItem = raw ?: ArtistItem(id = id, title = title, thumbnail = highResThumb, shuffleEndpoint = null, radioEndpoint = null),
                                    isArtist = true
                                )
                            )
                        }
                    }

                    // 1. Try YouTube.library("FEmusic_library_detail_artists")
                    try {
                        val artistsResult = YouTube.library("FEmusic_library_detail_artists")
                        if (artistsResult.isSuccess) {
                            artistsResult.getOrNull()?.items?.forEach { ytItem ->
                                if (ytItem is ArtistItem) {
                                    addArtist(ytItem.id, ytItem.title, ytItem.thumbnail, ytItem)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // 2. Parse directly via InnerTube.browse to catch all renderers
                    try {
                        val innerTube = com.metrolist.innertube.InnerTube().apply {
                            cookie = com.metrolist.innertube.YouTube.cookie
                        }
                        val response = innerTube.browse(
                            client = com.metrolist.innertube.models.YouTubeClient.WEB_REMIX,
                            browseId = "FEmusic_library_detail_artists",
                            setLogin = true
                        ).body<com.metrolist.innertube.models.response.BrowseResponse>()

                        val sectionContents = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents
                            ?: response.contents?.sectionListRenderer?.contents
                            ?: response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents
                            ?: response.continuationContents?.sectionListContinuation?.contents

                        sectionContents?.forEach { content ->
                            content.gridRenderer?.items?.forEach { gridItem ->
                                val renderer = gridItem.musicTwoRowItemRenderer
                                if (renderer != null) {
                                    val isArtist = renderer.isArtist || renderer.isUserChannel ||
                                        renderer.navigationEndpoint.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_ARTIST" ||
                                        renderer.navigationEndpoint.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_LIBRARY_ARTIST" ||
                                        renderer.navigationEndpoint.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL"

                                    if (isArtist) {
                                        val bId = renderer.navigationEndpoint.browseEndpoint?.browseId
                                        val title = renderer.title.runs?.lastOrNull()?.text ?: renderer.title.runs?.firstOrNull()?.text ?: ""
                                        val thumb = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl()
                                        if (bId != null && title.isNotEmpty()) {
                                            addArtist(bId, title, thumb, null)
                                        }
                                    }
                                }
                            }

                            content.musicShelfRenderer?.contents?.forEach { shelfContent ->
                                val renderer = shelfContent.musicResponsiveListItemRenderer
                                if (renderer != null) {
                                    val pageType = renderer.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                                        ?: renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                                            ?.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType

                                    val isArtist = renderer.isArtist || renderer.isUserChannel ||
                                        pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_LIBRARY_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL"

                                    val bId = renderer.navigationEndpoint?.browseEndpoint?.browseId
                                        ?: renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.navigationEndpoint?.browseEndpoint?.browseId

                                    if (isArtist && bId != null) {
                                        val title = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text ?: ""
                                        val thumb = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                                        if (title.isNotEmpty()) {
                                            addArtist(bId, title, thumb, null)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // 3. Fallback: if still empty, try "FEmusic_library_corpus_artists"
                    if (list.isEmpty()) {
                        try {
                            val corpusResult = YouTube.library("FEmusic_library_corpus_artists")
                            if (corpusResult.isSuccess) {
                                corpusResult.getOrNull()?.items?.forEach { ytItem ->
                                    if (ytItem is ArtistItem) {
                                        addArtist(ytItem.id, ytItem.title, ytItem.thumbnail, ytItem)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    list
                }

                val fetchedPlaylists = playlistsDeferred.await()
                val fetchedArtists = artistsDeferred.await()

                val fetchedItems = mutableListOf<LibraryGridItem>()
                // Liked songs playlist
                fetchedItems.add(
                    LibraryGridItem(
                        id = "LM",
                        title = "Liked",
                        subtitle = "Your liked songs",
                        isLiked = true,
                        isArtist = false
                    )
                )

                // Interleave playlists and subscribed artists so artists appear among/between playlists
                val pIter = fetchedPlaylists.iterator()
                val aIter = fetchedArtists.iterator()
                while (pIter.hasNext() || aIter.hasNext()) {
                    if (pIter.hasNext()) fetchedItems.add(pIter.next())
                    if (pIter.hasNext()) fetchedItems.add(pIter.next())
                    if (aIter.hasNext()) fetchedItems.add(aIter.next())
                }

                withContext(Dispatchers.Main) {
                    isLoadingGrid = false
                    gridItems = fetchedItems
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isLoadingGrid = false
                    gridItems = emptyList()
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
                    if (playlistResult.isSuccess) {
                        tracks = playlistResult.getOrNull()?.songs.orEmpty()
                        com.tunespark.music.LikedSongManager.setLikedSongs(context, tracks.map { it.id })
                    }
                } else if (rawItem is PlaylistItem || playlistId.startsWith("PL") || playlistId.startsWith("RD")) {
                    val playlistResult = YouTube.playlist(playlistId)
                    if (playlistResult.isSuccess) {
                        val playlistPage = playlistResult.getOrNull()
                        tracks = playlistPage?.songs.orEmpty()
                        playlistPage?.playlist?.let { playlistMeta ->
                            withContext(Dispatchers.Main) {
                                if (playlistMeta.thumbnail != null) activePlaylistThumbnail = playlistMeta.thumbnail
                                activePlaylistSongCountText = playlistMeta.songCountText ?: "${tracks.size} songs"
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

                withContext(Dispatchers.Main) {
                    playlistSongs = tracks
                    isSongsLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    playlistSongs = emptyList()
                    isSongsLoading = false
                }
            }
        }
    }

    LaunchedEffect(activeArtistId, playlistsRefreshTrigger) {
        val artistId = activeArtistId ?: return@LaunchedEffect
        isArtistSongsLoading = true
        artistTopSongs = emptyList()
        artistAllSongs = emptyList()
        isArtistAllSongsVisible = false

        coroutineScope.launch(Dispatchers.IO) {
            try {
                var topSongs = emptyList<SongItem>()
                var moreEndpoint: BrowseEndpoint? = null
                var subText: String? = null
                var radioEndpoint: WatchEndpoint? = null
                var highResThumb: String? = null

                val artistResult = YouTube.artist(artistId)
                if (artistResult.isSuccess) {
                    val page = artistResult.getOrNull()
                    if (page != null) {
                        subText = page.subscriberCountText
                        radioEndpoint = page.artist.radioEndpoint
                        if (!page.artist.thumbnail.isNullOrEmpty()) {
                            highResThumb = page.artist.thumbnail?.toHighResArtistThumbnail()
                        }
                        val songSection = page.sections.firstOrNull { s ->
                            s.items.any { it is SongItem } || s.title.contains("song", ignoreCase = true)
                        }
                        if (songSection != null) {
                            topSongs = songSection.items.filterIsInstance<SongItem>()
                            moreEndpoint = songSection.moreEndpoint
                        }
                    }
                }

                if (topSongs.isEmpty()) {
                    val searchResult = YouTube.search(activeArtistName, YouTube.SearchFilter.FILTER_SONG)
                    if (searchResult.isSuccess) {
                        topSongs = searchResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                    }
                }

                withContext(Dispatchers.Main) {
                    artistTopSongs = topSongs.take(10)
                    activeArtistMoreEndpoint = moreEndpoint
                    if (!highResThumb.isNullOrEmpty()) activeArtistThumbnail = highResThumb
                    if (subText != null) activeArtistSubscribers = subText
                    if (radioEndpoint != null) activeArtistRadioEndpoint = radioEndpoint
                    isArtistSongsLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    val searchResult = YouTube.search(activeArtistName, YouTube.SearchFilter.FILTER_SONG)
                    val fallbackSongs = if (searchResult.isSuccess) {
                        searchResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                    } else emptyList()
                    withContext(Dispatchers.Main) {
                        artistTopSongs = fallbackSongs.take(10)
                        isArtistSongsLoading = false
                    }
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    withContext(Dispatchers.Main) {
                        artistTopSongs = emptyList()
                        isArtistSongsLoading = false
                    }
                }
            }
        }
    }

    LaunchedEffect(isArtistAllSongsVisible, activeArtistId) {
        if (isArtistAllSongsVisible && activeArtistId != null && artistAllSongs.isEmpty()) {
            isArtistAllSongsLoading = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    var allSongs = emptyList<SongItem>()
                    val moreEndpoint = activeArtistMoreEndpoint

                    if (moreEndpoint != null) {
                        val itemsResult = YouTube.artistItems(moreEndpoint)
                        if (itemsResult.isSuccess) {
                            allSongs = itemsResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                        }
                    }

                    if (allSongs.isEmpty()) {
                        val searchResult = YouTube.search(activeArtistName, YouTube.SearchFilter.FILTER_SONG)
                        if (searchResult.isSuccess) {
                            allSongs = searchResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                        }
                    }

                    val merged = (artistTopSongs + allSongs).distinctBy { it.id }

                    withContext(Dispatchers.Main) {
                        artistAllSongs = merged
                        isArtistAllSongsLoading = false
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        artistAllSongs = artistTopSongs
                        isArtistAllSongsLoading = false
                    }
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

    val sortedSongs = remember(filteredSongs, sortBy, sortAscending, activePlaylistId, activePlaylistIsLiked) {
        val sorted = when (sortBy) {
            "Name" -> filteredSongs.sortedBy { it.title.lowercase() }
            "Date updated" -> filteredSongs.sortedBy { it.id }
            else -> filteredSongs // Date added (as fetched)
        }
        val isLikedPlaylist = activePlaylistId == "LM" || activePlaylistIsLiked
        if (isLikedPlaylist && sortBy == "Date added") {
            if (sortAscending) sorted.reversed() else sorted
        } else {
            if (sortAscending) sorted else sorted.reversed()
        }
    }

    val currentMode = when {
        isArtistAllSongsVisible && activeArtistId != null -> PlaylistsViewMode.ARTIST_ALL_SONGS
        activeArtistId != null -> PlaylistsViewMode.ARTIST_DETAIL
        activePlaylistId != null -> PlaylistsViewMode.PLAYLIST_DETAIL
        else -> PlaylistsViewMode.LIBRARY_GRID
    }

    AnimatedContent(
        targetState = currentMode,
        transitionSpec = {
            if (targetState.ordinal > initialState.ordinal) {
                slideInHorizontally(animationSpec = tween(300)) { width -> width } togetherWith
                        slideOutHorizontally(animationSpec = tween(300)) { width -> -width }
            } else {
                slideInHorizontally(animationSpec = tween(300)) { width -> -width } togetherWith
                        slideOutHorizontally(animationSpec = tween(300)) { width -> width }
            }
        },
        label = "ScreenTransition",
        modifier = modifier.fillMaxSize()
    ) { mode ->
        when (mode) {
            PlaylistsViewMode.ARTIST_ALL_SONGS -> {
                ArtistAllSongsScreen(
                    artistName = activeArtistName,
                    songs = artistAllSongs,
                    isLoading = isArtistAllSongsLoading,
                    onBack = {
                        playSoundAndHaptic()
                        isArtistAllSongsVisible = false
                    },
                    onPlaySong = { song ->
                        playSoundAndHaptic()
                        if (onPlaySong != null) {
                            onPlaySong(song)
                        } else {
                            val idx = artistAllSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                            onPlayPlaylist(activeArtistName, artistAllSongs, idx)
                        }
                        onNavigate(AppScreen.RADIO)
                    },
                    onSongLongPress = onSongLongPress
                )
            }

            PlaylistsViewMode.ARTIST_DETAIL -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    playSoundAndHaptic()
                                    activeArtistId = null
                                    artistTopSongs = emptyList()
                                    artistAllSongs = emptyList()
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
                                text = "Artist View",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = textColor
                            )
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
                                                .clip(CircleShape)
                                                .background(textColor),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (!activeArtistThumbnail.isNullOrEmpty()) {
                                                AsyncImage(
                                                    model = activeArtistThumbnail,
                                                    contentDescription = activeArtistName,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.AccountCircle,
                                                    contentDescription = "Artist",
                                                    tint = backgroundColor,
                                                    modifier = Modifier.size(80.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Text(
                                            text = activeArtistName,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = textColor,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )

                                        Text(
                                            text = activeArtistSubscribers ?: "Artist",
                                            fontSize = 14.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                                        )

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    playSoundAndHaptic()
                                                    if (artistTopSongs.isNotEmpty()) {
                                                        onPlayPlaylist(activeArtistName, artistTopSongs, 0)
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
                                                    if (artistTopSongs.isNotEmpty()) {
                                                        if (onPlaySong != null) {
                                                            onPlaySong(artistTopSongs.first())
                                                        } else {
                                                            onPlayPlaylist(activeArtistName, artistTopSongs, 0)
                                                        }
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
                                                Icon(Icons.Default.Radio, contentDescription = "Start radio", modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Start radio", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(24.dp))

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Top songs",
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = textColor
                                            )

                                            val isDarkTheme = MaterialTheme.colorScheme.background == Color.Black
                                            val showAllBgColor = if (isDarkTheme) Color(0xFF16161A) else Color(0xFFF2F2F5)
                                            val showAllTextColor = if (isDarkTheme) Color.White else Color.Black
                                            val showAllBorderColor = if (isDarkTheme) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f)

                                            Box(
                                                modifier = Modifier
                                                    .shadow(elevation = 6.dp, shape = CircleShape)
                                                    .clip(CircleShape)
                                                    .background(showAllBgColor)
                                                    .border(1.dp, showAllBorderColor, CircleShape)
                                                    .clickable {
                                                        playSoundAndHaptic()
                                                        isArtistAllSongsVisible = true
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    text = "Show all",
                                                    color = showAllTextColor,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                if (isArtistSongsLoading) {
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
                                } else if (artistTopSongs.isEmpty()) {
                                    item {
                                        Text(
                                            text = "No songs found for this artist.",
                                            color = Color.Gray,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 24.dp)
                                        )
                                    }
                                } else {
                                    itemsIndexed(artistTopSongs.take(10)) { index, song ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = {
                                                        playSoundAndHaptic()
                                                        onPlayPlaylist(activeArtistName, artistTopSongs, index)
                                                        onNavigate(AppScreen.RADIO)
                                                    },
                                                    onLongClick = {
                                                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                        onSongLongPress(song)
                                                    }
                                                )
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

                                            Spacer(modifier = Modifier.width(6.dp))

                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .clickable {
                                                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                        onSongLongPress(song)
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.MoreVert,
                                                    contentDescription = "More options",
                                                    tint = textColor.copy(alpha = 0.55f),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            PlaylistsViewMode.PLAYLIST_DETAIL -> {
                Box(
                    modifier = Modifier
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
                                            .combinedClickable(
                                                onClick = {
                                                    playSoundAndHaptic()
                                                    onPlayPlaylist(activePlaylistName, sortedSongs, index)
                                                    onNavigate(AppScreen.RADIO)
                                                },
                                                onLongClick = {
                                                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                    onSongLongPress(song)
                                                }
                                            )
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

                                        Spacer(modifier = Modifier.width(6.dp))

                                        // Minimal three-dot quick action button
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                    onSongLongPress(song)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "More options",
                                                tint = textColor.copy(alpha = 0.55f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            }

            PlaylistsViewMode.LIBRARY_GRID -> {
                if (!isUserSignedIn) {
                    // ── SIGNED-OUT LIBRARY VIEW ──────────────────────────────────────
                    val isDarkTheme = MaterialTheme.colorScheme.background == Color.Black
                    val cardBgColor = if (isDarkTheme) Color(0xFF16161A) else Color(0xFFF2F2F5)
                    val cardBorderColor = if (isDarkTheme) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f)

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(backgroundColor)
                            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 0.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Library",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            )
                        }

                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = { handleRefresh() },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 96.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(96.dp)
                                        .shadow(elevation = 6.dp, shape = CircleShape)
                                        .clip(CircleShape)
                                        .background(cardBgColor)
                                        .border(1.dp, cardBorderColor, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = "Sign in",
                                        tint = textColor,
                                        modifier = Modifier.size(52.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                Text(
                                    text = "Sign in to YouTube Music",
                                    color = textColor,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "Sign in to your YouTube Music account to view your playlists, liked tracks, and personalized music library.",
                                    color = Color.Gray,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 22.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )

                                Spacer(modifier = Modifier.height(32.dp))

                                Button(
                                    onClick = {
                                        playSoundAndHaptic()
                                        onNavigate(AppScreen.ACCOUNT)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                                    shape = RoundedCornerShape(30.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Sign In with YouTube",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ── GRID VIEW ────────────────────────────────────────────────────
                    Column(
                        modifier = Modifier
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
                                if (sortedGridItems.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(bottom = 96.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No playlists found in your library.",
                                            color = Color.Gray,
                                            fontSize = 15.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
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
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        playSoundAndHaptic()
                                                        if (item.isArtist) {
                                                            activeArtistId = item.id
                                                            activeArtistName = item.title
                                                            activeArtistThumbnail = item.thumbnailUrl?.toHighResArtistThumbnail()
                                                            activeArtistSubscribers = if (item.subtitle != "Artist") item.subtitle else null
                                                            activeArtistRawItem = item.rawItem as? ArtistItem
                                                        } else {
                                                            activePlaylistId = item.id
                                                            activePlaylistName = item.title
                                                            activePlaylistThumbnail = item.thumbnailUrl
                                                            activePlaylistSongCountText = item.subtitle
                                                            activePlaylistIsLiked = item.isLiked
                                                            activePlaylistRawItem = item.rawItem
                                                            activePlaylistAuthorName = item.authorName
                                                            activePlaylistAuthorAvatarUrl = item.authorAvatarUrl
                                                        }
                                                    }
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .aspectRatio(1f)
                                                        .fillMaxWidth()
                                                        .clip(if (item.isArtist) CircleShape else RoundedCornerShape(28.dp))
                                                        .background(color = if (item.isLiked) Color(0xFFFF0000) else textColor),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (item.isLiked) {
                                                        Icon(imageVector = Icons.Default.Favorite, contentDescription = "Heart", tint = Color.White, modifier = Modifier.size(44.dp))
                                                    } else if (!item.thumbnailUrl.isNullOrEmpty()) {
                                                        AsyncImage(model = item.thumbnailUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                    } else if (item.isArtist) {
                                                        Icon(imageVector = Icons.Default.AccountCircle, contentDescription = "Artist", tint = backgroundColor, modifier = Modifier.size(44.dp))
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
            }
        }
    }
}
