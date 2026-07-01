# TorrentOr

<p align="center">
  <img src="./assets/banner.png" alt="TorrentOr Banner" width="100%">
</p>

<h1 align="center">TorrentOr</h1>

<p align="center">
  <b>Modern Android Torrent Client powered by Native libtorrent</b>
</p>

<p align="center">
  <img src="https://img.shields.io/github/stars/a79user-ship-it/TorrentOr?style=for-the-badge" alt="Stars">
  <img src="https://img.shields.io/github/downloads/a79user-ship-it/TorrentOr/total?style=for-the-badge" alt="Downloads">
  <img src="https://img.shields.io/github/license/a79user-ship-it/TorrentOr?style=for-the-badge" alt="License">
  <img src="https://img.shields.io/badge/Android-10%2B-brightgreen?style=for-the-badge&logo=android" alt="Android">
  <img src="https://img.shields.io/badge/Kotlin-Native-blueviolet?style=for-the-badge&logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Powered%20by-libtorrent-orange?style=for-the-badge" alt="libtorrent">
</p>

---

## About

TorrentOr is a modern Android torrent client built with **Kotlin**, **Android NDK**, and the native **libtorrent** engine.

It provides a fast, lightweight, and polished torrent experience on Android with support for magnet links, torrent files, advanced torrent information, multiple themes, and native download management.

---

## Features

* Native libtorrent backend
* Magnet Link support
* Torrent File support
* Download & Upload speed indicators
* ETA (Estimated Time Remaining)
* Real-time torrent statistics
* Enhanced torrent details
* File selection before downloading
* Tracker management
* Web Seed support
* Swarm Health view
* Pieces information
* Comments section
* Pause / Resume torrents
* Delete torrents with or without downloaded files
* Multiple color themes
* AMOLED theme
* Android Foreground Service
* Native C++ performance using Android NDK

---

# Screenshots

## Theme Gallery

<p align="center">
  <img src="screenshots/blue-theme.jpg" width="23%" alt="Blue Theme"/>
  <img src="screenshots/green-theme.jpg" width="23%" alt="Green Theme"/>
  <img src="screenshots/red-theme.jpg" width="23%" alt="Red Theme"/>
  <img src="screenshots/orange-theme.jpg" width="23%" alt="Orange Theme"/>
</p>

<p align="center">
  <img src="screenshots/purple-theme.jpg" width="23%" alt="Purple Theme"/>
  <img src="screenshots/rose-theme.jpg" width="23%" alt="Rose Theme"/>
  <img src="screenshots/black-theme.jpg" width="23%" alt="Black Theme"/>
  <img src="screenshots/amoled-theme.jpg" width="23%" alt="AMOLED Theme"/>
</p>

---

## Torrent Details

<p align="center">
  <img src="screenshots/general-tab.jpg" width="23%" alt="General Tab"/>
  <img src="screenshots/file-tab.jpg" width="23%" alt="Files Tab"/>
  <img src="screenshots/trackers-tab.jpg" width="23%" alt="Trackers Tab"/>
  <img src="screenshots/web-seed-tab.jpg" width="23%" alt="Web Seed Tab"/>
</p>

<p align="center">
  <img src="screenshots/pieces-tab.jpg" width="23%" alt="Pieces Tab"/>
  <img src="screenshots/statistics-tab.jpg" width="23%" alt="Statistics Tab"/>
  <img src="screenshots/swarm-health-tab.jpg" width="23%" alt="Swarm Health Tab"/>
  <img src="screenshots/comment-tab.jpg" width="23%" alt="Comment Tab"/>
</p>

---

## Tech Stack

* Kotlin
* Android SDK
* Android NDK
* Native C++
* libtorrent
* CMake
* Gradle
* Material Design Components

---

## Requirements

* Android 10+
* Android Studio
* Android NDK
* CMake
* JDK 17

---

## Building

Clone the repository:

```bash
git clone https://github.com/a79user-ship-it/TorrentOr.git
cd TorrentOr
```

### Windows

```cmd
gradlew.bat assembleDebug
```

### Linux / macOS

```bash
./gradlew assembleDebug
```

Generated APK:

```text
app/build/outputs/apk/debug/
```

---

## Contributing

Contributions, bug reports, and feature requests are welcome. Feel free to open an issue or submit a pull request.

---

## License

This project is licensed under the MIT License.

---

## Disclaimer

TorrentOr is intended for downloading and sharing legal content only.

Users are responsible for complying with the copyright laws applicable in their country.

---

<p align="center">

Made with ❤️ using Kotlin, Android NDK and native libtorrent.

</p>

## Known Issues

TorrentOr is still under active development. The following issues are currently known:

### Paused Torrents Resume Automatically

In some cases, torrents that have been manually paused may automatically resume after restarting the app. This behavior is unintended and is being investigated.

### Previously Deleted Torrents Reappear

Torrents that have been deleted may reappear after installing the app on another device or after a fresh installation. This is a known issue related to torrent state persistence and will be fixed in a future update.

These issues are known and are being actively worked on. Thank you for your patience and support.
