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

    external fun addTorrentFileSelected(
        path: String,
        savePath: String,
        selectedIndexes: String
    )

    external fun setTorrentFilePriorities(index: Int, selectedIndexes: String)

    external fun getTorrentFiles(path: String): String

    external fun getDetailedStatus(): String

    external fun pauseAll()

    external fun resumeAll()

    external fun pauseTorrent(index: Int)

    external fun resumeTorrent(index: Int)

    external fun removeTorrent(index: Int, deleteFiles: Boolean)

    external fun getTorrentFilesByIndex(index: Int): String

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
}