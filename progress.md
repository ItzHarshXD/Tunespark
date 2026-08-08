# TuneSpark Open-Source Music Streaming Player
# Task Progress: Centralized AI Commentary Context System + ElevenLabs Advanced Model/Language/Voice Selection

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
- `app/src/main/java/com/tunespark/music/rss/`
  - **RSS-Powered Discover Feed** package:
    - `RssConfig.kt`: Centralized RSS feed configuration. All feed URLs for every interest category are defined here so new sources can be added or existing ones changed easily. Each interest aggregates from multiple high-quality, actively maintained public RSS feeds.
    - `Article.kt`: Normalized article model with title, description, thumbnail, source, article URL, published date, and category. Includes a `timeAgo()` helper for relative timestamps.
    - `RssParser.kt`: Parses RSS 2.0 and Atom XML feeds. Extracts thumbnails from `media:content`, `media:thumbnail`, `enclosure`, `itunes:image`, and HTML `<img>` fallback. Handles multiple date formats.
    - `RssRepository.kt`: Fetches all relevant RSS feeds concurrently, parses XML, merges articles, removes duplicates, sorts by newest first, and caches results for ~25 minutes. Individual feed failures are isolated so one bad feed never breaks the whole Discover feed.
- `app/src/main/java/com/tunespark/music/SessionManager.kt`
  - Manages secure, local persistence of YouTube session cookies in `SharedPreferences`.
  - Handles locally-cached user profile details (avatar, name, email) and initializes the active session cookie in `:innertube` on app startup.
  - Manages Discover interest category preferences (`getDiscoverCategories`, `saveDiscoverCategory`).
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

---

## Centralized AI Commentary Context System (Latest Milestone)

