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
    - `HomeScreen.kt`: Dynamic, light-themed premium dashboard. Displays current system clock and local weather. Reacts instantly to play state: displays a beautiful spectrum visualizer, a circular play-controller, and compact mini-player when playing, or a personalized greeting with categories and a "Start Radio" button when inactive.
    - `PlaylistsScreen.kt`: Highly-polished 3-column interactive layout supporting Light and Dark themes dynamically. Displays pill tabs ("Playlists", "Albums", "Artists"), sorting triggers, search navigation, and structured playlists: Liked (red with central heart outline) and local lists. Triggers background playlist playback mode with sequential commentary injections.
    - `SearchScreen.kt`: Advanced zero-button interactive search screen. Displays real-time auto-suggestions and reactive song search results side-by-side as you type, matching a clean white minimalist aesthetic.
    - `RadioScreen.kt`: Audio player view displaying up-next list and control triggers mapped to MediaController commands. Houses the high-accuracy dot-matrix sound visualizer with layout bounds stability, and renders real-time auto-scrolling time-synced lyrics with proximity-based opacity fading.
    - `SettingsScreen.kt`: Interactive links leading to dedicated customization options.
    - `AccountScreen.kt`: Manages optional YouTube Music login WebViews, sign-out actions, and live profile details.
    - `AppearanceScreen.kt`: Styled theme selections (Light, Dark, System).
    - `AiVoiceScreen.kt`: Controls and API keys configuration for Gemini / ElevenLabs backends.
    - `CommentaryScreen.kt`: Customize dynamic list switches like weather updates, song intros, etc.
    - `PlayerAndAudioScreen.kt`: Settings screen for configuring player and audio parameters (such as "Keep screen ON when expanded").
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
- `app/src/main/java/com/tunespark/music/WeatherService.kt`
  - Integrates Open-Meteo's free, no-key-required weather API via OkHttp.
  - Parses the active coordinates from SharedPreferences and returns live temperature, weather codes, and mapped description emojis.
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


### 3. Live Visualizer Refinement & Finalization
- **Radio Screen Visualizer Upgrade**: The dot-matrix Equalizer Waveform in the Radio screen has now been significantly refined to feel much more alive, musical, and premium. Instead of behaving like a flat or fake animated waveform, it now reacts more naturally to the actual character of the playing song through a tuned visual response model inspired by lively CAVA-style visualizer motion.
- **Improved Motion Behavior**: The final visualizer behavior now combines instant peak rise, gravity-based falloff, autosensitivity balancing, and Monstercat-style neighbor smoothing. This allows the bars/dots to rise quickly on strong beats, fall with a more natural weighted decay, and still feel visually connected rather than jittery or random.
- **Shape and Balance Tuning**: The waveform was further tuned to avoid awkward concave shaping and over-dominant edge columns. A softer center bias was introduced to preserve a balanced premium silhouette while still allowing the real audio energy to drive the visual output.
- **Low-End / First-Column Correction**: Special refinement was also made for the earliest FFT bands so the first column no longer appears permanently over-inflated. This resolved the classic low-frequency/DC-bin issue that was making the left-most visualizer bar look unnaturally full.
- **Compact Premium Dot Layout**: The final visualizer presentation was also tightened visually by reducing the effective vertical dot rows to a compact 6-7 level look. This keeps the player UI elegant and less noisy while still preserving strong expressive movement.
- **Result**: The Radio player now contains a much more polished, lively, compact, and premium-looking dotted audio visualizer that better matches the intended product feel and serves as one of the strongest visual identity elements inside TuneSpark's playback experience.

---

## Screen Architecture

TuneSpark has been structured into 13 distinct screens for clear separation of concerns and a native-feeling UX:

