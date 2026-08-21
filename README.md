<div align="center">

# 🎵 Tunespark

<img width="250" alt="Group 39" src="https://github.com/user-attachments/assets/c127255e-89fc-4221-9c74-cd703f141b93" />

**A modern, open-source Android music streaming player with an AI-powered radio experience.**

Tunespark combines YouTube Music-powered playback with a premium native Android interface, personalized song recommendations, AI Radio commentary, lyrics, and a personalized Discover feed.

> 🚧 Tunespark is an independent open-source project and is currently under active development.

---

## ✨ Screenshots

</div>

<table>
  <tr>
    <td width="33.33%"><img src="https://github.com/user-attachments/assets/85813080-304c-4035-adaf-70e0753dfb4e" width="100%" alt="Frame 9"/></td>
    <td width="33.33%"><img src="https://github.com/user-attachments/assets/d63871bb-2148-4f1e-893b-3a9269ba7109" width="100%" alt="Frame 10"/></td>
    <td width="33.33%"><img src="https://github.com/user-attachments/assets/1efd8ffd-b321-4d88-b752-8854e47ffd17" width="100%" alt="Frame 11"/></td>
  </tr>
  <tr>
    <td width="33.33%"><img src="https://github.com/user-attachments/assets/ef978cd6-12a7-4b3d-a7fa-b240b6c49cba" width="100%" alt="Frame 12"/></td>
    <td width="33.33%"><img src="https://github.com/user-attachments/assets/a058da7b-5207-41f1-b78f-d2a0d5675cf7" width="100%" alt="Frame 13"/></td>
    <td width="33.33%"><img src="https://github.com/user-attachments/assets/c606365f-13ae-43bc-9640-6480998a512c" width="100%" alt="Frame 14"/></td>
  </tr>
  <tr>
    <td width="33.33%"><img src="https://github.com/user-attachments/assets/f8201a65-c216-4be5-a340-0581e8c109cd" width="100%" alt="Frame 15"/></td>
    <td width="33.33%"><img src="https://github.com/user-attachments/assets/b8efbdbe-1d63-42cc-b574-21ccd25ca5ef" width="100%" alt="Frame 16"/></td>
    <td width="33.33%"><img src="https://github.com/user-attachments/assets/0ca95fc0-e66b-4637-9fd5-600061a6dce3" width="100%" alt="Frame 17"/></td>
  </tr>
</table>

---

## ✨ Features

### 🎧 Music & Playback

- YouTube Music-powered music search and playback
- Background playback with Android Media3 / ExoPlayer
- Continuous autoplay radio with dynamic recommendations
- Queue management and playback controls
- Recently played history
- Playlist and library support
- Light, Dark, and System themes

### 🤖 AI Commentary

Tunespark includes an AI-powered radio host designed to make music sessions feel more like a personalized radio station.

Commentary currently includes:

- 🎙️ **Session Opener** — personalized session introductions
- 😄 **Humour** — playful, music-aware commentary
- 📰 **Briefing** — short AI-powered updates based on relevant Discover content
- 🎵 **Music Context** — on-demand stories and background about the currently playing song

The AI commentary can use the current day's context, including listening activity, session information, time, weather, and relevant Discover content. The daily context resets according to the user's local day.

### ✨ Custom Commentary Style

Users can provide their own custom instructions to control how the AI Radio behaves.

For example:

> "Keep it witty, short and slightly sarcastic."

The custom style can influence personality, tone, humour, vocabulary, and overall presentation while the core Radio logic remains intact.

### 📰 Discover feed

A personalized RSS-powered Discover feed with content based on the user's selected interests.

AI summaries are generated only when requested and use article extraction before sending the relevant content to the configured AI model.

### 🎵 Lyrics

- Time-synchronized lyrics
- Automatic scrolling with sync
- LRC-based lyrics support through the integrated `:lrclib` module

### 🔊 AI Voice

Tunespark supports AI-generated voice commentary through configurable AI voice providers such as Gemini and ElevenLabs.

ElevenLabs integration includes:

- Multiple TTS models
- Voice selection
- Free/paid voice classification
- Model-specific voice settings

---

## 🏗️ Tech Stack

- **Kotlin**
- **Jetpack Compose**
- **Material 3**
- **AndroidX Media3 / ExoPlayer**
- **Ktor Client**
- **OkHttp**
- **Kotlinx Serialization**
- **JSoup**
- **JTransforms**
- **LRC / LRCLIB**
- **YouTube Music InnerTube**
- **Gemini**
- **ElevenLabs**
- **RSS / Atom feeds**

---

## 🚀 Getting Started

### Requirements

- Android Studio
- JDK compatible with the project's Gradle configuration
- Android device or emulator
- Internet connection

Some Tunespark features require API keys configured from inside the application, such as AI/TTS functionality.

### Build

Clone the repository:

```bash
git clone https://github.com/ItzHarshXD/Tunespark.git
cd Tunespark
```

Open the project in Android Studio and allow Gradle to sync.

Then build and run the `app` module.

For a signed release APK, use:

**Build → Generate Signed App Bundle / APK → APK → release**

---

## 📱 Releases

Official APK releases are available through GitHub Releases:

**https://github.com/ItzHarshXD/Tunespark/releases**

The project uses versioned GitHub Releases for distributing stable APK builds.

---

## 🤝 Contributing

Contributions, ideas, bug reports, and improvements are welcome!

You can reach out directly via email at **harsh.dev911@gmail.com**.

---

## ⚠️ Disclaimer

Tunespark is an independent open-source project and is not affiliated with, endorsed by, or sponsored by YouTube, YouTube Music, Google, Spotify, Gemini, ElevenLabs, or any other referenced service.

Tunespark uses publicly accessible services and APIs where applicable. Availability of third-party functionality may change independently of this project.

---

<div align="center">

### <a href="https://linktr.ee/harshcodes"><ins>Made with ❤️ by Harsh</ins></a>

</div>