### 1. `CommentaryContext.kt` — Daily Context Data Model
- **Single Source of Truth**: A single immutable data class that represents a snapshot of all relevant information for the current day that any AI commentary feature can request.
- **Daily Scoping**: The context is scoped to the current calendar day (00:00–23:59 in the user's local timezone) via a `dateKey` like `"2026-08-04"`.
- **Rich Context Fields**:
  - `dateKey` — Day key used to detect day rollover.
  - `currentTime` — Human-readable current time like "9:43 PM".
  - `timeOfDay` — Time-of-day bucket: "morning", "afternoon", "evening", or "night".
  - `userName` — The signed-in user's display name, if available.
  - `todaySongs` — Songs listened to today (most recent first), with timestamps.
  - `weather` — Current weather at the user's saved location, if enabled.
  - `discoverArticles` — Relevant Discover feed articles for the user's interests.
  - `sessionStartTime` — Epoch millis when the current listening session started.
  - `songsPlayedThisSession` — Number of songs played in the current session.
  - `currentSong` — The currently playing song as "'Title' by Artist", or null.
  - `upcomingSongs` — Upcoming songs as "'Title' by Artist" strings.
- **Companion Helpers**: `currentDateKey()`, `currentTimeString()`, and `currentTimeOfDay()` compute the current day/time values in the user's local timezone.

### 2. `CommentaryContextManager.kt` — Centralized Context Service
- **Single Reusable Service**: The one place that gathers and maintains all relevant information for the current day, acting as the foundation for every AI commentary feature (Session Opener, Humour, Briefing, Music Context).
- **Automatic Daily Reset**: Detects when a new day begins (via `dateKey` comparison) and automatically resets session metadata (session start time, songs played counter).
- **Continuous Updates**: The context is refreshed as the user listens. `recordSongPlayed()` is called by the playback pipeline whenever a real song (not a commentary) starts, keeping the session counter and context fresh.
- **Modular & Scalable**: New context sources can be added as fields on `CommentaryContext` without changing the commentary generation pipeline.
- **Smart Caching**:
  - Weather is cached for 10 minutes to avoid excessive API calls.
  - RSS articles leverage `RssRepository`'s existing 25-minute cache.
  - Network-backed sources (weather, RSS) are refreshed asynchronously in the background so the returned context is always immediately usable.
- **Context Prompt Builder**: `buildContextPrompt()` converts the context into a human-readable summary for inclusion in the AI prompt, giving the AI full awareness of the user's day (time, weather, listening history, session metadata, and relevant news headlines).

### 3. Integration into the Commentary Generation Pipeline
- **`TtsService.kt`**: Added an optional `contextPrompt` parameter to both `generateCommentaryScript()` and `generateCommentaryAudio()`. When provided, the context is injected into the Gemini prompt so the AI host is aware of the user's day.
- **`PlaybackService.kt`**:
  - `handleCurrentMediaItem()` now calls `CommentaryContextManager.recordSongPlayed()` whenever a real song (not a commentary) becomes current, keeping the session counter accurate.
  - Both `checkAndInsertPlaylistCommentary()` (playlist mode) and `createCommentaryItem()` (radio/autoplay mode) now build the centralized daily context and pass it to `TtsService.generateCommentaryAudio()`.
- **`MainActivity.kt`**: Both the `playPlaylist()` and `playSong()` session opener paths now build the centralized daily context and pass it to `TtsService.generateCommentaryAudio()` for the "AI DJ Welcome" session opener commentary.

### 4. Refined Prompt System — Session Opener vs. Between-Songs (Implemented)
The AI commentary now uses **two distinct prompt templates** based on the `isSessionOpener` flag, ensuring elements don't mix up:

#### Session Opener Prompt (`isSessionOpener = true`)
- This is the **first** commentary when the user starts a session — the "AI DJ Welcome".
- **ONLY here** does the AI:
  - Greet the user by name (from context)
  - Mention the time of day with a greeting ("Good morning", "Good evening", etc.)
  - Mention the weather (if available)
  - Welcome them to the session
- The context is provided with instructions to use time, weather, and name for the greeting, and listening history to personalize the opener.
- **No city name**: The AI is explicitly told to describe weather conditions (e.g. "it's a sunny afternoon") but NOT say the city/location name.
- Called from `MainActivity.kt` in both `playSong()` and `playPlaylist()` with `isSessionOpener = true`.

#### Between-Songs Prompt (`isSessionOpener = false`)
- This is a **short transition** between tracks — not a session opener.
- The AI is **explicitly forbidden** from:
  - Greeting the user or saying "welcome back/to"
  - Mentioning or addressing the user by name
  - Mentioning the time of day or any time-based greeting
  - Mentioning the weather
  - Repeating any session-opening language
- The context is still provided but as **BACKGROUND AWARENESS ONLY** — the AI uses listening history and session metadata to personalize the transition, but does NOT repeat greetings, name, time, or weather.
- **No "playlist" language**: The AI is explicitly forbidden from calling the upcoming songs a "playlist", "set", "collection", or "queue" — they are simply the next songs playing. The word "playlist" is banned in between-songs commentary.
- Called from `PlaybackService.kt` in both `checkAndInsertPlaylistCommentary()` and `createCommentaryItem()` with `isSessionOpener = false`.

### 5. Humour Commentary Element — Roasting Style (Implemented)
- **User-Selectable Element**: The "Humour" commentary element, when toggled ON, adds a **roasting-style** humour to the commentary.
- **Roasting, Not Mild Jokes**: The prompt now instructs the AI to:
  - Roast the user's music taste, listening habits, or song/artist choices
  - Be "a little savage — like a best friend who lovingly mocks you"
  - Use playful insults about song choices, cheeky observations about listening patterns from the context, or witty jabs at the artists
  - Keep it fun and never genuinely mean-spirited, but "don't be boring or overly safe either"
  - Weave the roast naturally into the commentary, not as a separate joke segment
  - Dark humour is welcome — no restrictions on humour type
- **Present in Both Types**: The humour element is active in both session opener and between-songs commentary.
- **Independent & Non-Mixing**: Each element is a separate instruction block in the prompt. The humour instruction is self-contained and doesn't interfere with the session-opener/between-songs structure.

### 6. Resilient Commentary Injection (Bug Fix)
- **Problem**: When commentary generation failed (API error, timeout, rate limit), the next set of songs was never added to the queue — playback would stop because the old code generated commentary *before* adding songs, and if commentary threw an exception, the song-adding code was never reached.
- **Fix**: Restructured `seedRecommendations()` in `PlaybackService.kt` into two steps:
  1. **Songs first**: Songs are added to the ExoPlayer queue **immediately** so playback never stops, regardless of what happens with commentary.
  2. **Commentary second**: Commentary is generated (if enabled) and then inserted *before* the already-queued songs by finding the first appended song's position. If commentary fails, songs are already in the queue and playback continues seamlessly without commentary.
- **Result**: Playback is now resilient — even if the Gemini API is down, rate-limited, or times out after 3 retries, the music keeps playing. The user just skips the commentary interlude and goes straight to the next songs.

### 7. What's Implemented vs. What's Coming
- **Implemented now**: Session opener commentary (with greeting/name/time/weather), typical between-songs commentary (no greeting), the **Humour** roasting element, the **AI Briefing** news summary element (fully isolated from humor, with dedicated scraping filters, repetition avoidance, and "AI Briefing" title), and resilient commentary injection that never blocks playback — all using the centralized context system with separated prompts.
- **Not yet implemented** (per user instruction): Music Context commentary element. This will be added later as a separate element block in the prompt.

---

## ElevenLabs Advanced Model / Language / Voice Selection (Latest Milestone)

The ElevenLabs section of the **AI and Voice** settings screen was overhauled from a simple hardcoded model dropdown + manual Voice ID text field into a fully API-driven, advanced selection experience.

### 1. Three Supported Models with Descriptions
- The old hardcoded list of 7 models was replaced with exactly **3 supported models**, each shown with its display name and a short description below the selection:
  1. **Eleven v3** (`eleven_v3`) — Flagship model, 70+ languages, 5000 char limit, supports emotional audio tags like `[excited, laughing]`, `[pause]`, `[sighs]`. Best for expressive, lifelike voice synthesis.
  2. **Eleven Multilingual v2** (`eleven_multilingual_v2`) — Stable, lifelike model, 29 languages, 10000 char limit. Great for long-form content with consistent quality.
  3. **Eleven Flash v2.5** (`eleven_flash_v2_5`) — Fastest model, ~75ms latency, 32 languages, 40000 char limit, 50% cheaper. Ideal for real-time applications.
- The selected model's description is rendered below the dropdown so the user understands the trade-offs.
- When **Eleven v3** is selected, an extra amber tip line is shown describing v3-specific features (emotional audio tags, punctuation cheat codes for pacing, and the auto-tuned stability slider).

### 2. Searchable Multi-Select Language Dropdown (API-Driven)
- A new **Language** dropdown was added that is populated by querying the ElevenLabs `GET /v1/models` endpoint and extracting the `languages` array for the currently selected model.
- The dropdown includes a **search field** (with a search icon) that filters languages by name or language ID in real time.
- Users can **select one or multiple languages** via checkboxes (red accent). Selected languages are shown as a comma-separated list in the field.
- If no language is selected, the field displays "Auto-detect (no language selected)" and the `language_code` parameter is omitted from the TTS request so the API auto-detects.
- When the user switches models, the language list is re-fetched and any previously selected languages that are no longer supported by the new model are automatically pruned.
- Loading and error states are handled gracefully inside the dropdown.

### 3. API-Driven Voice Selection with Free/Paid Classification
- The manual Voice ID text field was replaced with a **Voice** dropdown that queries the ElevenLabs `GET /v2/voices` endpoint.
- Each voice row displays:
  - **Voice name** (bold)
  - **Voice ID** (gray, small)
  - **Category** (gray, smaller — e.g. `premade`, `cloned`, `generated`, `professional`, `default`, `community`)
  - A **FREE** (green) or **PAID** (amber) badge on the right
- **Voice classification rule** (in `TtsService.classifyVoiceAsFree`):
  - `premade` / `default` → **FREE**
  - `cloned` / `professional` → **PAID** (requires Starter+)
  - `generated` / `community` / other → **FREE** if `"free"` is in `available_for_tiers`, else **PAID**
- The dropdown includes a **search field** that filters voices by name, voice ID, or category.
- Voices auto-load the first time the dropdown is opened (if not already loaded and no prior error). A "Tap to retry" option is shown if the fetch fails.
- Selecting a voice saves its `voice_id` to `SessionManager` (same key as before, so existing selections carry over).

### 4. TtsService Updates
- **New data classes**: `ElevenLanguage`, `ElevenModel`, `ElevenVoice` (all inside `TtsService`).
- **New API helpers**:
  - `fetchElevenLabsModels(apiKey)` — GET `https://api.elevenlabs.io/v1/models` with `xi-api-key` header, parses model_id, name, description, and languages array.
  - `fetchElevenLabsVoices(apiKey)` — GET `https://api.elevenlabs.io/v2/voices` with `xi-api-key` header, parses voice_id, name, category, available_for_tiers, and computes `isFree`.
- **`generateElevenLabsTts()`** now:
  - Uses the user-selected model ID from `SessionManager.getSelectedElevenLabsModelId()` instead of the hardcoded `"eleven_multilingual_v2"`.
  - Sends `language_code` (the first selected language) when the user has selected one or more languages; omits it otherwise for auto-detection.
  - Applies **per-model voice settings tuning**:
    - `eleven_v3` → stability 0.50, similarity_boost 0.80, style 0.30 (higher stability for consistent emotional delivery)
    - `eleven_flash_v2_5` → stability 0.35, similarity_boost 0.75, style 0.40 (slightly lower stability for speed)
    - `eleven_multilingual_v2` / fallback → stability 0.38, similarity_boost 0.78, style 0.38 (original balanced defaults)

### 5. SessionManager Updates
- New keys and methods:
  - `KEY_SELECTED_ELEVENLABS_MODEL_ID` + `getSelectedElevenLabsModelId()` / `saveSelectedElevenLabsModelId()` — persists the model ID (e.g. `eleven_v3`).
  - `KEY_SELECTED_ELEVENLABS_LANGUAGES` + `getSelectedElevenLabsLanguages()` / `saveSelectedElevenLabsLanguages()` — persists the set of selected language IDs as a `StringSet`.
- The existing `getSelectedElevenLabsModel()` / `saveSelectedElevenLabsModel()` (display name) methods are kept for backward compatibility and updated alongside the new model ID methods when the user selects a model.

### 6. UI / UX Details
- All new dropdowns follow the existing screen styling: `OutlinedTextField` with transparent background, `RoundedCornerShape(30.dp)`, red accent color `Color(0xFFFF0000)` for checkboxes/selections, and haptic feedback + sound effects on every interaction.
- The language and voice dropdowns are capped at `400.dp` max height with internal scrolling.
- The voice dropdown auto-loads voices on first open; the language dropdown auto-loads whenever the model or API key changes via a `LaunchedEffect`.

---

## RSS-Powered Discover Feed (Previous Milestone)

### 1. Centralized RSS Configuration (`RssConfig.kt`)
- **Single Source of Truth**: All RSS feed URLs are centralized in one configuration file. New sources can be added or existing ones changed without touching any other code.
- **Multi-Source Aggregation**: Each of the 16 interest categories (AI, Tech, Space, Science, Cars & EVs, Gaming, Movies & TV, Business & Startups, Finance, Mind & Productivity, World, Music, Sports, Fashion, Food, Travel) aggregates from **4 high-quality, actively maintained public RSS feeds** each, for a total of 64 feeds.
- **Reliable Sources**: Sources include MIT Technology Review, The Verge, TechCrunch, NASA, Space.com, ScienceDaily, Nature, Electrek, IGN, Polygon, Variety, The Hollywood Reporter, CNBC, MarketWatch, BBC World, CNN, Pitchfork, Rolling Stone, Billboard, ESPN, Vogue, Hypebeast, Serious Eats, Bon Appétit, Lonely Planet, Condé Nast Traveler, and more.

### 2. Normalized Article Model (`Article.kt`)
- **Common Model**: All RSS feeds are parsed and normalized into a single `Article` data class with:
  - `title` — Article headline
  - `description` — Plain-text preview (HTML stripped)
  - `thumbnail` — Best available image URL
  - `source` — Display name of the publishing source
  - `url` — Direct link to the full article
  - `publishedDate` — Unix timestamp in milliseconds
  - `category` — The interest category this article belongs to
- **Relative Time Helper**: `timeAgo()` converts the timestamp to human-readable strings like "2h ago", "3d ago", "1w ago".

### 3. Robust RSS Parser (`RssParser.kt`)
- **RSS 2.0 & Atom Support**: Parses both `<item>` (RSS) and `<entry>` (Atom) elements.
- **Thumbnail Extraction**: Extracts images from `media:content`, `media:thumbnail`, `enclosure`, `itunes:image`, and falls back to the first `<img>` tag found inside the description HTML.
- **Placeholder Fallback**: If no image is available, uses the app's existing placeholder image.
- **Multi-Format Date Parsing**: Handles 10+ common RSS/Atom date formats including RFC 822, ISO 8601, and Java `Date.toString()`.
- **HTML Stripping**: Cleans descriptions by removing HTML tags and decoding common HTML entities.

### 4. Smart Repository with Caching (`RssRepository.kt`)
- **Interest-Based Feed Selection**: Reads the user's enabled interest categories from `SessionManager` and resolves the relevant RSS sources from `RssConfig`.
- **Concurrent Fetching**: Fetches all feeds concurrently using coroutines for maximum speed.
- **Failure Isolation**: If one or more RSS feeds fail, the remaining feeds still load without affecting the overall Discover feed.
- **Merge & Deduplicate**: Merges articles from all feeds, removes duplicates by article ID, and sorts by newest first.
- **25-Minute Cache**: Caches fetched articles in `SharedPreferences` for ~25 minutes to avoid re-downloading RSS feeds every time the app is opened.
- **Cache Invalidation**: The cache is automatically cleared when the user changes their interest preferences in the Discover Feed settings screen.

### 5. Fully Functional Discover Screen (`DiscoverScreen.kt`)
- **Real RSS Content**: Replaced all dummy articles with real RSS-powered content loaded from the repository.
- **Identical UI**: Kept the exact same UI, layout, navigation, and user experience — header row with back button, "Discover" title, settings icon, "Today's Highlights" section, and article rows with 72dp thumbnails.
- **Loading Skeletons**: Shows skeleton placeholders while articles are being fetched.
- **Empty State**: Displays a friendly message when no articles are available.
- **Article Click**: Tapping an article opens the full article URL in the system browser.

### 6. Home Screen Daily Discover Carousel
- **Real Articles**: Replaced the dummy Discover carousel on the Home screen with the latest fetched RSS articles.
- **10-Article Limit**: Shows exactly 10 articles in the horizontal-scrolling `LazyRow` carousel.
- **Loading State**: Displays skeleton placeholders while articles load.
- **Pull-to-Refresh**: The Discover carousel refreshes when the user pulls down to refresh the Home screen.
- **"Show all" Navigation**: The "Show all" button still navigates to the full Discover screen.

### 7. Discover Feed Settings Integration
- **Cache Invalidation on Interest Change**: When the user toggles any interest category in the Discover Feed settings screen, the RSS cache is cleared so the next fetch reflects the new preferences.

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
   - **RSS Discover Carousel**: A horizontal-scrolling carousel showing the latest 10 RSS articles from the user's selected interest categories, with skeleton loading states and pull-to-refresh support.
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
   - Offers customization options: *Appearance*, *Account*, *AI and Voice*, *Commentary*, *Player and Audio*, *Location*, *Discover feed*, and *Updates*.
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

### 8. RSS-Powered Discover Feed (Current Milestone)
- **Centralized RSS Configuration**: All 64 RSS feed URLs across 16 interest categories are centralized in `RssConfig.kt`.
- **Normalized Article Model**: `Article.kt` provides a common model with title, description, thumbnail, source, URL, published date, and category.
- **Robust RSS Parser**: `RssParser.kt` handles RSS 2.0 and Atom feeds with multi-format date parsing and thumbnail extraction from `media:content`, `media:thumbnail`, `enclosure`, `itunes:image`, and HTML fallback.
- **Smart Repository with Caching**: `RssRepository.kt` fetches feeds concurrently, isolates failures, merges/deduplicates/sorts articles, and caches for 25 minutes.
- **Fully Functional Discover Screen**: Replaced all dummy content with real RSS-powered articles while keeping the exact same UI, layout, navigation, and user experience.
- **Home Screen Daily Discover Carousel**: Shows the latest 10 fetched articles in the horizontal carousel with skeleton loading and pull-to-refresh support.
- **Interest-Based Personalization**: Uses the user's selected interests from the Discover Feed settings to determine which RSS sources are included.
- **Cache Invalidation**: Changing interest preferences clears the RSS cache so the next fetch reflects the new selections.
- **Smart, Casual AI Summaries**: Rewrote the AI generation prompt to provide clean, easy-to-read, casual summaries designed for normal users. It displays a short takeaway sentence (with no "Summary:" or other labels) followed by a few scannable points, strictly avoiding academic jargon, asterisks, or markdown classifications.
- **Robust Unscraped Fallback System**: Added a fixed sentinel string ("Unable to get data") for any articles where scraping fails. The prompt instructs the model to return this sentinel if page extraction is unsuccessful, allowing the UI to reliably identify unscraped articles and offer an elegant fallback message with a solid, high-contrast, fully-clickable "Open Article" button.
- **Multiple Concurrent Summaries**: Refactored the UI state to use a set of expanded URLs, allowing users to open and view multiple article summaries simultaneously without automatically closing previous ones.
- **Seamless Inline Card Integration**: Redesigned the summary container to sit natively inside the article card flow as a natural extension, rather than as a separate boxed card background. All content inside the article card is cleanly aligned with standard `12.dp` padding.
- **Enhanced AI Action Buttons**: Upgraded the AI summary action buttons across the Home screen's Discover carousel and the full Discover screen. They now feature a high-contrast primary red accent circle with `onPrimary` icons, and their sizes have been boosted (from 28dp/32dp to 36dp with 20dp icon size) for excellent touch targets and premium aesthetics.
- **Enlarged Home Screen Discover Carousel Cards**: Increased the dimensions of the Discover article cards on the Home Screen carousel from 240dp width & 140dp height to a more striking 280dp width & 160dp height (with updated shimmer skeleton states), giving the imagery and text headlines breathing room.
