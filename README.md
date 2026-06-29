# TorrentOr

<p align="center">
  <img src="assets/banner.png" alt="TorrentOr Banner" width="100%">
</p>

<p align="center">
  <img src="https://img.shields.io/github/stars/a79user-ship-it/TorrentOr?style=for-the-badge" />
  <img src="https://img.shields.io/github/downloads/a79user-ship-it/TorrentOr/total?style=for-the-badge" />
  <img src="https://img.shields.io/github/license/a79user-ship-it/TorrentOr?style=for-the-badge" />
  <img src="https://img.shields.io/badge/Android-10%2B-brightgreen?style=for-the-badge&logo=android" />
  <img src="https://img.shields.io/badge/Kotlin-Native-blueviolet?style=for-the-badge&logo=kotlin" />
</p>

## About

**TorrentOr** is a modern Android torrent client built with **Kotlin**, **Android NDK**, and native **libtorrent**.
It is designed to provide a clean mobile torrenting experience with magnet support, torrent file support, detailed torrent information, real-time speed indicators, and a polished themed interface.

## Features

* Magnet link support
* `.torrent` file support
* Native libtorrent engine
* Download and upload speed indicators
* ETA display
* Enhanced torrent details view
* File selection interface
* Tracker, web seed, pieces, graph, statistics, and comments tabs
* Dark and light theme support
* Multiple color themes
* Android foreground service support
* Optimized native performance using Android NDK

## Screenshots

<p align="center">
  <img src="screenshots/blue-theme.jpg" width="30%" />
  <img src="screenshots/green-theme.jpg" width="30%" />
  <img src="screenshots/red-theme.jpg" width="30%" />
</p>

<p align="center">
  <img src="screenshots/general-tab.jpg" width="30%" />
  <img src="screenshots/files-tab.jpg" width="30%" />
  <img src="screenshots/statistics-tab.jpg" width="30%" />
</p>

## Tech Stack

* Kotlin
* Android SDK
* Android NDK
* CMake
* Native libtorrent
* Material Design UI

## Build

Clone the repository:

```bash
git clone https://github.com/a79user-ship-it/TorrentOr.git
cd TorrentOr
```

Build debug APK:

```bash
./gradlew assembleDebug
```

On Windows:

```bat
gradlew.bat assembleDebug
```

The generated APK will be inside:

```text
app/build/outputs/apk/debug/
```

## License

This project is licensed under the MIT License.

## Disclaimer

TorrentOr is intended for downloading and sharing legal content only.
Do not use this project to download or distribute copyrighted material without permission.