1. **Home Screen**:
   - A highly polished, light-themed system dashboard featuring a real-time system clock and localized weather info.
   - Implements two dynamic layouts tailored to the active audio state:
     - **Active Playback Mode**: Displays a large circular play/pause controller, an elegant vector-dot equalizer waveform, a "Stop Radio" action button, and a modern compact bottom mini-player alongside search/playlist controls.
     - **Inactive Mode**: Displays a personalized greeting ("Good Evening, Harsh"), horizontal scrollable tags (Chill, Feel good, Commute, Party), and an outlined "Start Radio" quick-play card, next to a premium 5-page horizontal-scrolling 3x3 Speed Dial grid (populated dynamically with 45 tracks based on user library/listening history when signed in, or curated global hit charts and trending fallback searches when signed out, styled with high-end bottom vertical artwork gradients and custom active-page indicators, and immediately launching song playback with direct radio queue seeding and transition to the Radio screen when clicked).
   - **'From the community' Playlist Row**: A premium customized playlist recommendation engine added to the Inactive Home Screen, showcasing exactly 10 high-relevance playlists.
     - **Data Model (`CommunityPlaylistData`)**: Directly integrates community playlists with pre-fetched song lists to generate a 2x2 collage artwork and display individual song rows inside the card.
     - **Algorithmic Personalized Fetching & Scoring**: Rather than showing raw popularity searches, the engine concurrently pulls 15 community playlist candidates and scores them via `calculateRelevanceScore`. It ranks playlists based on actual user history (song & artist overlaps from `libraryRecentActivity` and `home` recommendations) plus a collaborative relevance boost prioritizing genuine user-made community curators and manual study/workout/chill curations.
     - **Beautiful High-Fidelity UI Row**: Implements a clean title header "From the community" matching the DM Sans typeface, rendering 10 playlists in a swipeable `LazyRow` with "card peek" horizontal scrolling.
     - **Direct Playlist Detail View Overlay**: Clicking on any card opens a premium, full-screen detail overlay (`CommunityPlaylistDetailView`) right on the Home Screen mirroring the style of `PlaylistsScreen.kt`, offering dedicated play/shuffle controls and a complete, clickable song list. Handles system back button integration cleanly via a native Compose `BackHandler`.
     - **Interactive Centered Actions**: Each card offers three action triggers: Play Playlist (vibrant red button with theme-aware high-contrast `MaterialTheme.colorScheme.onPrimary` tinting to prevent black/white invisibility), Start Radio (sensors/wireless button to launch autoplay starting with the first track), and Save to Library (PlaylistAdd button to like the playlist in the background with Toast confirmation).
2. **Search Screen**:
   - Dedicated search layout. Users enter queries, trigger YouTube Music searches, and select a track.
   - Selecting a track immediately triggers playback and transitions to the Radio screen.
3. **Radio Screen**:
   - A dedicated premium audio player view completely distinct from the Home screen, designed to perfectly match the user's provided target design.
   - Features a custom top header bar containing:
     - Left: A solid black circle Back button with a white back arrow to return the user to the Home screen without stopping playback.
     - Center: A Row of split Play/Pause and Skip buttons styled as rounded capsule halves that beautifully and independently elongate their outer curved edges with bouncy spring physics and premium haptics when tapped.
     - Right: A solid red circle Close button with a white "X" that features a confirmation expansion animation (expanding into a beautiful "Stop" capsule upon initial click with safety timeout and haptic feedback) as a second re-input to prevent accidental triggers.
   - Features a central, prominent dot-matrix Equalizer Waveform visualizer located below the top bar and above the song details, which uses physics-simulated bass, mid, and treble components to dance accurately on the song's real-time beats. Stabilized with fixed layout height constraints to prevent content-shaking jitter.
   - Features a Song details section with rounded square artwork on the left, and bold title & artist details on the right.
   - Features a premium, scrollable, styled lyrics block leveraging the integrated `:lrclib` module with microsecond-accurate timestamp parsing. Renders time-synced auto-scrolling using a centered `LazyListState` list, featuring Spotify-style progressive fading (highlighting the currently active spoken line and fading neighboring lines). Hoists lyrics loading in the parent scope for zero-latency in-memory background caching during navigation.
   - Features a capsule Skip button at the very bottom with a white background, black border, a solid black circle on the left containing a skip next icon, and "Skip song" text centered.
4. **Settings Screen**:
   - Offers customization options: *Appearance*, *Account*, *AI and Voice*, *Commentary*, *Player and Audio*, *Location*, and *Updates*.
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
9. **Player and Audio Screen**:
    - Controls configuration settings like keeping the screen on and enabling/disabling the real-time beat visualizer.
    - Employs animated toggle switches equipped with premium audio-click and physical keyboard-tap haptics.
