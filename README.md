# 🎵 Musick - Spotify Music Quiz Game

A fun, interactive music guessing game that transforms your Spotify playlists into engaging multiplayer quizzes. Perfect for parties, gatherings, or testing your music knowledge with friends!

## 🎮 Features

- **Spotify Integration**: Connect with your Spotify account and use any public playlist
- **Multiplayer Support**: Add multiple players with custom names and scoring
- **Interactive Gameplay**: 
  - Pause/play songs with the spinning buzzer button
  - Reveal song information when ready
  - Manual scoring system for flexible gameplay
- **Smart UI**: 
  - Album artwork display with smooth animations
  - Progress bar showing song playback
  - Player score tracking with highlighted leader
- **Playlist History**: Automatically saves your last 5 used playlists for quick access
- **Haptic Feedback**: Vibration feedback for button interactions

## 🚀 Getting Started

Simply download the latest release apk and install it on your device!

## 🎯 How to Play

1. **Login**: Authenticate with your Spotify account
2. **Choose Playlist**: Enter a Spotify playlist URL or select from your recent playlists
3. **Setup Players**: Add player names (2-8 players recommended)
4. **Game Time**:
   - Songs play automatically with shuffle enabled
   - Current player listens and can pause/resume with the buzzer button
   - When ready to guess, pause the song and hit "Reveal" to see the answer
   - Manually adjust scores using +/- buttons
   - Tap the album cover to skip to the next player's turn
5. **Keep Score**: The player with the highest score is highlighted in green

## 🏗️ Technical Details

### Architecture

- **Language**: Kotlin
- **UI Framework**: Android Views with Material Design 3
- **Async Operations**: Kotlin Coroutines
- **Networking**: Retrofit + OkHttp
- **Image Loading**: Glide
- **Local Storage**: SharedPreferences
- **Spotify Integration**: Spotify App Remote SDK + Web API

### Project Structure

```
app/src/main/java/com/example/musick/
├── MainActivity.kt           # Main entry point & Spotify auth
├── PlayerSetupActivity.kt    # Player configuration screen
├── GameActivity.kt          # Core game logic and UI
├── SpotifyManager.kt        # Spotify authentication & connection
├── SpotifyApiClient.kt      # Retrofit API client
└── ui/                      # UI theme components
```

### Key Dependencies

```kotlin
// Spotify Integration
implementation("com.spotify.android:auth:2.1.0")
implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))

// Networking
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// UI & Images
implementation("androidx.compose.material3:material3")
implementation("com.github.bumptech.glide:glide:4.16.0")

// JSON Processing
implementation("com.google.code.gson:gson:2.10.1")
```

## 🔧 Configuration

### Spotify App Setup

1. Go to [Spotify Developer Dashboard](https://developer.spotify.com/dashboard/)
2. Create a new app
3. Add these settings:
   - **Redirect URI**: `com.example.musick://callback`
   - **Package Name**: `com.example.musick`
   - **SHA1 Fingerprint**: Your app's signing fingerprint

### Permissions Required

- `INTERNET`: For Spotify API calls
- `VIBRATE`: For haptic feedback during gameplay

## ⚖️ Legal

This app uses the Spotify Web API and App Remote SDK. Users must have a Spotify Premium account for full functionality. This app is not affiliated with Spotify AB.

## 👨‍💻 Author

**hijox** - [GitHub Profile](https://github.com/hijox)

## 🙏 Acknowledgments

- [Spotify Web API](https://developer.spotify.com/documentation/web-api/) for music data
- [Spotify App Remote SDK](https://developer.spotify.com/documentation/android/) for playback control
- [Material Design](https://material.io/) for UI components
- All the amazing artists whose music makes this game possible! 🎶

***

**Enjoy the music and have fun playing Musick!** 🎵🎮

*If you encounter any issues or have suggestions, please [open an issue](https://github.com/hijox/musick/issues) on GitHub.*
