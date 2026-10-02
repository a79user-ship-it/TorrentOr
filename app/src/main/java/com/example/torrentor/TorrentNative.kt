package com.example.torrentor

object TorrentNative {

    init {
        System.loadLibrary("torrent-rasterbar")
        System.loadLibrary("torrentor")
    }

    external fun startSession(savePath: String)
    external fun addMagnet(magnet: String, savePath: String)
    external fun addMagnetPaused(magnet: String, savePath: String): Int
    external fun addTorrentFile(path: String, savePath: String)
    external fun addTorrentFilePaused(path: String, savePath: String)

    external fun addTorrentFileSelected(path: String, savePath: String, selectedIndexes: String)
    external fun addTorrentFileSelectedPaused(path: String, savePath: String, selectedIndexes: String)

    external fun setTorrentFilePriorities(index: Int, selectedIndexes: String)
    external fun getTorrentFiles(path: String): String
    external fun getDetailedStatus(): String

    external fun pauseAll()
    external fun resumeAll()
    external fun pauseTorrent(index: Int)
    external fun resumeTorrent(index: Int)
    external fun removeTorrent(index: Int, deleteFiles: Boolean)
    external fun removeAllTorrents(deleteFiles: Boolean)

    external fun getTorrentFilesByIndex(index: Int): String
    external fun getTorrentFileProgress(index: Int): String
    external fun getTorrentTrackers(index: Int): String
    external fun getTorrentTrackerStatus(index: Int): String
    external fun getTorrentWebSeeds(index: Int): String
    external fun getTorrentWebSeedCount(index: Int): String
    external fun getTorrentTrackerHost(index: Int): String
    external fun getTorrentTotalSize(index: Int): String
    external fun getTorrentPeers(index: Int): String
    external fun getTorrentMagnet(index: Int): String
    external fun getTorrentHash(index: Int): String
    external fun getTorrentComment(index: Int): String
    external fun getTorrentCreator(index: Int): String
    external fun getTorrentCreationDate(index: Int): String
    external fun isPrivateTorrent(index: Int): Boolean
    external fun getTorrentEncoding(index: Int): String
    external fun getTorrentSource(index: Int): String
    external fun getTorrentAvailability(index: Int): String
    external fun getTorrentSwarmHealth(index: Int): String
    external fun hasTorrentHash(hash: String): Boolean
    external fun getTorrentFileHash(path: String): String
    external fun forceRecheck(index: Int)
    external fun forceReannounce(index: Int)
    external fun getTorrentPieces(index: Int): String
    external fun getTorrentPieceSize(index: Int): String
    external fun getTorrentStatistics(index: Int): String
    external fun getGlobalStatistics(): String
    external fun getPortForwardingStatus(): String
    external fun getDhtStatus(): String
    external fun setDhtEnabled(enabled: Boolean)
    external fun getPexStatus(): String
    external fun setPexEnabled(enabled: Boolean)
    external fun getLsdStatus(): String
    external fun setLsdEnabled(enabled: Boolean)
    external fun getNetworkFeaturesStatus(): String

    // Feature 4: Connection settings (uTP/TCP/UPnP/NAT-PMP/listen port).
    // Safe to call before or after startSession() - see applyConnectionSettings
    // in torrentor.cpp for exactly how each case is handled.
    external fun applyConnectionSettings(
        utpIn: Boolean,
        utpOut: Boolean,
        tcpIn: Boolean,
        tcpOut: Boolean,
        upnpEnabled: Boolean,
        natpmpEnabled: Boolean,
        listenPort: Int
    )

    external fun getConnectionStatus(): String

    // Feature 3: Sequential Download & First/Last Piece Priority.
    // Identified by info-hash (never list index) per the project's
    // persistence rules. Each setter/getter returns false - never
    // throws - when the hash isn't found, or (first/last priority only)
    // when the torrent's metadata isn't ready yet; it never crashes.
    external fun setSequentialDownload(hash: String, enabled: Boolean): Boolean
    external fun getSequentialDownload(hash: String): Boolean
    external fun setFirstLastPiecePriority(hash: String, enabled: Boolean): Boolean
    external fun getFirstLastPiecePriority(hash: String): Boolean

    // Feature 1: Speed Control (global limits + per-torrent override,
    // no scheduling). All limits are bytes/second; 0 means unlimited.
    // The per-torrent pair is identified by info-hash (never list
    // index) and returns "" from the getter / false from the setter -
    // never throws - when the hash isn't found.
    external fun applyGlobalSpeedLimits(uploadLimit: Int, downloadLimit: Int)
    external fun getGlobalSpeedLimits(): String
    external fun setTorrentSpeedLimits(hash: String, uploadLimit: Int, downloadLimit: Int): Boolean
    external fun getTorrentSpeedLimits(hash: String): String
}
