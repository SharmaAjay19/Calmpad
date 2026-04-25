# CalmPad

A calm, minimal note-taking Android app inspired by [Calm OneNote](https://haikubits.github.io/selfhosting/calmpad/).

## Features

- **Sections** — Organize notes into tabs
- **Rich editing** — Title + freeform content per note
- **Drag-and-drop reorder** — Long-press to move notes up/down
- **Search** — Full-text search across all notes
- **4 Themes** — Light, Sepia, Dim, Dark
- **3 Fonts** — Sans, Serif, Mono
- **Auto-save** — Debounced saves with status indicator
- **Version history** — Keeps last 5 snapshots
- **Backup / Import** — JSON export and import
- **PDF / Print** — Print single notes or entire notebook

## Tech Stack

- Kotlin + Jetpack Compose (Material 3)
- Room Database for storage
- DataStore Preferences for settings
- Hilt for dependency injection
- MVVM architecture

## Build

```
./gradlew assembleDebug
```
