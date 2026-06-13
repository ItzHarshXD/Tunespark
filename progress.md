# TuneSpark Open-Source Music Streaming Player

TuneSpark is a clean Android music streaming app built with Jetpack Compose, AndroidX Media3/ExoPlayer, and Metrolist's `:innertube` module for YouTube Music data.

---

## Project Architecture

The project is split into two Gradle modules:

### 1. `:app`

The Android app module. It owns the UI, playback service, and app-level Android permissions.

Key files and packages:

- `app/src/main/java/com/tunespark/music/MainActivity.kt`
  - Single-activity entry point. Boots the Jetpack Compose UI, binds to `PlaybackService` via Media3 `MediaController`, initializes `SessionManager`, and hosts global state variables. Delegates screen rendering cleanly to the individual composable screens.
- `app/src/main/java/com/tunespark/music/ui/screens/`
  - Modularized screens package hosting distinct, clean Jetpack Compose UI screens:
    - `HomeScreen.kt`: Landing view with brand headers, settings launcher, quick shuffle play, and search launcher.
    - `SearchScreen.kt`: Dedicated search field, queries YouTube Music, displays song results, and launches playback.
    - `RadioScreen.kt`: Audio player view displaying up-next list and control triggers mapped to MediaController commands.
    - `SettingsScreen.kt`: Interactive links leading to dedicated customization options.
    - `AccountScreen.kt`: Manages optional YouTube Music login WebViews, sign-out actions, and live profile details.
    - `AppearanceScreen.kt`: Styled theme selections (Light, Dark, System).
    - `AiVoiceScreen.kt`: Controls and API keys configuration for Gemini / ElevenLabs backends.
    - `CommentaryScreen.kt`: Customize dynamic list switches like weather updates, song intros, etc.
    - `NotificationsScreen.kt`: Setting to turn on/off app notifications.
    - `LocationScreen.kt`: Manages auto/manual GPS coordinates fetching for localized updates.
    - `UpdatesScreen.kt`: Shows current version (v1.24.2) and update check actions.
    - `SettingsHeader.kt`: A shared, beautiful back-navigated top-bar widget used across configurations.
- `app/src/main/java/com/tunespark/music/SessionManager.kt`
  - Manages secure, local persistence of YouTube session cookies in `SharedPreferences`.
  - Handles locally-cached user profile details (avatar, name, email) and initializes the active session cookie in `:innertube` on app startup.
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

TuneSpark has been structured into 11 distinct screens for clear separation of concerns and a native-feeling UX:

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
   - The functional screen selected from Settings. Displays account details.
   - Hosts the **Optional YouTube Music Sign-In** flow. If signed out, it presents a secure Google Sign-In prompt. If signed in, it displays live user profile details (avatar badge with initials, email, handle), connection status, and a fully-functional "Sign Out" option.
6. **Appearance Screen**:
   - Custom interface displaying "Select theme" with three stylized visual options (Light, Dark, and System Split) and selectable pill-shaped labels.
7. **AI and Voice Screen**:
   - Contains toggleable tabs for both "Gemini" and "ElevenLabs" options, instruction steps on how to obtain API keys, custom key input field with clipboard icon, customizable ElevenLabs Voice ID input box, a "Preview voice" action button, and a red commentary frequency slider.
8. **Commentary Screen**:
   - Dynamic list of option switches ("Weather updates", "Session opener", "Song intro", etc.) with stylized check circle toggle states.
9. **Notifications Screen**:
   - Simple setting view to disable or toggle notifications.
10. **Location Screen**:
    - Manage auto/manual location settings with active GPS switches, current coordinate display, and a dedicated GPS crosshair action button.
11. **Updates Screen**:
    - Displays current app version (v1.24.2) and hosts interactive dummy "Check for updates" buttons.

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

---

## How YouTube Music Sign-In Works

### 1. Secure OAuth-less Login Integration
TuneSpark embeds a system-native, secure `WebView` pointing directly to Google's official sign-in endpoint (`accounts.google.com`). This ensures that credentials remain completely isolated and secure. The app never sees or stores user passwords.

### 2. Cookie Extraction & Cryptographic Header Signing
Upon successful Google Authentication, the app interceptively extracts session cookies (like `SAPISID`) via Android's `CookieManager`. On every authenticated InnerTube request:
- InnerTube uses the `SAPISID` cookie along with the current timestamp to generate a SHA-1 cryptographic signature called `SAPISIDHASH`.
- This hash is passed via the `Authorization` header, granting access to the official YouTube Music private API.

### 3. Personalization & Playback Tracking
Once signed in:
- Calls to homepage browse endpoints (`YouTube.home()`) fetch the user's custom taste feeds, favorite mixes, and library.
- Background recommendations (`YouTube.next()`) shift from generic charts to highly-tailored algorithmic radios.
- Song playback actively updates the user's official YouTube Music listening history, training their personalized recommendations.

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
