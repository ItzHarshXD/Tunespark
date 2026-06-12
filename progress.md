# TuneSpark Open-Source Music Streaming Player

TuneSpark is a clean Android music streaming app built with Jetpack Compose, AndroidX Media3/ExoPlayer, and Metrolist's `:innertube` module for YouTube Music data.

---

## Project Architecture

The project is split into two Gradle modules:

### 1. `:app`

The Android app module. It owns the UI, playback service, and app-level Android permissions.

Key files:

- `app/src/main/java/com/tunespark/music/MainActivity.kt`
  - Single-activity Jetpack Compose UI with custom state-based navigation across 5 screens (Home, Search, Radio, Settings, and Account).
  - Connects to `PlaybackService` through a Media3 `MediaController`.
  - Handles user interactions, global stream/shuffle-play triggers, and hosts settings options and dedicated views.
- `app/src/main/java/com/tunespark/music/PlaybackService.kt`
  - Background `MediaSessionService`.
  - Owns the real `ExoPlayer` instance.
  - Handles autoplay queue seeding, lazy stream URL resolution, and next-song prefetching.
- `app/src/main/java/com/tunespark/music/StreamUrlResolver.kt`
  - Resolves a YouTube video ID into a playable stream URL.
  - Tries several InnerTube client profiles and picks the first usable direct stream URL.
- `app/src/main/AndroidManifest.xml`
  - Declares network, foreground service, media playback, and service registration requirements.

### 2. `:innertube`

The YouTube Music API wrapper module. It translates app requests into calls to YouTube Music's private InnerTube endpoints.

Key files:

- `innertube/src/main/kotlin/com/metrolist/innertube/InnerTube.kt`
  - Low-level Ktor HTTP client and endpoint request builders.
- `innertube/src/main/kotlin/com/metrolist/innertube/YouTube.kt`
  - High-level API used by the app for search, player data, queues, recommendations, and browse calls.
- `innertube/src/main/kotlin/com/metrolist/innertube/pages/NextPage.kt`
  - Converts YouTube Music "watch next" queue renderers into app `SongItem`s.
- `innertube/src/main/kotlin/com/metrolist/innertube/pages/NewPipe.kt`
  - Utility wrapper for stream/cipher handling.

---

## Screen Architecture

TuneSpark has been structured into 5 distinct screens for clear separation of concerns and a native-feeling UX:

1. **Home Screen**:
   - The landing screen containing the branding header, a dedicated **Settings** button, a **Quick Shuffle Play** card, and a **Search Music** button.
   - If a track is already playing or loaded, a convenient **Go to Radio Screen** button appears.
2. **Search Screen**:
   - Dedicated search layout. Users enter queries, trigger YouTube Music searches, and select a track.
   - Selecting a track immediately triggers playback and transitions to the Radio screen.
3. **Radio Screen**:
   - The playback view. Displays the standard **Up Next** queue and hosts the persistent playback controller card at the bottom.
4. **Settings Screen**:
   - Offers customization options: *Appearance*, *Account*, *AI and Voice*, *Commentary*, *Notifications*, *Location*, and *Updates*.
   - Stylized dark/black background layout with a custom red circular back button.
5. **Account Screen**:
   - The functional screen selected from Settings. Displays account subscriber details.

---

## How Playback Works

### 1. Search

The user types a song, album, or artist name in `MainActivity.kt`.

Search calls:

```kotlin
YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
```

The app displays only `SongItem` results in the Compose search list.

### 2. First Song Playback

When the user taps a search result, the app resolves that song immediately:

```kotlin
StreamUrlResolver.resolveStreamUrl(song.id)
```

The first selected song is inserted into ExoPlayer with a real playable URI. This keeps the first playback action fast and predictable from the user's point of view.

### 3. Background MediaSession

`PlaybackService` owns ExoPlayer inside a Media3 `MediaSession`.

`MainActivity` does not directly own playback. It connects with a `MediaController`, which reads metadata, queue state, and playback state from the service.

This allows playback to continue when the Activity is recreated or the app moves to the background.

### 4. Autoplay Queue Seeding

When a playable song becomes current, `PlaybackService` calls:

```kotlin
YouTube.next(WatchEndpoint(videoId = videoId))
```

That uses InnerTube's YouTube Music "next" endpoint and returns a recommended watch-next queue.

The service filters out songs that are already in the queue and keeps about 20 upcoming tracks available.

The queue is stable during normal playback. When the next song starts, TuneSpark does not replace the remaining queue with a fresh queue for that song. It only fetches more recommendations when the number of upcoming tracks drops below 5, then appends unique new items to the end.

If `YouTube.next` fails or produces no usable next songs, the service falls back to:

```kotlin
YouTube.search("$title $artist", YouTube.SearchFilter.FILTER_SONG)
```

That fallback is app-side logic. The primary recommendation source is still InnerTube's `YouTube.next`.

### 5. Placeholder Queue Items

ExoPlayer requires every queued `MediaItem` to have a URI. It cannot accept URI-less items, even if the app intends to resolve them later.

For this reason, unresolved autoplay items are queued with an internal placeholder URI:

```text
tunespark://unresolved/<videoId>
```

This URI is not meant to be streamed. It is only a marker that lets ExoPlayer hold the item in its queue while TuneSpark resolves the real stream URL in the background.

### 6. Lazy Loading and Prefetching

TuneSpark uses two layers of stream resolution:

- On-demand lazy loading:
  - If playback reaches an unresolved item, `PlaybackService` resolves that item's video ID, replaces the placeholder item with a real stream URL, prepares the player, and resumes playback.
- Proactive prefetching:
  - As soon as a track is current, the service checks the next queue item.
  - If the next item is unresolved, it resolves the stream URL early and replaces the queue item before the current song ends.

This keeps the queue visible immediately while avoiding expensive upfront stream resolution for every recommendation.

### 7. Notification and Queue Controls

The `MediaSession` grants the connected `MediaController` access to timeline and transport commands. This allows:

- The Queue tab to read the full ExoPlayer playlist.
- Previous and Next controls to appear when the timeline has neighboring items.
- The system media notification to expose standard playback controls.

If the queue contains only one item, the notification may not show a Next button. Once recommendations are successfully appended, the Next button becomes available.

### 8. Quick Shuffle Play (Home Screen Shuffle)

The app features a prominent "Quick Shuffle Play" option on the home/search screen when no search query has been entered. 

How it works:
- **Seed Song Acquisition**: Upon clicking, the app fetches a pool of high-quality potential tracks from various sources including regional charts and trending music (`YouTube.getChartsPage()`), editorial/community home collections (`YouTube.home()`), and popular/viral trending search fallbacks.
- **Randomized Seeding**: The song pool is shuffled, and a random seed track is chosen. This prevents repetitiveness and ensures a completely unique queue each time the shuffle button is tapped.
- **Radio/Queue Generation**: Once the random seed track starts playing, the background `PlaybackService` automatically seeds and generates the continuous autoplay queue/radio via `YouTube.next()` in the background.