10. **Location Screen**:
    - Features a dynamic root "Enable Location" switch (styled after the custom Player and Audio screen toggle) which completely hides downstream controls when toggled off.
    - When enabled, it displays an "Automatic Location" toggle (defaulting to off).
    - If "Automatic Location" is off (manual state), a preset cities dropdown list is shown along with input text fields for manually setting City/State, Latitude, and Longitude.
    - When "Automatic Location" is on (automatic state), the screen automatically fetches GPS coordinates, hiding manual controls and displaying the current location alongside live weather forecast data.
    - Employs native vector Material icons instead of raw emojis for UI control buttons.
    - Toggling location off completely removes the weather forecast dashboard from the Home Screen.
11. **Updates Screen**:
    - Displays current app version (v1.24.2) and hosts interactive dummy "Check for updates" buttons.
12. **Playlists Screen**:
   - Designed precisely to match reference layouts for both Light and Dark themes.
   - Displays "Date added ↓" sorting options, and a search icon (the top tabs bar was removed as playlists are showing up properly and switching is unnecessary).
  - Features a clean 3-column grid of playlist items. The "Liked" playlist features a prominent red card with a white heart outline icon, while other playlists are rendered with rounded squares using adaptive backgrounds (solid black in Light theme and solid white in Dark theme).
   - Selecting any playlist instantly loads its complete song collection into the background media service, sets `isPlaylistMode` to true, and launches the player view.
13. **Recents Screen**:
   - Dedicated full-screen recently-listened history tracker displaying songs in sectioned dates (like "Today", "Yesterday", and custom date headers), matching high-end design specifications.
   - Connects directly to YouTube Music's `YouTube.musicHistory()` endpoints for authenticated sessions and falls back to a locally-cached database with microsecond precision Unix timestamps.
   - Selecting any track triggers background radio queue seeding and redirects the user immediately to the Radio player screen.

---

## How Playback Works

### Sequential Playback Flow & AI Commentary Injection

To create a radio-host-style music streaming experience (like Spotify's AI DJ mode), TuneSpark integrates dynamic background playback queues with AI commentary and voice synthesis tracks using the Gemini 3.1 Flash and ElevenLabs engines.

#### 1. Dynamic Block Sizing (Commentary Frequency)
The active song block size ($N$) is dynamically parsed from the user's Commentary Frequency setting:
- A float value from the slider UI in the **AI and Voice Settings** screen is mapped to a block size between 1 and 8 songs.
- The UI displays this mapped value in real-time (e.g., "Commentary Frequency: Every 4 songs").
- This block size determines how many consecutive songs play before an AI interlude.

#### 2. Unified Lookahead Refilling and Commentary Generation
Instead of a separate dynamic injection loop, `PlaybackService` unifies queue refilling and commentary injection:
- It monitors the number of upcoming songs in the queue (excluding commentaries).
- When the upcoming songs count drops below 2, it triggers a background refilling operation.
- It fetches the next block of exactly $N$ recommendations.
- It asynchronously generates a context-aware radio host commentary script (25-30 words) using **Gemini 3.1 Flash Preview** describing the upcoming songs and transitions.
- It synthesizes the voice track using the active provider:
  - **Gemini TTS**: Speaks through the prebuilt `Kore` voice.
  - **ElevenLabs**: Speaks using the configured Voice ID (e.g. `EXAVITQu4vr4xnSDxMaL`) and customized high-quality voice settings.
- It builds and appends both the synthesized commentary item (`mediaId` starting with `commentary_`) and the $N$ recommendations as a single, perfectly structured batch (`[Commentary] + [Block of N Songs]`).

#### 3. Clean Playback Service Routing
When the player encounters a commentary item, it routes around standard YouTube recommendations:
- It skips resolving YouTube stream URLs.
- It skips recommending new tracks on the next watch endpoint.
- It continues lookahead pre-fetching for subsequent song tracks, maintaining a flawless, zero-lag transition between the commentary and the next batch of music.

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

---

## Typography and Theme Customization Update

The app has been configured to follow a highly-structured, premium design system with strict color and font specifications:

- **Typography**:
  - Integrated the official Google Fonts provider with GMS secure caching certificates.
  - Set **DM Sans** as the default typography standard across all Jetpack Compose screens.
  - Set the beautiful, digital-styled **Bitcount Single** font family exclusively for the "Tunespark" logo branding on the Home screen header.
- **Color Palette**:
  - Unified the theme to strictly employ **Pure Black**, **Pure White**, and **Red Accent** colors.
  - Designed deep contrast environments for both Light Mode (White-rich) and Dark Mode (Black-rich), with vibrant Red highlights on primary indicators, selections, buttons, and active items.

---

## Completed Milestones & Spacing Harmonization

We have successfully refined and completed the premium user experience across all primary app screens:

### 1. Spacing, Alignment, & Padding Refinements
- **Home Screen Alignment**: Adjusted the vertical center content column's top padding from `80.dp` to `56.dp` and bottom padding from `100.dp` to `72.dp`. This brings elements closer to the status bar and bottom bars, reclaiming visual balance and eliminating excessive blank spaces.
- **Playlist Screen Alignment**: Reduced the top margins inside the detailed playlist view's `LazyColumn` header space from `16.dp` to `0.dp`, aligning content cleanly below the status bar bounds. Adjusted the main grid Column top padding and row vertical spacing to keep the layout snug and perfectly balanced.
- **Radio Screen Alignment**: Replaced double-padding on the outer container Box of the Radio screen from `vertical = 16.dp` to `vertical = 0.dp`. The system safe-insets (`statusBarsPadding()` and `navigationBarsPadding()`) are now strictly leveraged, allowing player controls and lyrics sections to beautifully fit the display heights without clipping or awkward gaps.

### 2. Universal Button Size & Style Harmonization
- **Standardized Capsule Action Buttons**: Main action buttons (e.g., *Stop Radio*, *Start Radio*, *Skip song*) have been standardized to exactly `56.dp` height with perfect capsule corner shapes (`RoundedCornerShape(28.dp)`) and unified border weights (`1.5.dp`).
- **Standardized Tab Navigation Pill Buttons**: Tab navigation pill elements (e.g., *Lyrics/Up Next* on Radio screen) are now standardized to a crisp `40.dp` height and a rounded capsule corner shape (`RoundedCornerShape(20.dp)`), featuring perfectly center-aligned content instead of variable padding values, establishing flawless, high-fidelity UI consistency universally.

### 3. Premium Page-Based 3x3 Speed Dial Integration
- **Dynamic 45-Song Custom Recommendation Engine**: Built-in dynamic data resolution that automatically checks the user's login status. If authenticated, it grabs actual user data from `libraryRecentActivity()`, `home()` personalized recommendations, andLiked songs (`playlist("LM")`). If anonymous, it fetches curated global/regional hits from charts and trending hits fallback searches. It runs supplementary query loops dynamically to guarantee a pool of exactly 45 unique songs.
- **HorizontalPager with pageSpacing**: Developed a beautiful HorizontalPager structure dividing the 45 tracks into 5 tabs of 3x3 grids. Applied `pageSpacing = 16.dp` to introduce an elegant, native transition gap as users swipe between pages/tabs.
- **Eager Playback & Screen Transition**: Integrated a click callback where tapping any card instantly starts playing that specific song, seeds a tailored radio queue, and transitions the active view cleanly to the Radio Screen (`AppScreen.RADIO`).
- **Pulsing Shimmer Skeletons**: Provided a premium infinite-loop shimmer skeleton loading view for the 3x3 grids to handle low-bandwidth and initial API call delays seamlessly.

### 4. Premium 'From the community' Playlist Row
- **Smart Discovery & Scoring**: Upgraded the recommendation model to showcase exactly 10 community-curated playlists. Personalized recommendations are computed concurrently using parallel coroutines, scored dynamically using a collaborative relevance model (mapping user listening history overlaps and user‑made curation signals).
- **Interactive Playlist Overlay**: Integrated a high-fidelity `CommunityPlaylistDetailView` on the Home screen that replicates the full playlist detail view from `PlaylistsScreen.kt`. It supports a BackHandler, custom header, play/shuffle actions, and list of all playable songs.
- **Play/Radio/Save Interactions**: Fully wired with dynamic Media3 background services, including a critical color-contrast fix ensuring play icons render beautifully with proper contrast in both Light (white-on-black) and Dark (black-on-white) themes.

### 5. Premium Recents & Listening History Section
- **Dynamic Dual-Source History Engine**: Developed a smart dual-source recent listening tracker. When signed in, it queries YouTube Music's native history endpoints (`YouTube.musicHistory()`). When signed out (or when logged in history is empty), it seamlessly falls back to a locally cached list persisted securely inside `SharedPreferences` as a JSON array.
- **Automated Listening Capture**: Fully wired up in `MainActivity.kt` inside the Media3 `Player.Listener`'s `onMediaMetadataChanged`. It intercepts played songs dynamically as they begin streaming, skipping any AI Commentary/DJ tracks, and adds them to the local listening history cache instantly.
- **High-Fidelity Recents Horizontal Row**: Integrated a beautiful horizontal-scrolling `LazyRow` featuring compact, highly-polished 120.dp `RecentsCard`s with high-resolution artwork, exact titles, and adaptive secondary styling ("Song • Artist") aligning with DM Sans standard typography and pure black/white theme.
- **Dedicated Recents Screen**: Replaced the overlay with a full, dedicated Recents Screen (`RecentsScreen.kt`) with robust date headers ("Today", "Yesterday", "Tue, Jul 14, 2026") grouping all songs beautifully as shown in design specifications. Handled back integration cleanly and wired song clicks to play instantly, transitions to the Radio screen, and seeds the continuous background autoplay queue.

### 6. Refined History & Personalized Recommendation Engine (Next-Iteration Architecture)
- **Unified 10-Second Listen Rule**: Consolidated the playback listening capture mechanism in `MainActivity.kt` into a single, unified 10-second threshold check that applies identically to both signed-in and signed-out playback sessions. Standardized tracking on explicit state keys (`currentSongTitle, currentSongArtist, isPlaying, playbackState`), eliminating any premature history addition in signed-out sessions.
- **App-Dependent Listening History**: Ensured the listening history displayed inside `RecentsScreen.kt` and `HomeScreen.kt` is strictly app-dependent (local history only). Removed any external cloud‑based music history calls from the recents displays, making the app's history 100% locally-owned.
- **Dynamic Recommendation Threshold Rule**: Introduced a robust seeding threshold rule across Daily Discover, Speed Dial, and Community Playlists recommendation algorithms:
  - If the user's local history contains *fewer than 3 songs* (`localHistory.size < 3`), the algorithms leverage YouTube cloud history/activity as a starting fallback (if `userSignedIn`), or popular global/regional chart feeds (if signed out).
  - As soon as the user listens to *3 or more songs* (`localHistory.size >= 3`) on the app, the recommendation algorithms for all three sections switch entirely to a purely app-dependent local history model. This utilizes the local history's tracks as seeds for watch-next radio queues, artist matches, and community playlist relevance ranking, completely ignoring cloud feeds.
- **Seamless Login Session Carryover**: Refactored `SessionManager.clearSession` to remove only session-specific keys (Google session cookies and account profile details), preserving all user-customized settings (theme, AI Commentary APIs and parameters, player preferences) and the entire local listening history on-device during sign-out and sign-in transitions, ensuring no past list or session data is lost.

### 7. Floating Bottom Bar (Dock) Architecture & Subtle Shadows
- **Directory and Architecture**:
  - The floating bottom dock component is declared as `BottomDock` in `app/src/main/java/com/tunespark/music/ui/screens/HomeScreen.kt`.
  - It is hosted globally in `app/src/main/java/com/tunespark/music/MainActivity.kt` inside the root content view container overlay, meaning it hovers/floats over any active screen view (except when the expanded `RadioScreen` player is active).
  - It aligns seamlessly at `Alignment.BottomCenter`, styled with local system navigation bar insets and custom paddings.
- **Dynamic Playback-State Responsive Layouts**:
  - **Inactive Playback Mode (`isTrackLoaded` is false)**:
    - Renders a horizontal `Row` containing two beautiful `BottomActionButton`s: "Search" (redirects to `AppScreen.SEARCH`) and "Library" (redirects to `AppScreen.PLAYLISTS`), each set to exactly `56.dp` height with capsule shapes (`RoundedCornerShape(30.dp)`).
  - **Active Playback Mode (`isTrackLoaded` is true)**:
    - Renders a horizontal `Row` containing a compact `CircularDockButton` on the left for quick Search access, a central music-playing tile displaying the current track artwork/title/artist (clickable to instantly open the player view `AppScreen.RADIO`), and a compact `CircularDockButton` on the right for Library access.
- **Subtle Shadow Refinement**:
  - To make the floating bar stand out against its backdrops with a clean, high-fidelity premium aesthetic, all components inside the dock are elevated with subtle drop shadows using Jetpack Compose's `.shadow(elevation = 6.dp, shape = ...)` modifier.
  - These shadows are applied explicitly to:
    - `CircularDockButton` with a `CircleShape` shadow boundary.
    - `BottomActionButton` with a `RoundedCornerShape(30.dp)` capsule boundary.
    - The active music-playing tile with a `RoundedCornerShape(30.dp)` capsule boundary.
