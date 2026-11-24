#include <cstdlib>
#include <set>
#include <jni.h>
#include <string>
#include <memory>
#include <vector>
#include <mutex>
#include <thread>
#include <chrono>
#include <deque>
#include <sstream>
#include <algorithm>
#include <cctype>
#include <cstdio>
#include <ctime>
#include <android/log.h>
#include <fstream>
#include <iterator>
#include <chrono>
#include <sys/stat.h>

#include <libtorrent/session.hpp>
#include <libtorrent/session_status.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/torrent_status.hpp>
#include <libtorrent/error_code.hpp>
#include <libtorrent/download_priority.hpp>
#include <libtorrent/peer_info.hpp>
#include <libtorrent/create_torrent.hpp>
#include <libtorrent/alert.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/write_resume_data.hpp>
#include <libtorrent/read_resume_data.hpp>

namespace lt = libtorrent;

static std::unique_ptr<lt::session> g_session;
static std::vector<lt::torrent_handle> g_handles;
static std::vector<bool> g_pausedHandles;
static std::mutex g_mutex;
static std::string g_savePath;
static bool g_pausedAll = false;

// Connection settings (Feature 4). Defaults match exactly what the app
// already did before this feature existed, so an existing install behaves
// the same until the user changes something in the new Connection screen.
static int g_listenPort = 6881;
static bool g_utpIn = true;
static bool g_utpOut = true;
static bool g_tcpIn = true;
static bool g_tcpOut = true;
static bool g_upnpEnabled = true;
static bool g_natpmpEnabled = true;

static std::string g_upnpState = "Waiting";
static std::string g_upnpMessage = "No UPnP response yet";
static int g_upnpExternalPort = 0;

static std::string g_natpmpState = "Waiting";
static std::string g_natpmpMessage = "No NAT-PMP response yet";
static int g_natpmpExternalPort = 0;

static bool g_dhtEnabled = true;
static bool g_pexEnabled = true;
static bool g_lsdEnabled = true;

// Contact every tracker (every tier) at once instead of only the first
// working tier. libtorrent's own default is off; this app turns it ON by
// default (the user can switch it off in Network Features).
static bool g_announceToAll = true;

// Feature 1: Speed Control (global limits only - no scheduling). Bytes
// per second, matching libtorrent's own convention where 0 means
// unlimited. Defaults to unlimited, matching the app's behavior before
// this feature existed.
static int g_globalUploadLimit = 0;
static int g_globalDownloadLimit = 0;

// Network policy (Wi-Fi only / mobile data only): when the phone is on a
// network the user did not allow, the whole session is paused with
// session::pause(), which stops all transfers without touching any
// torrent's own paused/resumed state. Kept here so it is also applied if
// the session is created after the setting was made.
static bool g_networkPaused = false;

// ---- Execution Log ----
// libtorrent alerts are turned into short lines ("<level>|<text>", level is
// N = normal, I = info, W = warning, C = critical). The app collects them with
// EngineExtras.drainEngineLog() and shows them in its Execution Log screen.
static std::deque<std::string> g_engineLog;
static const size_t kMaxEngineLog = 500;

static void pushEngineLog(char level, const std::string& message) {
    std::string line;
    line += level;
    line += '|';

    // keep every entry on a single line
    for (char ch : message) {
        line += (ch == '\n' || ch == '\r') ? ' ' : ch;
    }

    // do not repeat the exact same line twice in a row
    if (!g_engineLog.empty() && g_engineLog.back() == line) {
        return;
    }

    g_engineLog.push_back(line);

    while (g_engineLog.size() > kMaxEngineLog) {
        g_engineLog.pop_front();
    }
}

// ---- Protocol encryption (like qBittorrent's "Encryption mode") ----
// 0 = allow encryption (default), 1 = require encryption, 2 = disable encryption
static int g_encryptionMode = 0;

static void fillEncryptionSettings(lt::settings_pack& pack, int mode) {
    int policy = static_cast<int>(lt::settings_pack::pe_enabled);
    int level = static_cast<int>(lt::settings_pack::pe_both);

    if (mode == 1) {
        // only encrypted connections, in and out
        policy = static_cast<int>(lt::settings_pack::pe_forced);
        level = static_cast<int>(lt::settings_pack::pe_rc4);
    } else if (mode == 2) {
        // no encryption at all
        policy = static_cast<int>(lt::settings_pack::pe_disabled);
        level = static_cast<int>(lt::settings_pack::pe_both);
    }

    pack.set_int(lt::settings_pack::out_enc_policy, policy);
    pack.set_int(lt::settings_pack::in_enc_policy, policy);
    pack.set_int(lt::settings_pack::allowed_enc_level, level);
}

static std::string toString(JNIEnv* env, jstring value) {
    if (!value) return "";

    const char* raw = env->GetStringUTFChars(value, nullptr);
    std::string result = raw ? raw : "";
    env->ReleaseStringUTFChars(value, raw);

    return result;
}

static std::string hashBytesToHex(
        const char* data,
        int size) {

    std::ostringstream oss;

    for (int i = 0; i < size; ++i) {
        unsigned char c =
                static_cast<unsigned char>(data[i]);

        char hex[3];

        std::snprintf(
                hex,
                sizeof(hex),
                "%02x",
                c
        );

        oss << hex;
    }

    return oss.str();
}

static std::string normalizeHash(std::string hash) {
    std::string key = "btih:";
    size_t pos = hash.find(key);

    if (pos != std::string::npos) {
        hash = hash.substr(pos + key.length());
    }

    size_t end = hash.find("&");

    if (end != std::string::npos) {
        hash = hash.substr(0, end);
    }

    hash.erase(
            std::remove_if(
                    hash.begin(),
                    hash.end(),
                    [](unsigned char c) {
                        return std::isspace(c);
                    }
            ),
            hash.end()
    );

    std::transform(
            hash.begin(),
            hash.end(),
            hash.begin(),
            [](unsigned char c) {
                return std::tolower(c);
            }
    );

    return hash;
}

static std::string getTorrentInfoHashSafe(
        const lt::torrent_info& info) {

    try {
        auto hashes = info.info_hashes();

        if (hashes.has_v1()) {
            auto h = hashes.v1;

            return normalizeHash(
                    hashBytesToHex(
                            h.data(),
                            static_cast<int>(h.size())
                    )
            );
        }

        if (hashes.has_v2()) {
            auto h = hashes.v2;

            return normalizeHash(
                    hashBytesToHex(
                            h.data(),
                            static_cast<int>(h.size())
                    )
            );
        }
    } catch (...) {
        return "";
    }

    return "";
}

static std::string extractHashFromMagnet(const std::string& magnet) {
    std::string key = "btih:";
    size_t pos = magnet.find(key);

    if (pos == std::string::npos) {
        return normalizeHash(magnet);
    }

    pos += key.length();

    size_t end = magnet.find("&", pos);

    std::string hash;

    if (end == std::string::npos) {
        hash = magnet.substr(pos);
    } else {
        hash = magnet.substr(pos, end - pos);
    }

    return normalizeHash(hash);
}

static std::string getHashFromHandle(const lt::torrent_handle& handle) {
    if (!handle.is_valid()) {
        return "";
    }

    try {
        std::string magnet = lt::make_magnet_uri(handle);
        return extractHashFromMagnet(magnet);
    } catch (...) {
        return "";
    }
}

// ------------------------------------------------------------------
// Resume data. libtorrent can describe exactly which pieces of a torrent
// are already on disk ("fast resume"). Without it, every restart makes
// libtorrent re-read and hash all downloaded data ("Checking files").
//
// Files live in g_resumeDir as <info-hash>.fastresume. They are written
// from save_resume_data_alert (see updatePortForwardingAlerts) and read
// back in applyResumeData() just before a torrent is added. Everything
// here is best-effort: any failure just means that torrent is checked the
// normal way, exactly like before this existed.
// ------------------------------------------------------------------
static std::string g_resumeDir;
static int g_resumePending = 0;

static bool isSafeHashName(const std::string& hash) {
    if (hash.empty() || hash.size() > 64) return false;

    for (char c : hash) {
        if (!std::isalnum(static_cast<unsigned char>(c))) return false;
    }

    return true;
}

static std::string resumeFilePath(const std::string& hash) {
    return g_resumeDir + "/" + hash + ".fastresume";
}

// Trackers merged into an existing torrent (re-adding the same torrent
// from another .torrent file / magnet) are kept in <info-hash>.trackers,
// one "tier<TAB>url" per line, because applyResumeData() deliberately
// does not restore trackers from the .fastresume file. They are put back
// on the torrent whenever it is re-added, and deleted with the torrent.
static std::string mergedTrackersPath(const std::string& hash) {
    return g_resumeDir + "/" + hash + ".trackers";
}

static void deleteResumeFile(const std::string& hash) {
    if (g_resumeDir.empty() || !isSafeHashName(hash)) return;
    std::remove(resumeFilePath(hash).c_str());
    std::remove(mergedTrackersPath(hash).c_str());
}

static bool resumeFileExists(const std::string& hash) {
    if (g_resumeDir.empty() || !isSafeHashName(hash)) return false;

    std::ifstream in(resumeFilePath(hash), std::ios::binary);
    return static_cast<bool>(in);
}

static void applyMergedTrackers(lt::add_torrent_params& params, const std::string& hash) {
    try {
        std::ifstream in(mergedTrackersPath(hash));
        if (!in) return;

        std::set<std::string> known;
        for (const auto& u : params.trackers) known.insert(u);

        if (params.ti) {
            for (const auto& t : params.ti->trackers()) known.insert(t.url);
        }

        std::string line;

        while (std::getline(in, line)) {
            size_t tab = line.find('\t');
            if (tab == std::string::npos) continue;

            int tier = std::atoi(line.substr(0, tab).c_str());
            std::string url = line.substr(tab + 1);

            while (!url.empty() && (url.back() == '\r' || url.back() == '\n')) {
                url.pop_back();
            }

            if (url.empty() || known.count(url) > 0) continue;

            // tracker_tiers must line up with trackers before appending.
            while (params.tracker_tiers.size() < params.trackers.size()) {
                params.tracker_tiers.push_back(0);
            }

            params.trackers.push_back(url);
            params.tracker_tiers.push_back(tier);
            known.insert(url);
        }
    } catch (...) {
        // Best effort - the torrent just starts with its own trackers.
    }
}

// Copies only "what we already have" from the saved resume data into the
// params about to be added. Deliberately NOT copied: save path, flags,
// file/piece priorities (the app re-applies its own saved selection), and
// trackers. A resume file for a torrent with no resume file, or one that
// cannot be read, leaves params untouched.
static void applyResumeData(lt::add_torrent_params& params, const std::string& hash) {
    if (g_resumeDir.empty() || !isSafeHashName(hash)) return;

    applyMergedTrackers(params, hash);

    try {
        std::ifstream in(resumeFilePath(hash), std::ios::binary);
        if (!in) return;

        std::vector<char> buf(
                (std::istreambuf_iterator<char>(in)),
                std::istreambuf_iterator<char>()
        );

        if (buf.empty() || buf.size() > 64u * 1024u * 1024u) return;

        lt::error_code rec;
        lt::add_torrent_params rp = lt::read_resume_data(
                lt::span<char const>(buf.data(), buf.size()), rec);

        if (rec) return;

        params.have_pieces = rp.have_pieces;
        params.verified_pieces = rp.verified_pieces;
        params.unfinished_pieces = rp.unfinished_pieces;
        params.renamed_files = rp.renamed_files;
        params.total_uploaded = rp.total_uploaded;
        params.total_downloaded = rp.total_downloaded;
        params.active_time = rp.active_time;
        params.finished_time = rp.finished_time;
        params.seeding_time = rp.seeding_time;
        params.added_time = rp.added_time;
        params.completed_time = rp.completed_time;

        // A magnet link has no metadata yet; the saved info dictionary
        // gives it immediately, with nothing to fetch from peers.
        if (!params.ti && rp.ti) {
            params.ti = rp.ti;
        }
    } catch (...) {
        // Unreadable or incompatible: ignore, the torrent is just checked.
    }
}

static void writeResumeFile(const lt::save_resume_data_alert* alert) {
    if (!alert || g_resumeDir.empty()) return;

    try {
        std::string hash = getHashFromHandle(alert->handle);
        if (!isSafeHashName(hash)) return;

        std::vector<char> buf = lt::write_resume_data_buf(alert->params);
        if (buf.empty()) return;

        std::string finalPath = resumeFilePath(hash);
        std::string tmpPath = finalPath + ".tmp";

        {
            std::ofstream out(tmpPath, std::ios::binary | std::ios::trunc);
            if (!out) return;

            out.write(buf.data(), static_cast<std::streamsize>(buf.size()));
            out.flush();

            if (!out) {
                out.close();
                std::remove(tmpPath.c_str());
                return;
            }
        }

        std::rename(tmpPath.c_str(), finalPath.c_str());
    } catch (...) {
    }
}

static bool hasHashAlready(const std::string& rawHash) {
    std::string hash = normalizeHash(rawHash);

    if (hash.empty()) {
        return false;
    }

    for (auto& handle : g_handles) {
        if (!handle.is_valid()) {
            continue;
        }

        std::string existingHash = getHashFromHandle(handle);

        if (!existingHash.empty() && existingHash == hash) {
            return true;
        }
    }

    return false;
}

// Feature 3 (Sequential Download / First-Last Piece Priority): every new
// native function for that feature identifies its torrent by info-hash,
// never by list index, per the project's standing rule - indexes shift as
// torrents are added/removed/sorted, hashes don't. This is the shared
// lookup both of those functions use.
static lt::torrent_handle findHandleByHash(const std::string& rawHash) {
    std::string hash = normalizeHash(rawHash);

    if (hash.empty()) {
        return lt::torrent_handle();
    }

    for (auto& handle : g_handles) {
        if (!handle.is_valid()) {
            continue;
        }

        std::string existingHash = getHashFromHandle(handle);

        if (!existingHash.empty() && existingHash == hash) {
            return handle;
        }
    }

    return lt::torrent_handle();
}

// Feature: lets a torrent added by magnet link or bare info-hash be saved
// out as a real .torrent file, once metadata has actually arrived -
// handle.torrent_file() is null until then, which is the normal state for
// a magnet still resolving, not an error. create_torrent(torrent_info
// const&) rebuilds a torrent_info the app already has into a fresh
// create_torrent, and generate_buf() bencodes it straight to bytes (no
// separate bencode()/entry step needed). Identified by info-hash, never
// list index, per the project's standing rule. Returns "" (never
// throws/crashes) on any failure - hash not found, no metadata yet, or the
// file couldn't be written.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_saveTorrentFile(
        JNIEnv* env,
        jobject,
        jstring hashValue,
        jstring destFolder) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return env->NewStringUTF("");
    }

    std::shared_ptr<const lt::torrent_info> info;

    try {
        info = handle.torrent_file();
    } catch (...) {
        return env->NewStringUTF("");
    }

    if (!info) {
        // No metadata yet - a magnet link that hasn't finished resolving.
        return env->NewStringUTF("");
    }

    try {
        lt::create_torrent ct(*info);
        std::vector<char> buffer = ct.generate_buf();

        std::string folder = toString(env, destFolder);
        std::string name = info->name();

        if (name.empty()) {
            name = "torrent";
        }

        std::string safeName;
        for (char ch : name) {
            if (ch == '/' || ch == '\\' || ch == ':' || ch == '*' ||
                ch == '?' || ch == '"' || ch == '<' || ch == '>' || ch == '|') {
                safeName += '_';
            } else {
                safeName += ch;
            }
        }

        std::string path = folder + "/" + safeName + ".torrent";

        FILE* f = fopen(path.c_str(), "wb");

        if (!f) {
            return env->NewStringUTF("");
        }

        size_t written = fwrite(buffer.data(), 1, buffer.size(), f);
        fclose(f);

        if (written != buffer.size()) {
            return env->NewStringUTF("");
        }

        return env->NewStringUTF(path.c_str());
    } catch (...) {
        return env->NewStringUTF("");
    }
}

static std::string buildListenInterfaces(int port) {
    std::string p = std::to_string(port);
    return "0.0.0.0:" + p + ",[::]:" + p;
}

// Feature 5: Custom Save Folder. g_savePath is only the SESSION DEFAULT -
// it is seeded once, the first time the session is created (normally from
// TorrentService's startSession() call). After that, passing a different
// savePath here does NOT change g_savePath; each add* function below uses
// its own per-call savePath argument as that one torrent's save_path
// (falling back to g_savePath only when the caller passed an empty
// string), so a global default change never silently redirects a torrent
// the user placed in a custom per-torrent folder, and vice versa.
static void ensureSession(const std::string& savePath) {
    if (!g_session) {
        if (!savePath.empty()) {
            g_savePath = savePath;
        }

        lt::settings_pack pack;

        pack.set_str(
                lt::settings_pack::user_agent,
                "TorrentOr/1.0"
        );

        // TorrentOr lets the user decide what runs and what is paused, so turn
        // libtorrent's own queue limits off. With the defaults (3 active
        // downloads) libtorrent silently pauses the other torrents and can
        // pause one again shortly after the user resumed it.
        pack.set_int(lt::settings_pack::active_downloads, -1);
        pack.set_int(lt::settings_pack::active_seeds, -1);
        pack.set_int(lt::settings_pack::active_limit, -1);

        // Which libtorrent alerts we receive for the Execution Log:
        // error (1) + port mapping (4) + storage (8) + tracker (16) + status (64).
        pack.set_int(lt::settings_pack::alert_mask, 1 + 4 + 8 + 16 + 64);

        // Protocol encryption mode chosen in the app.
        fillEncryptionSettings(pack, g_encryptionMode);

        // Connection settings (Feature 4) - listen port, uTP/TCP and
        // automatic port forwarding. These come from g_listenPort /
        // g_utpIn / g_utpOut / g_tcpIn / g_tcpOut / g_upnpEnabled /
        // g_natpmpEnabled, which already hold either the built-in
        // defaults (first run) or whatever TorrentService restored from
        // SharedPreferences before calling startSession() - see
        // Java_..._applyConnectionSettings below for the live-apply path
        // used after the session already exists.
        //
        // Automatic port forwarding asks supported routers to open the
        // listening port using UPnP / NAT-PMP. It will not work on
        // networks that block port forwarding, CGNAT, mobile data, or
        // routers where UPnP / NAT-PMP is disabled - the Connection
        // screen shows the real alert-derived reason in that case
        // instead of claiming success.
        pack.set_str(
                lt::settings_pack::listen_interfaces,
                buildListenInterfaces(g_listenPort)
        );

        pack.set_bool(
                lt::settings_pack::enable_upnp,
                g_upnpEnabled
        );

        pack.set_bool(
                lt::settings_pack::enable_natpmp,
                g_natpmpEnabled
        );

        // DHT: finds peers without trackers, important for magnet links.
        pack.set_bool(
                lt::settings_pack::enable_dht,
                g_dhtEnabled
        );

        // LSD: finds peers on the same local Wi-Fi / LAN.
        pack.set_bool(
                lt::settings_pack::enable_lsd,
                g_lsdEnabled
        );

        // Announce to every tracker and every tier at once (on by default).
        pack.set_bool(lt::settings_pack::announce_to_all_trackers, g_announceToAll);
        pack.set_bool(lt::settings_pack::announce_to_all_tiers, g_announceToAll);

        // uTP: enables µTP peer connections, commonly used by modern clients.
        pack.set_bool(
                lt::settings_pack::enable_incoming_utp,
                g_utpIn
        );

        pack.set_bool(
                lt::settings_pack::enable_outgoing_utp,
                g_utpOut
        );

        // TCP: libtorrent defaults these to true, so this keeps the exact
        // prior behavior unless the user turns one off in the Connection
        // screen.
        pack.set_bool(
                lt::settings_pack::enable_incoming_tcp,
                g_tcpIn
        );

        pack.set_bool(
                lt::settings_pack::enable_outgoing_tcp,
                g_tcpOut
        );

        // Feature 1: Speed Control - global upload/download limits in
        // bytes/second, from g_globalUploadLimit / g_globalDownloadLimit
        // (same first-run-default-or-restored-from-prefs pattern as the
        // Connection settings above). 0 means unlimited.
        pack.set_int(
                lt::settings_pack::upload_rate_limit,
                g_globalUploadLimit
        );

        pack.set_int(
                lt::settings_pack::download_rate_limit,
                g_globalDownloadLimit
        );

        // Bug fix: libtorrent's default is to IGNORE the speed limits for
        // any peer on the local network (same Wi-Fi/LAN, found for example
        // through Local Service Discovery, which this app has on). So a
        // limit set to 50 KB/s held for internet peers but jumped to
        // 2 MB/s whenever a peer on the local network was connected. With
        // this off the limits apply to every peer. It changes nothing when
        // no limit is set (0 = unlimited), so unlimited speeds are
        // unaffected.
        pack.set_bool(
                lt::settings_pack::ignore_limits_on_local_network,
                false
        );

        if (!g_upnpEnabled) {
            g_upnpState = "Disabled";
            g_upnpMessage = "UPnP is turned off";
        }

        if (!g_natpmpEnabled) {
            g_natpmpState = "Disabled";
            g_natpmpMessage = "NAT-PMP is turned off";
        }

        // HTTPS trackers: OpenSSL has no certificate store on Android by
        // default, so every https:// tracker failed certificate checking
        // ("unspecified system error"). Point OpenSSL at Android's own
        // trusted-certificate folders (a colon-separated list; folders that
        // do not exist on a given Android version are simply skipped).
        setenv("SSL_CERT_DIR",
               "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts",
               1);

        // HTTPS tracker certificates are verified (libtorrent default). The
        // trusted-certificate bundle is prepared by CaBundle.kt before this.
        pack.set_bool(lt::settings_pack::validate_https_trackers, true);

        g_session = std::make_unique<lt::session>(pack);

        if (g_networkPaused) {
            try {
                g_session->pause();
            } catch (...) {
            }
        }

        // PEX note:
        // libtorrent normally enables peer exchange through its standard torrent extensions
        // when the library is built with extensions enabled. There is no safe universal
        // settings_pack key named enable_pex across all libtorrent builds, so we keep PEX
        // supported by the normal libtorrent extension system without adding a build-breaking key.
    }
}


static std::string extractTrackerHostFromUrl(const std::string& url) {
    if (url.empty()) {
        return "Unknown tracker";
    }

    std::string value = url;

    size_t scheme = value.find("://");
    if (scheme != std::string::npos) {
        value = value.substr(scheme + 3);
    }

    size_t at = value.find('@');
    if (at != std::string::npos) {
        value = value.substr(at + 1);
    }

    if (!value.empty() && value[0] == '[') {
        size_t endBracket = value.find(']');
        if (endBracket != std::string::npos) {
            return value.substr(1, endBracket - 1);
        }
    }

    size_t end = value.find_first_of(":/?#");
    if (end != std::string::npos) {
        value = value.substr(0, end);
    }

    if (value.empty()) {
        return "Unknown tracker";
    }

    return value;
}

static std::string formatBytes(long long bytes) {
    if (bytes <= 0) {
        return "0 B";
    }

    double value = static_cast<double>(bytes);
    const char* units[] = {"B", "KB", "MB", "GB", "TB"};
    int unitIndex = 0;

    while (value >= 1024.0 && unitIndex < 4) {
        value /= 1024.0;
        unitIndex++;
    }

    char buffer[64];

    if (unitIndex == 0) {
        std::snprintf(
                buffer,
                sizeof(buffer),
                "%.0f %s",
                value,
                units[unitIndex]
        );
    } else {
        std::snprintf(
                buffer,
                sizeof(buffer),
                "%.2f %s",
                value,
                units[unitIndex]
        );
    }

    return std::string(buffer);
}

static std::string formatEta(long seconds) {
    if (seconds <= 0) return "ETA: --";

    long mins = seconds / 60;
    long hrs = mins / 60;
    long days = hrs / 24;

    // Feature: once the ETA passes 24 hours, show it in days (+ remaining
    // hours) instead of letting the hour count run up into the hundreds.
    if (days > 0) {
        return "ETA: " +
               std::to_string(days) +
               "d " +
               std::to_string(hrs % 24) +
               "h";
    }

    if (hrs > 0) {
        return "ETA: " +
               std::to_string(hrs) +
               "h " +
               std::to_string(mins % 60) +
               "m";
    }

    return "ETA: " + std::to_string(mins) + "m";
}

static std::string pluralUnit(long value, const char* singular, const char* plural) {
    return std::to_string(value) + " " + (value == 1 ? singular : plural);
}

// Feature: "Time active" on the Statistics tab - same day/hour rollover
// idea as formatEta, but spelled out ("12 days 7 hours") since this is a
// standalone label rather than a short, repeated inline value.
static std::string formatDuration(long seconds) {
    if (seconds <= 0) return "0 seconds";

    long days = seconds / 86400;
    long hrs = (seconds % 86400) / 3600;
    long mins = (seconds % 3600) / 60;
    long secs = seconds % 60;

    // Always days, hours, minutes and seconds: it starts at the largest
    // unit that is not zero and then shows every smaller unit, e.g.
    // "1 day 16 hours 1 minute 5 seconds" or "3 minutes 0 seconds".
    std::string out;

    if (days > 0) {
        out = pluralUnit(days, "day", "days") + " " +
              pluralUnit(hrs, "hour", "hours") + " " +
              pluralUnit(mins, "minute", "minutes") + " " +
              pluralUnit(secs, "second", "seconds");
    } else if (hrs > 0) {
        out = pluralUnit(hrs, "hour", "hours") + " " +
              pluralUnit(mins, "minute", "minutes") + " " +
              pluralUnit(secs, "second", "seconds");
    } else if (mins > 0) {
        out = pluralUnit(mins, "minute", "minutes") + " " +
              pluralUnit(secs, "second", "seconds");
    } else {
        out = pluralUnit(secs, "second", "seconds");
    }

    return out;
}

// "Time active: ..." (and "Seeding time: ..." once it has been seeding) -
// shared by the Statistics tab and the General tab so both always agree.
// Both values come from libtorrent and are restored from resume data, so
// they carry over app restarts.
static std::string buildTimeLines(const lt::torrent_status& st) {
    std::string out;

    long long added = static_cast<long long>(st.added_time);
    long long completed = static_cast<long long>(st.completed_time);
    long long now = static_cast<long long>(std::time(nullptr));

    // Elapsed = real (wall-clock) time since the torrent was added,
    // including time it was paused or the app was closed. It stops
    // counting at the moment the download completed, so it then equals
    // "Time to complete". "Time active" below only counts time it was
    // actually running.
    if (added > 0) {
        long long end = (completed > added) ? completed : now;

        if (end > added) {
            out += "Elapsed: ";
            out += formatDuration(static_cast<long>(end - added));
            out += "\n";
        }
    }

    out += "Time active: ";
    out += formatDuration(static_cast<long>(st.active_duration.count()));

    long seeding = static_cast<long>(st.seeding_duration.count());

    if (seeding > 0) {
        out += "\nSeeding time: ";
        out += formatDuration(seeding);
    }

    // How long it took from being added to finishing the download.
    if (added > 0 && completed > added) {
        out += "\nTime to complete: ";
        out += formatDuration(static_cast<long>(completed - added));
    }

    return out;
}


static std::string lowerCopy(std::string value) {
    std::transform(
            value.begin(),
            value.end(),
            value.begin(),
            [](unsigned char c) {
                return std::tolower(c);
            }
    );

    return value;
}

static std::string detectPortForwardingMethod(const std::string& message) {
    std::string lower = lowerCopy(message);

    if (lower.find("nat-pmp") != std::string::npos ||
        lower.find("natpmp") != std::string::npos ||
        lower.find("pmp") != std::string::npos) {
        return "NAT-PMP";
    }

    if (lower.find("upnp") != std::string::npos ||
        lower.find("upnp") != std::string::npos) {
        return "UPnP";
    }

    return "Unknown";
}

// Turns libtorrent alerts into Execution Log lines (shown in the app) and also
// writes the same text to logcat (tag TorrentOrLT).
static void logTorrentAlert(lt::alert* alert) {
    if (!alert) {
        return;
    }

    char level = 0;

    if (lt::alert_cast<lt::torrent_error_alert>(alert) ||
        lt::alert_cast<lt::file_error_alert>(alert) ||
        lt::alert_cast<lt::listen_failed_alert>(alert) ||
        lt::alert_cast<lt::metadata_failed_alert>(alert) ||
        lt::alert_cast<lt::torrent_delete_failed_alert>(alert) ||
        lt::alert_cast<lt::storage_moved_failed_alert>(alert)) {
        level = 'C';
    } else if (lt::alert_cast<lt::tracker_error_alert>(alert) ||
               lt::alert_cast<lt::tracker_warning_alert>(alert) ||
               lt::alert_cast<lt::scrape_failed_alert>(alert) ||
               lt::alert_cast<lt::portmap_error_alert>(alert)) {
        level = 'W';
    } else if (auto* added = lt::alert_cast<lt::add_torrent_alert>(alert)) {
        level = added->error ? 'C' : 'I';
    } else if (lt::alert_cast<lt::torrent_finished_alert>(alert) ||
               lt::alert_cast<lt::torrent_paused_alert>(alert) ||
               lt::alert_cast<lt::torrent_resumed_alert>(alert) ||
               lt::alert_cast<lt::torrent_removed_alert>(alert) ||
               lt::alert_cast<lt::state_changed_alert>(alert) ||
               lt::alert_cast<lt::metadata_received_alert>(alert) ||
               lt::alert_cast<lt::listen_succeeded_alert>(alert) ||
               lt::alert_cast<lt::storage_moved_alert>(alert) ||
               lt::alert_cast<lt::portmap_alert>(alert)) {
        level = 'I';
    }

    if (level == 0) {
        return;
    }

    std::string message = alert->message();

    __android_log_print(
            ANDROID_LOG_INFO,
            "TorrentOrLT",
            "%s",
            message.c_str()
    );

    // Diagnostic: the plain message hides the numeric error code and its
    // category, which is what tells a TLS/certificate failure apart from a
    // network failure for HTTPS trackers.
    if (auto* te = lt::alert_cast<lt::tracker_error_alert>(alert)) {
        __android_log_print(
                ANDROID_LOG_INFO,
                "TorrentOrLT",
                "TRACKER-ERR url=%s code=%d category=%s msg=%s reason=%s",
                te->tracker_url(),
                te->error.value(),
                te->error.category().name(),
                te->error.message().c_str(),
                te->failure_reason()
        );
    }

    pushEngineLog(level, message);
}

static void updatePortForwardingAlerts() {
    if (!g_session) {
        return;
    }

    std::vector<lt::alert*> alerts;

    try {
        g_session->pop_alerts(&alerts);
    } catch (...) {
        return;
    }

    for (lt::alert* alert : alerts) {
        if (!alert) {
            continue;
        }

        logTorrentAlert(alert);

        // Resume data requested by saveResumeData(): write it to disk.
        if (auto* rdAlert = lt::alert_cast<lt::save_resume_data_alert>(alert)) {
            if (g_resumePending > 0) g_resumePending--;
            writeResumeFile(rdAlert);
            continue;
        }

        if (lt::alert_cast<lt::save_resume_data_failed_alert>(alert)) {
            // Includes "nothing changed since last save" - not an error.
            if (g_resumePending > 0) g_resumePending--;
            continue;
        }

        // Feature: magnet file selection (app + Web UI). addMagnetPaused
        // deliberately keeps auto_managed on so libtorrent will run the
        // torrent long enough to fetch its metadata - but that same
        // auto_managed flag means libtorrent can also start downloading
        // real file data the instant metadata arrives, before the user
        // has even seen the file list, let alone pressed Download. The
        // moment metadata actually arrives, re-pause (for real this
        // time - no auto_managed) any torrent still waiting on that
        // confirmation (g_pausedHandles[i] true); resumeTorrent(), called
        // from the Download button's confirm step, is what starts it
        // for real.
        if (auto* metaAlert = lt::alert_cast<lt::metadata_received_alert>(alert)) {
            lt::torrent_handle metaHandle = metaAlert->handle;

            for (int i = 0; i < static_cast<int>(g_handles.size()); ++i) {
                if (g_handles[i] == metaHandle) {
                    if (i < static_cast<int>(g_pausedHandles.size()) && g_pausedHandles[i] &&
                        metaHandle.is_valid()) {
                        metaHandle.unset_flags(lt::torrent_flags::auto_managed);
                        metaHandle.pause();
                    }
                    break;
                }
            }

            continue;
        }

        if (auto* success = lt::alert_cast<lt::portmap_alert>(alert)) {
            std::string message = success->message();
            std::string method = detectPortForwardingMethod(message);

            if (method == "UPnP") {
                g_upnpState = "Success";
                g_upnpExternalPort = success->external_port;
                g_upnpMessage = message;
            } else if (method == "NAT-PMP") {
                g_natpmpState = "Success";
                g_natpmpExternalPort = success->external_port;
                g_natpmpMessage = message;
            } else {
                g_upnpState = "Success";
                g_natpmpState = "Success";
                g_upnpExternalPort = success->external_port;
                g_natpmpExternalPort = success->external_port;
                g_upnpMessage = message;
                g_natpmpMessage = message;
            }

            continue;
        }

        if (auto* error = lt::alert_cast<lt::portmap_error_alert>(alert)) {
            std::string message = error->message();
            std::string method = detectPortForwardingMethod(message);

            if (method == "UPnP") {
                if (g_upnpState != "Success") {
                    g_upnpState = "Failed";
                    g_upnpMessage = message;
                }
            } else if (method == "NAT-PMP") {
                if (g_natpmpState != "Success") {
                    g_natpmpState = "Failed";
                    g_natpmpMessage = message;
                }
            } else {
                if (g_upnpState != "Success") {
                    g_upnpState = "Failed";
                    g_upnpMessage = message;
                }

                if (g_natpmpState != "Success") {
                    g_natpmpState = "Failed";
                    g_natpmpMessage = message;
                }
            }
        }
    }
}


static void applyNetworkFeatureSettings() {
    if (!g_session) {
        return;
    }

    try {
        lt::settings_pack pack;

        pack.set_bool(
                lt::settings_pack::enable_dht,
                g_dhtEnabled
        );

        pack.set_bool(
                lt::settings_pack::enable_lsd,
                g_lsdEnabled
        );

        pack.set_bool(lt::settings_pack::announce_to_all_trackers, g_announceToAll);
        pack.set_bool(lt::settings_pack::announce_to_all_tiers, g_announceToAll);

        g_session->apply_settings(pack);
    } catch (...) {
        // Prevent crash if settings cannot be applied at runtime.
    }
}

// Feature 4: pushes the current uTP/TCP/UPnP/NAT-PMP/listen-port globals
// into the running session with apply_settings() - no restart, and
// (unlike adding/removing a torrent) this never touches g_handles, so it
// cannot pause, resume or re-add anything, nor touch the paused-hash or
// file-selection prefs - those live entirely on the Kotlin side and this
// function never goes near them.
//
// If the session does not exist yet, this is a no-op: ensureSession()
// above already reads the same globals when it builds the very first
// settings_pack, so a call made before startSession() still takes effect
// once the session actually starts.
static void applyConnectionSettings() {
    if (!g_session) {
        return;
    }

    try {
        lt::settings_pack pack;

        pack.set_bool(lt::settings_pack::enable_incoming_utp, g_utpIn);
        pack.set_bool(lt::settings_pack::enable_outgoing_utp, g_utpOut);
        pack.set_bool(lt::settings_pack::enable_incoming_tcp, g_tcpIn);
        pack.set_bool(lt::settings_pack::enable_outgoing_tcp, g_tcpOut);
        pack.set_bool(lt::settings_pack::enable_upnp, g_upnpEnabled);
        pack.set_bool(lt::settings_pack::enable_natpmp, g_natpmpEnabled);

        pack.set_str(
                lt::settings_pack::listen_interfaces,
                buildListenInterfaces(g_listenPort)
        );

        g_session->apply_settings(pack);
    } catch (...) {
        // Prevent crash if settings cannot be applied at runtime.
    }
}

static std::string enabledText(bool enabled) {
    return enabled ? "Enabled" : "Disabled";
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_startSession(
        JNIEnv* env,
        jobject,
        jstring savePath) {

    std::lock_guard<std::mutex> lock(g_mutex);
    ensureSession(toString(env, savePath));
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_addMagnet(
        JNIEnv* env,
        jobject,
        jstring magnet,
        jstring savePath) {

    std::lock_guard<std::mutex> lock(g_mutex);
    std::string torrentSavePath = toString(env, savePath);
    ensureSession(torrentSavePath);

    std::string magnetText = toString(env, magnet);
    std::string hash = extractHashFromMagnet(magnetText);

    if (hasHashAlready(hash)) {
        return;
    }

    lt::error_code ec;
    lt::add_torrent_params params =
            lt::parse_magnet_uri(magnetText, ec);

    if (ec) return;

    params.save_path = torrentSavePath.empty() ? g_savePath : torrentSavePath;

    applyResumeData(params, hash);

    auto handle = g_session->add_torrent(params, ec);

    // Bug fix: a newly added torrent used to inherit g_pausedAll, which
    // stays true indefinitely after a single "Pause All" tap (it's only
    // cleared by Resume All / removeAllTorrents). That made every torrent
    // added after any past Pause All start paused with no visible reason.
    // Pause All only pauses the torrents that existed at that moment; new
    // torrents always start running.
    if (!ec && handle.is_valid()) {
        g_handles.push_back(handle);
        g_pausedHandles.push_back(false);
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_torrentor_TorrentNative_addMagnetPaused(
        JNIEnv* env,
        jobject,
        jstring magnet,
        jstring savePath) {

    std::lock_guard<std::mutex> lock(g_mutex);
    std::string torrentSavePath = toString(env, savePath);
    ensureSession(torrentSavePath);

    std::string magnetText = toString(env, magnet);
    std::string hash = extractHashFromMagnet(magnetText);

    for (int i = 0; i < static_cast<int>(g_handles.size()); ++i) {
        if (!g_handles[i].is_valid()) continue;

        std::string existingHash =
                getHashFromHandle(g_handles[i]);

        if (!existingHash.empty() && existingHash == hash) {
            return i + 1;
        }
    }

    lt::error_code ec;
    lt::add_torrent_params params =
            lt::parse_magnet_uri(magnetText, ec);

    if (ec) return -1;

    params.save_path = torrentSavePath.empty() ? g_savePath : torrentSavePath;

    // Keep auto_managed here on purpose. A magnet has to be running to fetch
    // its metadata, and libtorrent's queue starts it for that. If auto_managed
    // is cleared the torrent stays paused forever and the file list never
    // appears. Torrents the user pauses are handled by pauseTorrent(), which
    // clears auto_managed, and the service pauses restored ones the same way.
    params.flags |= lt::torrent_flags::paused;

    applyResumeData(params, hash);

    auto handle = g_session->add_torrent(params, ec);

    if (ec || !handle.is_valid()) {
        return -1;
    }

    handle.pause();

    g_handles.push_back(handle);
    g_pausedHandles.push_back(true);

    return static_cast<jint>(g_handles.size());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_addTorrentFile(
        JNIEnv* env,
        jobject,
        jstring path,
        jstring savePath) {

    std::lock_guard<std::mutex> lock(g_mutex);
    std::string torrentSavePath = toString(env, savePath);
    ensureSession(torrentSavePath);

    std::string torrentPath = toString(env, path);

    lt::error_code ec;
    auto info =
            std::make_shared<lt::torrent_info>(
                    torrentPath,
                    ec
            );

    if (ec) return;

    std::string hash = getTorrentInfoHashSafe(*info);

    if (hasHashAlready(hash)) {
        return;
    }

    lt::add_torrent_params params;
    params.ti = info;
    params.save_path = torrentSavePath.empty() ? g_savePath : torrentSavePath;

    applyResumeData(params, hash);

    auto handle = g_session->add_torrent(params, ec);

    // Bug fix: see addMagnet above - new torrents must not inherit the
    // sticky g_pausedAll flag.
    if (!ec && handle.is_valid()) {
        g_handles.push_back(handle);
        g_pausedHandles.push_back(false);
    }
}



extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_addTorrentFilePaused(
        JNIEnv* env,
        jobject,
        jstring path,
        jstring savePath) {

    std::lock_guard<std::mutex> lock(g_mutex);
    std::string torrentSavePath = toString(env, savePath);
    ensureSession(torrentSavePath);

    std::string torrentPath = toString(env, path);

    lt::error_code ec;
    auto info =
            std::make_shared<lt::torrent_info>(
                    torrentPath,
                    ec
            );

    if (ec) return;

    std::string hash = getTorrentInfoHashSafe(*info);

    if (hasHashAlready(hash)) {
        return;
    }

    lt::add_torrent_params params;
    params.ti = info;
    params.save_path = torrentSavePath.empty() ? g_savePath : torrentSavePath;
    params.flags |= lt::torrent_flags::paused;
    params.flags &= ~lt::torrent_flags::auto_managed;

    applyResumeData(params, hash);

    auto handle = g_session->add_torrent(params, ec);

    if (!ec && handle.is_valid()) {
        handle.unset_flags(lt::torrent_flags::auto_managed);
        handle.pause();

        g_handles.push_back(handle);
        g_pausedHandles.push_back(true);
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentFiles(
        JNIEnv* env,
        jobject,
        jstring path) {

    std::string torrentPath = toString(env, path);

    lt::error_code ec;
    lt::torrent_info info(torrentPath, ec);

    if (ec) {
        std::string err = "ERROR: " + ec.message();
        return env->NewStringUTF(err.c_str());
    }

    std::string output;
    auto const& files = info.files();

    for (int i = 0; i < files.num_files(); ++i) {
        output += std::to_string(i);
        output += "|";
        output += files.file_path(i);
        output += "|";
        output += std::to_string(files.file_size(i));
        output += "\n";
    }

    if (output.empty()) {
        return env->NewStringUTF("ERROR: no files");
    }

    return env->NewStringUTF(output.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_addTorrentFileSelected(
        JNIEnv* env,
        jobject,
        jstring path,
        jstring savePath,
        jstring selectedIndexes) {

    std::lock_guard<std::mutex> lock(g_mutex);
    std::string torrentSavePath = toString(env, savePath);
    ensureSession(torrentSavePath);

    std::string torrentPath = toString(env, path);
    std::string selected = toString(env, selectedIndexes);

    lt::error_code ec;
    auto info =
            std::make_shared<lt::torrent_info>(
                    torrentPath,
                    ec
            );

    if (ec) return;

    std::string hash = getTorrentInfoHashSafe(*info);

    if (hasHashAlready(hash)) {
        return;
    }

    lt::add_torrent_params params;
    params.ti = info;
    params.save_path = torrentSavePath.empty() ? g_savePath : torrentSavePath;

    int fileCount = info->files().num_files();

    std::vector<lt::download_priority_t> priorities(
            fileCount,
            lt::dont_download
    );

    std::stringstream ss(selected);
    std::string token;

    while (std::getline(ss, token, ',')) {
        if (token.empty()) continue;

        try {
            int fileIndex = std::stoi(token);

            if (fileIndex >= 0 && fileIndex < fileCount) {
                priorities[fileIndex] = lt::default_priority;
            }
        } catch (...) {
            // Ignore bad index
        }
    }

    params.file_priorities = priorities;

    applyResumeData(params, hash);

    auto handle = g_session->add_torrent(params, ec);

    // Bug fix: see addMagnet above - new torrents must not inherit the
    // sticky g_pausedAll flag.
    if (!ec && handle.is_valid()) {
        g_handles.push_back(handle);
        g_pausedHandles.push_back(false);
    }
}



extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_addTorrentFileSelectedPaused(
        JNIEnv* env,
        jobject,
        jstring path,
        jstring savePath,
        jstring selectedIndexes) {

    std::lock_guard<std::mutex> lock(g_mutex);
    std::string torrentSavePath = toString(env, savePath);
    ensureSession(torrentSavePath);

    std::string torrentPath = toString(env, path);
    std::string selected = toString(env, selectedIndexes);

    lt::error_code ec;
    auto info =
            std::make_shared<lt::torrent_info>(
                    torrentPath,
                    ec
            );

    if (ec) return;

    std::string hash = getTorrentInfoHashSafe(*info);

    if (hasHashAlready(hash)) {
        return;
    }

    lt::add_torrent_params params;
    params.ti = info;
    params.save_path = torrentSavePath.empty() ? g_savePath : torrentSavePath;
    params.flags |= lt::torrent_flags::paused;
    params.flags &= ~lt::torrent_flags::auto_managed;

    int fileCount = info->files().num_files();

    std::vector<lt::download_priority_t> priorities(
            fileCount,
            lt::dont_download
    );

    std::stringstream ss(selected);
    std::string token;

    while (std::getline(ss, token, ',')) {
        if (token.empty()) continue;

        try {
            int fileIndex = std::stoi(token);

            if (fileIndex >= 0 && fileIndex < fileCount) {
                priorities[fileIndex] = lt::default_priority;
            }
        } catch (...) {
            // Ignore bad index
        }
    }

    params.file_priorities = priorities;

    applyResumeData(params, hash);

    auto handle = g_session->add_torrent(params, ec);

    if (!ec && handle.is_valid()) {
        handle.unset_flags(lt::torrent_flags::auto_managed);
        handle.pause();

        g_handles.push_back(handle);
        g_pausedHandles.push_back(true);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_setTorrentFilePriorities(
        JNIEnv* env,
        jobject,
        jint index,
        jstring selectedIndexes) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return;
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return;
    }

    auto info = handle.torrent_file();

    if (!info) {
        return;
    }

    std::string selected = toString(env, selectedIndexes);

    int fileCount = info->files().num_files();

    std::vector<lt::download_priority_t> priorities(
            fileCount,
            lt::dont_download
    );

    std::stringstream ss(selected);
    std::string token;

    while (std::getline(ss, token, ',')) {
        if (token.empty()) continue;

        try {
            int fileIndex = std::stoi(token);

            if (fileIndex >= 0 && fileIndex < fileCount) {
                priorities[fileIndex] = lt::default_priority;
            }
        } catch (...) {
            // Ignore bad index
        }
    }

    try {
        handle.prioritize_files(priorities);
    } catch (...) {
        // Prevent crash
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getDetailedStatus(
        JNIEnv* env,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    updatePortForwardingAlerts();

    if (g_handles.empty()) {
        return env->NewStringUTF("No torrents");
    }

    std::string output;
    int index = 1;

    for (auto& handle : g_handles) {
        if (!handle.is_valid()) {
            index++;
            continue;
        }

        lt::torrent_status st = handle.status();

        int pct =
                static_cast<int>(
                        st.progress * 100.0f
                );

        int downKb = st.download_payload_rate / 1024;
        int upKb = st.upload_payload_rate / 1024;

        int connectedSeeds = st.num_seeds;
        int totalSeeds = st.list_seeds;

        int connectedPeers = st.num_peers;
        int totalPeers = st.list_peers;

        long long downloadedBytes =
                static_cast<long long>(st.total_wanted_done);

        long long totalBytes =
                static_cast<long long>(st.total_wanted);

        long remaining =
                (st.total_wanted > st.total_wanted_done)
                ? static_cast<long>(
                        st.total_wanted -
                        st.total_wanted_done
                )
                : 0L;

        std::string downloadedText =
                formatBytes(downloadedBytes) +
                " / " +
                formatBytes(totalBytes);

        long eta =
                (downKb > 0)
                ? remaining / (downKb * 1024)
                : 0L;

        std::string state;

        if (st.errc) {
            // libtorrent pauses a torrent that hit an error (for example a
            // file it may not write). Show why instead of a bare "Paused".
            std::string errText = st.errc.message();

            for (char& ch : errText) {
                if (ch == '\n' || ch == '\r') ch = ' ';
            }

            state = "Paused (error: " + errText + ")";
        } else if (st.flags & lt::torrent_flags::paused) {
            if (st.flags & lt::torrent_flags::auto_managed) {
                state = "Paused (queued)";
            } else {
                state = "Paused";
            }
        } else if (
                st.state == lt::torrent_status::checking_files ||
                st.state == lt::torrent_status::checking_resume_data
        ) {
            // Bug fix: a force recheck on a torrent that was downloading
            // (not paused, not yet seeding) used to fall straight into the
            // "else" branch below and keep showing a frozen "Downloading"
            // with its last-known percent, since libtorrent doesn't update
            // progress while it's re-checking pieces on disk - it looked
            // like the recheck did nothing. Reporting the real state here
            // makes it visible again, same as before.
            state = "Checking files";
        } else if (st.is_seeding || st.state == lt::torrent_status::finished) {
            // A partial download (only some files selected) never reaches
            // libtorrent's "seeding" state - it sits in "finished" once the
            // selected files are done - yet it is seeding those files, so
            // report it as Seeding instead of "Downloading".
            state = "Seeding";
        } else {
            // Feature: make it visible that an incomplete torrent is
            // already uploading its downloaded pieces to other peers
            // (libtorrent does this on its own - there's nothing to turn
            // on). num_uploads is how many peers are currently unchoked,
            // i.e. actively receiving data from us right now.
            state = (st.num_uploads > 0)
                    ? "Downloading (uploading to " + std::to_string(st.num_uploads) +
                      (st.num_uploads == 1 ? " peer)" : " peers)")
                    : "Downloading";
        }

        std::string name =
                "Torrent " +
                std::to_string(index);

        if (!st.name.empty()) {
            name = st.name;
        }

        output += name +
                  " • " + state +
                  " • " + std::to_string(pct) + "%" +
                  " • ↓ " + formatBytes(st.download_payload_rate) + "/s" +
                  " • ↑ " + formatBytes(st.upload_payload_rate) + "/s" +
                  " • Size " + downloadedText +
                  " • Seeds " +
                  std::to_string(connectedSeeds) +
                  " (" +
                  std::to_string(totalSeeds) +
                  ")" +
                  " • Peers " +
                  std::to_string(connectedPeers) +
                  " (" +
                  std::to_string(totalPeers) +
                  ")" +
                  " • " + formatEta(eta) +
                  "\n";

        index++;
    }

    if (output.empty()) {
        output = "No active torrents";
    }

    return env->NewStringUTF(output.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_pauseAll(
        JNIEnv*,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);
    g_pausedAll = true;

    for (int i = 0; i < static_cast<int>(g_handles.size()); ++i) {
        if (i < static_cast<int>(g_pausedHandles.size())) {
            g_pausedHandles[i] = true;
        }

        if (g_handles[i].is_valid()) {
            g_handles[i].unset_flags(lt::torrent_flags::auto_managed);
            g_handles[i].pause();
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_resumeAll(
        JNIEnv*,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);
    g_pausedAll = false;

    for (int i = 0; i < static_cast<int>(g_handles.size()); ++i) {
        if (i < static_cast<int>(g_pausedHandles.size())) {
            g_pausedHandles[i] = false;
        }

        if (g_handles[i].is_valid()) {
            g_handles[i].clear_error();
            // not auto managed: only the user pauses / resumes this torrent
            g_handles[i].unset_flags(lt::torrent_flags::auto_managed);
            g_handles[i].resume();
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_pauseTorrent(
        JNIEnv*,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    __android_log_print(ANDROID_LOG_INFO, "TorrentOrLT", "pauseTorrent called index=%d", static_cast<int>(index));

    int i = index - 1;

    if (i >= 0 && i < static_cast<int>(g_handles.size())) {
        if (i < static_cast<int>(g_pausedHandles.size())) {
            g_pausedHandles[i] = true;
        }

        if (g_handles[i].is_valid()) {
            g_handles[i].unset_flags(lt::torrent_flags::auto_managed);
            g_handles[i].pause();
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_resumeTorrent(
        JNIEnv*,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    __android_log_print(ANDROID_LOG_INFO, "TorrentOrLT", "resumeTorrent called index=%d", static_cast<int>(index));

    int i = index - 1;

    if (i >= 0 && i < static_cast<int>(g_handles.size())) {
        if (i < static_cast<int>(g_pausedHandles.size())) {
            g_pausedHandles[i] = false;
        }

        if (g_handles[i].is_valid()) {
            g_handles[i].clear_error();
            // not auto managed: only the user pauses / resumes this torrent
            g_handles[i].unset_flags(lt::torrent_flags::auto_managed);
            g_handles[i].resume();
        }
    }
}

// Where fast-resume files are kept (app-private folder, created by the
// caller). Safe to call more than once.
extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_setResumeDataDir(
        JNIEnv* env,
        jobject,
        jstring dir) {

    std::lock_guard<std::mutex> lock(g_mutex);

    try {
        g_resumeDir = toString(env, dir);

        if (!g_resumeDir.empty()) {
            ::mkdir(g_resumeDir.c_str(), 0700);
        }
    } catch (...) {
        g_resumeDir.clear();
    }
}

// Asks libtorrent for fresh resume data of every torrent that has
// metadata, then waits up to waitMs for the answers and writes them to
// disk. Returns how many torrents were asked, 0 on any problem.
extern "C"
JNIEXPORT jint JNICALL
Java_com_example_torrentor_TorrentNative_saveResumeData(
        JNIEnv*,
        jobject,
        jint waitMs) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_session || g_resumeDir.empty()) {
        return 0;
    }

    int requested = 0;

    try {
        // Write whatever earlier requests produced first.
        updatePortForwardingAlerts();
        g_resumePending = 0;

        for (auto& handle : g_handles) {
            if (!handle.is_valid()) continue;

            try {
                if (!handle.torrent_file()) continue;

                std::string hash = getHashFromHandle(handle);
                if (!isSafeHashName(hash)) continue;

                // Unchanged torrents are skipped, unless this one has
                // never been saved.
                lt::resume_data_flags_t flags = lt::torrent_handle::save_info_dict;

                if (resumeFileExists(hash)) {
                    flags |= lt::torrent_handle::only_if_modified;
                }

                handle.save_resume_data(flags);
                requested++;
                g_resumePending++;
            } catch (...) {
                // Skip this torrent, carry on with the rest.
            }
        }

        auto deadline = std::chrono::steady_clock::now() +
                        std::chrono::milliseconds(waitMs > 0 ? waitMs : 0);

        while (g_resumePending > 0 && std::chrono::steady_clock::now() < deadline) {
            g_session->wait_for_alert(std::chrono::milliseconds(50));
            updatePortForwardingAlerts();
        }

        updatePortForwardingAlerts();
        g_resumePending = 0;
    } catch (...) {
        g_resumePending = 0;
        return 0;
    }

    return requested;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_removeTorrent(
        JNIEnv*,
        jobject,
        jint index,
        jboolean deleteFiles) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i >= 0 && i < static_cast<int>(g_handles.size())) {
        // A removed torrent must not leave stale resume data behind.
        std::string removedHash = getHashFromHandle(g_handles[i]);

        if (g_session && g_handles[i].is_valid()) {
            if (deleteFiles) {
                g_session->remove_torrent(
                        g_handles[i],
                        lt::session::delete_files
                );
            } else {
                g_session->remove_torrent(
                        g_handles[i]
                );
            }
        }

        g_handles.erase(
                g_handles.begin() + i
        );

        if (i >= 0 && i < static_cast<int>(g_pausedHandles.size())) {
            g_pausedHandles.erase(
                    g_pausedHandles.begin() + i
            );
        }

        deleteResumeFile(removedHash);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_removeAllTorrents(
        JNIEnv*,
        jobject,
        jboolean deleteFiles) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_session) {
        for (auto& handle : g_handles) {
            if (!handle.is_valid()) {
                continue;
            }

            deleteResumeFile(getHashFromHandle(handle));

            try {
                if (deleteFiles) {
                    g_session->remove_torrent(
                            handle,
                            lt::session::delete_files
                    );
                } else {
                    g_session->remove_torrent(handle);
                }
            } catch (...) {
                // Ignore bad handle and continue clearing the rest.
            }
        }
    }

    g_handles.clear();
    g_pausedHandles.clear();
    g_pausedAll = false;
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentFilesByIndex(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto info = handle.torrent_file();

    if (!info) {
        return env->NewStringUTF("Metadata not ready");
    }

    std::string output;
    auto const& files = info->files();

    for (int f = 0; f < files.num_files(); ++f) {
        output += std::to_string(f);
        output += "|";
        output += files.file_path(f);
        output += "|";
        output += std::to_string(files.file_size(f));
        output += "\n";
    }

    if (output.empty()) {
        output = "No files";
    }

    return env->NewStringUTF(output.c_str());
}


// Feature: Web UI file streaming/download (WebUiServer.kt). Returns this
// torrent's current save path straight from the live handle (the same
// torrent_status field already read elsewhere in this file, e.g.
// list_seeds/list_peers above), so it always matches reality even after
// a moveTorrentStorage() call - rather than trusting a value cached on
// the Kotlin side. Empty string on any invalid index/handle.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentSavePath(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("");
    }

    lt::torrent_status st = handle.status();
    return env->NewStringUTF(st.save_path.c_str());
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentFileProgress(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto info = handle.torrent_file();

    if (!info) {
        return env->NewStringUTF("Metadata not ready");
    }

    std::vector<std::int64_t> progress;

    try {
        handle.file_progress(progress);
    } catch (...) {
        return env->NewStringUTF("Progress not ready");
    }

    std::string output;
    auto const& files = info->files();

    int fileCount = files.num_files();

    for (int f = 0; f < fileCount; ++f) {
        long long done = 0;

        if (f < static_cast<int>(progress.size())) {
            done = static_cast<long long>(progress[f]);
        }

        long long total = static_cast<long long>(files.file_size(f));

        int percent = 0;

        if (total > 0) {
            percent = static_cast<int>(
                    (static_cast<double>(done) /
                     static_cast<double>(total)) * 100.0
            );
        }

        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;

        output += std::to_string(f);
        output += "|";
        output += files.file_path(f);
        output += "|";
        output += std::to_string(done);
        output += "|";
        output += std::to_string(total);
        output += "|";
        output += std::to_string(percent);
        output += "\n";
    }

    if (output.empty()) {
        output = "No files";
    }

    return env->NewStringUTF(output.c_str());
}


// Feature: per-file Seeds/Peers, the same idea as the torrent-level
// Seeds/Peers count on the General tab (st.num_seeds / st.num_peers),
// just scoped to one file instead of the whole torrent. libtorrent has no
// per-file counter for this (file_progress() only gives downloaded
// bytes - see getTorrentFileProgress above), so it's computed here from
// each currently connected peer's own piece bitfield (peer_info::pieces):
// a peer counts as a "seed" for this file if it has every piece in the
// file's piece range, or as a "peer" if it has at least one piece of the
// file but not all of them. file_storage::file_offset()/piece_length()
// give the file's first piece, and file_num_pieces() gives how many
// pieces the file spans (handles a file sharing its first/last piece
// with a neighboring file correctly, unlike computing the count by hand).
// Each line also carries the torrent's tracker-reported totalSeeds/
// totalPeers (st.list_seeds/st.list_peers - the same values the main
// card's "(Y)" already shows), repeated for every file since libtorrent
// has no equivalent total scoped to one file, plus uploadingCount - the
// file-scoped version of the main card's "Uploading to: N peer(s)"
// (st.num_uploads): peers we have not choked that still lack at least
// one piece of this file.
// Returns "" (never throws/crashes) on any failure.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentFileSwarm(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto info = handle.torrent_file();

    if (!info) {
        return env->NewStringUTF("Metadata not ready");
    }

    std::vector<lt::peer_info> peers;

    try {
        handle.get_peer_info(peers);
    } catch (...) {
        return env->NewStringUTF("Peers not ready");
    }

    // (added) The parenthetical totals shown alongside each file's
    // connected seed/peer count - same as the "(Y)" in the main card's
    // "Seeds X (Y)" - come from the torrent's tracker-reported swarm size
    // (list_seeds/list_peers). libtorrent has no such total scoped to one
    // file, so every file row reuses this same torrent-wide pair; it is
    // not a true per-file swarm size, just the same honest total the main
    // card already shows.
    lt::torrent_status st = handle.status();
    int totalSeeds = st.list_seeds;
    int totalPeers = st.list_peers;

    // Torrent-wide copies, computed exactly like the General/Swarm tabs
    // (getTorrentAvailability): libtorrent's distributed_copies, or - when
    // it is -1 - the tracker seed counts. A finished torrent, and a partial
    // download that has finished its selected files, has few or no
    // connected peers (seeds don't stay connected to a seeder), so a purely
    // per-file count of connected peers + us would read just "1.000". For a
    // file we hold completely the torrent-wide figure is the real one, so
    // it is used when it is higher (see below).
    float torrentAvail = st.distributed_copies;

    if (torrentAvail < 0.0f) {
        if (st.list_seeds > 0) {
            torrentAvail = static_cast<float>(st.list_seeds);
        } else if (st.num_seeds > 0) {
            torrentAvail = static_cast<float>(st.num_seeds);
        } else if (st.is_seeding || st.progress >= 1.0f) {
            torrentAvail = 1.0f;
        } else {
            torrentAvail = 0.0f;
        }
    }

    int torrentCopiesMilli = static_cast<int>(torrentAvail * 1000.0f + 0.5f);

    std::string output;
    auto const& files = info->files();

    int fileCount = files.num_files();
    int pieceLength = files.piece_length();

    for (int f = 0; f < fileCount; ++f) {
        int numFilePieces = files.file_num_pieces(f);

        int firstPiece = (pieceLength > 0)
                ? static_cast<int>(files.file_offset(f) / pieceLength)
                : 0;

        int seedCount = 0;
        int peerCount = 0;
        // Feature: "Uploading to X peer(s)" per file - the file-scoped
        // equivalent of the main card's "Uploading to: N peer(s)"
        // (st.num_uploads, lines ~2877-2879), which counts peers we have
        // currently unchoked (peer_info::choked means **we** have choked
        // that peer, so its absence means we haven't - i.e. we're willing
        // to upload to them). Scoped to one file by also requiring the
        // peer to still be missing at least one piece in that file's
        // range - a peer that already has every piece of this file has
        // nothing left to request from it, unchoked or not.
        int uploadingCount = 0;

        // Feature: per-file availability - which pieces of this file exist
        // anywhere we can reach: at least one connected peer has them (their
        // bitfield, read in the loop below) or we already have them. Reported
        // as the percentage of the file's pieces that are available, -1 when
        // it can't be worked out. Read-only; changes nothing.
        // pieceCopies[k] = how many connected peers have piece k of this
        // file (we add ourselves below), which also gives the file's
        // "distributed copies" figure.
        std::vector<int> pieceCopies(
                numFilePieces > 0 ? static_cast<size_t>(numFilePieces) : 0, 0);

        if (numFilePieces > 0) {
            for (auto const& peer : peers) {
                int bitfieldSize = peer.pieces.size();

                if (bitfieldSize <= 0) {
                    continue;
                }

                int haveCount = 0;

                for (int p = firstPiece; p < firstPiece + numFilePieces; ++p) {
                    if (p < 0 || p >= bitfieldSize) {
                        continue;
                    }

                    if (peer.pieces[lt::piece_index_t(p)]) {
                        haveCount++;

                        if (p - firstPiece >= 0 && p - firstPiece < numFilePieces) {
                            pieceCopies[static_cast<size_t>(p - firstPiece)]++;
                        }
                    }
                }

                if (haveCount >= numFilePieces) {
                    seedCount++;
                } else if (haveCount > 0) {
                    peerCount++;
                }

                bool weHaveChokedThem =
                        static_cast<bool>(peer.flags & lt::peer_info::choked);

                if (!weHaveChokedThem && haveCount < numFilePieces) {
                    uploadingCount++;
                }
            }
        }

        int availabilityPercent = -1;
        // Distributed copies in thousandths: whole number = how many full
        // copies of the file exist (peers + us) i.e. the rarest piece's
        // count; fraction = share of pieces that have more than that.
        // -1 = unknown.
        int copiesMilli = -1;

        try {
            if (numFilePieces <= 0) {
                availabilityPercent = 100;
            } else {
                int availableCount = 0;
                int minCopies = -1;
                int ownedCount = 0;

                for (int k = 0; k < numFilePieces; ++k) {
                    int c = pieceCopies[static_cast<size_t>(k)];

                    if (handle.have_piece(lt::piece_index_t(firstPiece + k))) {
                        c++;
                        ownedCount++;
                    }

                    pieceCopies[static_cast<size_t>(k)] = c;

                    if (c > 0) {
                        availableCount++;
                    }

                    if (minCopies < 0 || c < minCopies) {
                        minCopies = c;
                    }
                }

                availabilityPercent = static_cast<int>(
                        (static_cast<long long>(availableCount) * 100LL) / numFilePieces);

                int above = 0;

                for (int k = 0; k < numFilePieces; ++k) {
                    if (pieceCopies[static_cast<size_t>(k)] > minCopies) {
                        above++;
                    }
                }

                copiesMilli = minCopies * 1000 + static_cast<int>(
                        (static_cast<long long>(above) * 1000LL) / numFilePieces);

                // We hold this whole file: report the torrent-wide copies
                // when higher (connected peers alone under-count it).
                if (ownedCount >= numFilePieces && torrentCopiesMilli > copiesMilli) {
                    copiesMilli = torrentCopiesMilli;
                }
            }
        } catch (...) {
            availabilityPercent = -1;
            copiesMilli = -1;
        }

        output += std::to_string(f);
        output += "|";
        output += std::to_string(seedCount);
        output += "|";
        output += std::to_string(peerCount);
        output += "|";
        output += std::to_string(totalSeeds);
        output += "|";
        output += std::to_string(totalPeers);
        output += "|";
        output += std::to_string(uploadingCount);
        output += "|";
        output += std::to_string(availabilityPercent);
        output += "|";
        output += std::to_string(copiesMilli);
        output += "\n";
    }

    if (output.empty()) {
        output = "No files";
    }

    return env->NewStringUTF(output.c_str());
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentTrackers(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    std::string output;

    auto trackers = handle.trackers();

    for (const auto& tracker : trackers) {
        output += tracker.url;
        output += "\n";
    }

    if (output.empty()) {
        output = "No trackers";
    }

    return env->NewStringUTF(output.c_str());
}



extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentTrackerStatus(
        JNIEnv* env,
        jobject,
        jint index)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    std::string output;

    try {
        auto trackers = handle.trackers();

        // Even with no real trackers the DHT / PeX / LSD entries below are
        // still shown, so the tab always says whether those are on.
        output += "Tracker Status\n\n";

        std::string specialOutput;

        // DHT / PeX / LSD as their own entries at the TOP of the list (before the real trackers): on
        // ("Working") or off with the reason (shown red by the app and the
        // Web UI), plus how many connected peers each one found. Never uses
        // the words "Error / Failed" - TorrentNotifier counts those.
        {
            bool isPrivate = false;
            auto ti = handle.torrent_file();
            if (ti && ti->priv()) {
                isPrivate = true;
            }

            auto flags = handle.flags();
            bool dhtFlagOff = static_cast<bool>(flags & lt::torrent_flags::disable_dht);
            bool pexFlagOff = static_cast<bool>(flags & lt::torrent_flags::disable_pex);
            bool lsdFlagOff = static_cast<bool>(flags & lt::torrent_flags::disable_lsd);

            bool dhtSessionOn = true;
            bool lsdSessionOn = true;
            if (g_session) {
                dhtSessionOn = g_session->is_dht_running();
                lsdSessionOn = g_session->get_settings().get_bool(lt::settings_pack::enable_lsd);
            }

            int peersDht = 0, peersPex = 0, peersLsd = 0;
            {
                std::vector<lt::peer_info> peerList;
                handle.get_peer_info(peerList);
                for (const auto& p : peerList) {
                    if (static_cast<bool>(p.source & lt::peer_info::dht)) peersDht++;
                    if (static_cast<bool>(p.source & lt::peer_info::pex)) peersPex++;
                    if (static_cast<bool>(p.source & lt::peer_info::lsd)) peersLsd++;
                }
            }

            auto addEntry = [&](const char* name, bool on, const std::string& offReason,
                                const char* onMessage, int peerCount) {
                specialOutput += "URL: ";
                specialOutput += name;
                specialOutput += "\nStatus: ";
                specialOutput += on ? std::string("Working") : ("Off (" + offReason + ")");
                specialOutput += "\nMessage: ";
                specialOutput += on ? std::string(onMessage)
                             : (std::string(name) + " is turned off - " + offReason);
                specialOutput += "\nPeers: ";
                specialOutput += std::to_string(peerCount);
                specialOutput += "\n\n";
            };

            std::string dhtReason = !dhtSessionOn ? "DHT is off for the whole app"
                                    : isPrivate ? "private torrent"
                                    : "disabled for this torrent";
            addEntry("DHT", dhtSessionOn && !isPrivate && !dhtFlagOff, dhtReason,
                     "Distributed hash table - finds peers without a tracker", peersDht);

            addEntry("PeX", g_pexEnabled && !isPrivate && !pexFlagOff,
                     !g_pexEnabled ? "PEX is off for the whole app"
                                   : isPrivate ? "private torrent" : "disabled for this torrent",
                     "Peer exchange - peers share other peers they know", peersPex);

            std::string lsdReason = !lsdSessionOn ? "Local Service Discovery is off for the whole app"
                                    : isPrivate ? "private torrent"
                                    : "disabled for this torrent";
            addEntry("LSD", lsdSessionOn && !isPrivate && !lsdFlagOff, lsdReason,
                     "Local Service Discovery - finds peers on your own network", peersLsd);
        }

        output += specialOutput;

        for (const auto& tracker : trackers) {
            output += "URL: ";
            output += tracker.url;

            output += "\nTier: ";
            output += std::to_string(tracker.tier);

            // Real per-tracker state. libtorrent 2.x keeps fail count, last
            // error, the tracker's own message and scrape numbers on each
            // endpoint (one per local address / IP family) and per
            // info-hash version, not on the announce_entry itself.
            int maxFails = 0;
            bool anyUpdating = false;
            std::string lastError;
            std::string trackerMessage;
            int scrapeIncomplete = -1;
            int scrapeComplete = -1;
            int scrapeDownloaded = -1;
            int endpointCount = 0;
            int endpointsFailing = 0;

            for (const auto& ep : tracker.endpoints) {
                endpointCount++;
                bool epFailing = false;

                for (const auto& ih : ep.info_hashes) {
                    if (static_cast<int>(ih.fails) > maxFails) {
                        maxFails = static_cast<int>(ih.fails);
                    }

                    if (ih.fails > 0) epFailing = true;
                    if (ih.updating) anyUpdating = true;

                    if (ih.last_error) {
                        epFailing = true;
                        if (lastError.empty()) lastError = ih.last_error.message();
                    }

                    if (trackerMessage.empty() && !ih.message.empty()) {
                        trackerMessage = ih.message;
                    }

                    if (ih.scrape_incomplete > scrapeIncomplete) scrapeIncomplete = ih.scrape_incomplete;
                    if (ih.scrape_complete > scrapeComplete) scrapeComplete = ih.scrape_complete;
                    if (ih.scrape_downloaded > scrapeDownloaded) scrapeDownloaded = ih.scrape_downloaded;
                }

                if (epFailing) endpointsFailing++;
            }

            bool allFailing = endpointCount > 0 && endpointsFailing == endpointCount;

            output += "\nFails: ";
            output += std::to_string(maxFails);

            output += "\nVerified: ";
            output += tracker.verified ? "Yes" : "No";

            // "Status: Error / Failed" is matched by TorrentNotifier
            // (tracker error notification) - keep that exact text.
            output += "\nStatus: ";
            if (allFailing) {
                output += "Error / Failed";
            } else if (anyUpdating) {
                output += "Updating...";
            } else if (tracker.verified || (endpointCount > 0 && endpointsFailing < endpointCount &&
                                             (!trackerMessage.empty() || scrapeIncomplete >= 0))) {
                output += "Working";
            } else if (maxFails > 0) {
                // Has failed announces but isn't confirmed dead on every
                // address - shown orange. Not "Error / Failed", so it does
                // not trigger the tracker error notification.
                output += "Failing";
            } else {
                output += "Not contacted yet";
            }

            output += "\nMessage: ";
            if (!trackerMessage.empty()) {
                output += trackerMessage;
            } else if (allFailing) {
                output += "No message from the tracker";
            } else if (tracker.verified) {
                output += "Announce OK";
            } else {
                output += "No tracker message yet";
            }

            if (!lastError.empty()) {
                output += "\nLast error: ";
                output += lastError;
            }

            if (scrapeIncomplete >= 0 || scrapeComplete >= 0 || scrapeDownloaded >= 0) {
                output += "\nTracker reports: Seeds ";
                output += scrapeComplete >= 0 ? std::to_string(scrapeComplete) : std::string("--");
                output += "  Peers ";
                output += scrapeIncomplete >= 0 ? std::to_string(scrapeIncomplete) : std::string("--");
                output += "  Downloaded ";
                output += scrapeDownloaded >= 0 ? std::to_string(scrapeDownloaded) : std::string("--");
            }

            output += "\nSource: ";
            if (tracker.source & lt::announce_entry::source_torrent) {
                output += "Torrent";
            } else if (tracker.source & lt::announce_entry::source_client) {
                output += "Client";
            } else if (tracker.source & lt::announce_entry::source_magnet_link) {
                output += "Magnet";
            } else {
                output += "Unknown";
            }

            output += "\n\n";
        }
    }
    catch (...) {
        return env->NewStringUTF("Tracker status unavailable");
    }

    return env->NewStringUTF(output.c_str());
}


// Feature: per-torrent DHT status and where this torrent's connected peers
// came from (tracker / DHT / PEX / LSD / resume data / incoming). The DHT
// itself is one shared table for the whole session, so what is per torrent
// is whether it uses DHT at all and how many peers it actually got from it.
// Caller must hold g_mutex.
static std::string buildPeerSourceSummary(
        const lt::torrent_handle& handle,
        const std::vector<lt::peer_info>& peers) {

    std::string dhtState = "Active";

    try {
        bool isPrivate = false;
        auto ti = handle.torrent_file();
        if (ti && ti->priv()) {
            isPrivate = true;
        }

        if (g_session && !g_session->is_dht_running()) {
            dhtState = "Off (DHT is turned off for the whole app)";
        } else if (isPrivate) {
            dhtState = "Not used (private torrent)";
        } else if (static_cast<bool>(handle.flags() & lt::torrent_flags::disable_dht)) {
            dhtState = "Disabled for this torrent";
        }
    } catch (...) {
        dhtState = "Unknown";
    }

    int fromTracker = 0, fromDht = 0, fromPex = 0, fromLsd = 0, fromResume = 0, incoming = 0;

    for (const auto& peer : peers) {
        bool any = false;

        if (static_cast<bool>(peer.source & lt::peer_info::tracker)) { fromTracker++; any = true; }
        if (static_cast<bool>(peer.source & lt::peer_info::dht)) { fromDht++; any = true; }
        if (static_cast<bool>(peer.source & lt::peer_info::pex)) { fromPex++; any = true; }
        if (static_cast<bool>(peer.source & lt::peer_info::lsd)) { fromLsd++; any = true; }
        if (static_cast<bool>(peer.source & lt::peer_info::resume_data)) { fromResume++; any = true; }
        if (!any) { incoming++; }
    }

    std::string out = "DHT: " + dhtState;
    out += "\nPeers from: Tracker " + std::to_string(fromTracker);
    out += " \u00B7 DHT " + std::to_string(fromDht);
    out += " \u00B7 PEX " + std::to_string(fromPex);
    out += " \u00B7 LSD " + std::to_string(fromLsd);
    out += " \u00B7 Resume data " + std::to_string(fromResume);
    out += " \u00B7 Incoming " + std::to_string(incoming);
    return out;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentPeers(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    std::vector<lt::peer_info> peers;

    handle.get_peer_info(peers);

    std::string output;

    std::string sourceSummary = buildPeerSourceSummary(handle, peers);

    for (const auto& peer : peers) {
        int progressPercent =
                static_cast<int>(
                        peer.progress * 100.0f
                );

        std::string client = peer.client;

        if (client.empty()) {
            client = "Unknown client";
        }

        std::string ipAddress =
                peer.ip.address().to_string();

        // uTP vs TCP: connection_type only separates BitTorrent/web-seed/
        // http-seed, so whether the socket itself is uTP is a separate bit
        // in peer.flags (peer_info::utp_socket) - same bitfield-flag
        // pattern already used elsewhere in this file for torrent_flags_t.
        bool isUtp = static_cast<bool>(peer.flags & lt::peer_info::utp_socket);

        output += "IP: ";
        output += ipAddress;

        output += "\nClient: ";
        output += client;

        output += "\nConnection: ";
        output += isUtp ? "uTP" : "TCP";

        {
            std::string sources;
            auto addSource = [&](bool on, const char* name) {
                if (!on) return;
                if (!sources.empty()) sources += " + ";
                sources += name;
            };
            addSource(static_cast<bool>(peer.source & lt::peer_info::tracker), "Tracker");
            addSource(static_cast<bool>(peer.source & lt::peer_info::dht), "DHT");
            addSource(static_cast<bool>(peer.source & lt::peer_info::pex), "PEX");
            addSource(static_cast<bool>(peer.source & lt::peer_info::lsd), "LSD");
            addSource(static_cast<bool>(peer.source & lt::peer_info::resume_data), "Resume data");
            if (sources.empty()) sources = "Incoming";
            output += "\nFound via: ";
            output += sources;
        }

        output += "\nProgress: ";
        output += std::to_string(progressPercent);
        output += "%";

        output += "\n↓ ";
        output += std::to_string(peer.down_speed / 1024);
        output += " KB/s";

        output += "  ↑ ";
        output += std::to_string(peer.up_speed / 1024);
        output += " KB/s";

        // Feature: upload-diagnostic flags per peer - lets you tell
        // "connected but they don't want anything from us" (remote_interested
        // false - nothing to fix, they're just not interested right now)
        // apart from "we're the ones holding back" (remote_choked true - our
        // own choking algorithm hasn't given them a slot yet). Note the
        // libtorrent naming is from the peer's point of view: "choked"
        // (unused here) means THEY have choked US, which is a download
        // concern, not an upload one - "remote_choked" is the one that
        // means we've choked them.
        bool theyAreInterested =
                static_cast<bool>(peer.flags & lt::peer_info::remote_interested);

        bool weAreChokingThem =
                static_cast<bool>(peer.flags & lt::peer_info::remote_choked);

        bool optimisticUnchoke =
                static_cast<bool>(peer.flags & lt::peer_info::optimistic_unchoke);

        output += "\nInterested in us: ";
        output += theyAreInterested ? "Yes" : "No";

        output += "  We're choking them: ";
        output += weAreChokingThem ? "Yes" : "No";

        if (optimisticUnchoke) {
            output += "\n(Optimistic unchoke slot)";
        }

        output += "\n\n";
    }

    if (output.empty()) {
        output = "No peers";
    }

    output = sourceSummary + "\n\n" + output;

    return env->NewStringUTF(output.c_str());
}

// Just the two summary lines (DHT status + peer sources) for the General tab.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentPeerSources(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("");
    }

    try {
        std::vector<lt::peer_info> peers;
        handle.get_peer_info(peers);
        std::string summary = buildPeerSourceSummary(handle, peers);
        return env->NewStringUTF(summary.c_str());
    } catch (...) {
        return env->NewStringUTF("");
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentHash(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    std::string hash =
            getHashFromHandle(handle);

    if (hash.empty()) {
        return env->NewStringUTF("Hash not ready");
    }

    return env->NewStringUTF(hash.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentMagnet(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    std::string magnet =
            lt::make_magnet_uri(handle);

    if (magnet.empty()) {
        return env->NewStringUTF("Magnet not ready");
    }

    return env->NewStringUTF(magnet.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_hasTorrentHash(
        JNIEnv* env,
        jobject,
        jstring hashValue) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash =
            normalizeHash(
                    toString(env, hashValue)
            );

    return hasHashAlready(hash)
           ? JNI_TRUE
           : JNI_FALSE;
}

// Re-adding a torrent that is already in the session: instead of refusing,
// merge any trackers from the new .torrent file / magnet link into the
// existing torrent (like qBittorrent). Returns "OK|<added>|<skipped>",
// "PRIVATE" (private torrents are left alone) or "ERROR|<message>".
// Never throws and never creates a second handle for the same hash.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_mergeTorrentTrackers(
        JNIEnv* env,
        jobject,
        jstring hashValue,
        jstring sourceValue)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    try {
        std::string hash = normalizeHash(toString(env, hashValue));
        std::string source = toString(env, sourceValue);

        if (hash.empty() || source.empty()) {
            return env->NewStringUTF("ERROR|Missing hash or source");
        }

        lt::torrent_handle handle = findHandleByHash(hash);

        if (!handle.is_valid()) {
            return env->NewStringUTF("ERROR|Torrent is not in the session");
        }

        auto existingInfo = handle.torrent_file();

        if (existingInfo && existingInfo->priv()) {
            return env->NewStringUTF("PRIVATE");
        }

        // The trackers the new source carries, with their tiers.
        std::vector<std::pair<std::string, int>> incoming;

        if (source.rfind("magnet:", 0) == 0) {
            lt::error_code ec;
            lt::add_torrent_params params = lt::parse_magnet_uri(source, ec);

            if (ec) {
                return env->NewStringUTF("ERROR|Could not read the magnet link");
            }

            for (size_t k = 0; k < params.trackers.size(); k++) {
                int tier = k < params.tracker_tiers.size() ? params.tracker_tiers[k] : 0;
                incoming.emplace_back(params.trackers[k], tier);
            }
        } else {
            lt::error_code ec;
            lt::torrent_info info(source, ec);

            if (ec) {
                return env->NewStringUTF("ERROR|Could not read the .torrent file");
            }

            for (const auto& t : info.trackers()) {
                incoming.emplace_back(t.url, static_cast<int>(t.tier));
            }
        }

        auto normalizeUrl = [](std::string u) {
            for (auto& c : u) {
                c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
            }
            while (!u.empty() && u.back() == '/') {
                u.pop_back();
            }
            return u;
        };

        std::set<std::string> known;
        int maxTier = 0;

        for (const auto& t : handle.trackers()) {
            known.insert(normalizeUrl(t.url));
            if (static_cast<int>(t.tier) > maxTier) {
                maxTier = static_cast<int>(t.tier);
            }
        }

        int added = 0;
        int skipped = 0;

        for (const auto& item : incoming) {
            if (item.first.empty()) continue;

            std::string key = normalizeUrl(item.first);

            if (known.count(key) > 0) {
                skipped++;
                continue;
            }

            lt::announce_entry entry(item.first);
            int tier = maxTier + 1 + item.second;
            entry.tier = static_cast<std::uint8_t>(tier > 255 ? 255 : tier);

            handle.add_tracker(entry);
            known.insert(key);
            added++;

            // Remember it so it is still there after a restart.
            try {
                std::string fileHash = getHashFromHandle(handle);

                if (!g_resumeDir.empty() && isSafeHashName(fileHash)) {
                    std::ofstream out(mergedTrackersPath(fileHash), std::ios::app);
                    out << static_cast<int>(entry.tier) << '\t' << item.first << '\n';
                }
            } catch (...) {
            }
        }

        if (added > 0) {
            handle.force_reannounce();
        }

        std::string result = "OK|" + std::to_string(added) + "|" + std::to_string(skipped);
        return env->NewStringUTF(result.c_str());
    }
    catch (...) {
        return env->NewStringUTF("ERROR|Could not merge trackers");
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentFileHash(
        JNIEnv* env,
        jobject,
        jstring path) {

    std::string torrentPath =
            toString(env, path);

    lt::error_code ec;
    lt::torrent_info info(torrentPath, ec);

    if (ec) {
        return env->NewStringUTF("");
    }

    std::string hash;

    try {
        auto hashes = info.info_hashes();

        if (hashes.has_v1()) {
            auto h = hashes.v1;

            hash = hashBytesToHex(
                    h.data(),
                    static_cast<int>(h.size())
            );
        } else if (hashes.has_v2()) {
            auto h = hashes.v2;

            hash = hashBytesToHex(
                    h.data(),
                    static_cast<int>(h.size())
            );
        }
    } catch (...) {
        hash = "";
    }

    hash = normalizeHash(hash);

    if (hash.empty()) {
        return env->NewStringUTF("");
    }

    // IMPORTANT:
    // Never return raw torrent hash bytes through NewStringUTF.
    // NewStringUTF only accepts valid text. Raw binary hashes can crash Android
    // with "input is not valid Modified UTF-8". This function returns HEX text only.
    return env->NewStringUTF(hash.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentPieceSize(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto info = handle.torrent_file();

    if (!info) {
        return env->NewStringUTF("Metadata not ready");
    }

    int pieceSize = 0;
    int pieces = 0;

    try {
        pieceSize = info->piece_length();
        pieces = info->num_pieces();
    } catch (...) {
        return env->NewStringUTF("Pieces not ready");
    }

    std::string output;
    output += "Piece size: ";
    output += formatBytes(pieceSize);
    output += "\n";

    output += "Pieces: ";
    output += std::to_string(pieces);

    return env->NewStringUTF(output.c_str());
}

// Feature 1: pushes the global upload/download rate limit globals into
// the running session - no restart needed, and (like applyConnectionSettings
// above) this never touches g_handles, so it cannot pause, resume or
// re-add anything. If the session doesn't exist yet, this is a no-op;
// ensureSession() above reads the same globals when it builds the first
// settings_pack, so a call made before startSession() still takes effect
// once the session actually starts.
static void applyGlobalSpeedLimits() {
    if (!g_session) {
        return;
    }

    try {
        lt::settings_pack pack;

        pack.set_int(lt::settings_pack::upload_rate_limit, g_globalUploadLimit);
        pack.set_int(lt::settings_pack::download_rate_limit, g_globalDownloadLimit);

        g_session->apply_settings(pack);
    } catch (...) {
        // Prevent crash if settings cannot be applied at runtime.
    }
}

// Feature 1: Speed Control - global limits, in bytes/second (0 = unlimited).
extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_applyGlobalSpeedLimits(
        JNIEnv*,
        jobject,
        jint uploadLimit,
        jint downloadLimit) {

    std::lock_guard<std::mutex> lock(g_mutex);

    g_globalUploadLimit = uploadLimit > 0 ? uploadLimit : 0;
    g_globalDownloadLimit = downloadLimit > 0 ? downloadLimit : 0;

    applyGlobalSpeedLimits();
}

// Network policy: pause (true) or resume (false) every transfer because the
// current network is not one the user allowed. Never throws; does not change
// any torrent's own paused state.
extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_setNetworkPaused(
        JNIEnv*,
        jobject,
        jboolean paused) {

    std::lock_guard<std::mutex> lock(g_mutex);

    g_networkPaused = (paused == JNI_TRUE);

    if (!g_session) {
        return;
    }

    try {
        if (g_networkPaused) {
            g_session->pause();
        } else {
            g_session->resume();
        }
    } catch (...) {
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getGlobalSpeedLimits(
        JNIEnv* env,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string output =
            std::to_string(g_globalUploadLimit) + "|" +
            std::to_string(g_globalDownloadLimit);

    return env->NewStringUTF(output.c_str());
}

// Feature 1: Speed Control - per-torrent override, in bytes/second (0 =
// no per-torrent cap of its own; the global limit above still applies
// on top, same as every other torrent). Identified by info-hash, never
// list index, per the project's standing rule. Returns false (never
// throws/crashes) if the hash isn't found.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_setTorrentSpeedLimits(
        JNIEnv* env,
        jobject,
        jstring hashValue,
        jint uploadLimit,
        jint downloadLimit) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return JNI_FALSE;
    }

    try {
        handle.set_upload_limit(uploadLimit > 0 ? uploadLimit : 0);
        handle.set_download_limit(downloadLimit > 0 ? downloadLimit : 0);
    } catch (...) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

// Returns "" if the hash isn't found, otherwise "uploadLimit|downloadLimit"
// in bytes/second (0 = no per-torrent cap of its own).
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentSpeedLimits(
        JNIEnv* env,
        jobject,
        jstring hashValue) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return env->NewStringUTF("");
    }

    int uploadLimit = 0;
    int downloadLimit = 0;

    try {
        uploadLimit = handle.upload_limit();
        downloadLimit = handle.download_limit();
    } catch (...) {
        return env->NewStringUTF("");
    }

    std::string output =
            std::to_string(uploadLimit) + "|" +
            std::to_string(downloadLimit);

    return env->NewStringUTF(output.c_str());
}

// Streaming (step 1). streamPrepare: switches the torrent to sequential
// mode and gives the chosen file top priority so its pieces come first.
// Returns "OK|fileSize|pieceLength" or "ERR|reason". Never throws.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_streamPrepare(
        JNIEnv* env,
        jobject,
        jint index,
        jint fileIndex) {

    std::lock_guard<std::mutex> lock(g_mutex);

    try {
        int i = index - 1;
        if (i < 0 || i >= static_cast<int>(g_handles.size())) {
            return env->NewStringUTF("ERR|torrent not found");
        }

        auto handle = g_handles[i];
        if (!handle.is_valid()) {
            return env->NewStringUTF("ERR|torrent not found");
        }

        auto info = handle.torrent_file();
        if (!info || fileIndex < 0 || fileIndex >= info->num_files()) {
            return env->NewStringUTF("ERR|metadata not ready");
        }

        // Drop urgent-piece requests left over from an earlier stream/open:
        // libtorrent serves deadline pieces oldest-deadline-first, so stale
        // ones (even expired) would make this file wait behind them.
        handle.clear_piece_deadlines();

        handle.set_sequential_download(true);
        handle.file_priority(lt::file_index_t(fileIndex), lt::top_priority);
        handle.resume();

        std::int64_t fsize = info->files().file_size(lt::file_index_t(fileIndex));

        // Bytes of this file already downloaded (used for the free-space check).
        std::int64_t done = 0;
        try {
            std::vector<std::int64_t> prog;
            handle.file_progress(prog);
            if (fileIndex < static_cast<int>(prog.size())) done = prog[fileIndex];
        } catch (...) {
            done = 0;
        }

        // Some containers (MP4/MOV "moov" index, MKV cues) keep their index at
        // the very end of the file, and a player can't start until it has it.
        // Ask for the last ~8 MB (at least 2 pieces, at most 32) early; the
        // deadlines come after the first pieces of the file so playback of
        // the start is not delayed.
        if (fsize > 0) {
            std::int64_t plen = info->piece_length();
            if (plen < 1) plen = 1;
            int tailPieces = static_cast<int>((8LL * 1024 * 1024 + plen - 1) / plen);
            if (tailPieces < 2) tailPieces = 2;
            if (tailPieces > 32) tailPieces = 32;

            int lastP = static_cast<int>(
                    info->map_file(lt::file_index_t(fileIndex), fsize - 1, 1).piece);
            int firstP = static_cast<int>(
                    info->map_file(lt::file_index_t(fileIndex), 0, 1).piece);

            for (int k = 0; k < tailPieces; ++k) {
                int pp = lastP - k;
                if (pp < firstP) break;
                if (!handle.have_piece(lt::piece_index_t(pp))) {
                    handle.set_piece_deadline(lt::piece_index_t(pp), 3000 + k * 400);
                }
            }
        }

        std::string out = "OK|" + std::to_string(fsize) + "|" +
                std::to_string(info->piece_length()) + "|" +
                std::to_string(done);
        return env->NewStringUTF(out.c_str());
    } catch (...) {
        return env->NewStringUTF("ERR|native error");
    }
}

// streamWait: makes sure the pieces covering [offset, offset+length) of the
// file (plus a small read-ahead) are urgent, then waits until the pieces
// for the requested range are on disk or timeoutMs passes.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_streamWait(
        JNIEnv*,
        jobject,
        jint index,
        jint fileIndex,
        jlong offset,
        jlong length,
        jint timeoutMs) {

    int firstPiece = 0;
    int lastPiece = 0;
    lt::torrent_handle handle;

    {
        std::lock_guard<std::mutex> lock(g_mutex);

        try {
            int i = index - 1;
            if (i < 0 || i >= static_cast<int>(g_handles.size())) return JNI_FALSE;

            handle = g_handles[i];
            if (!handle.is_valid()) return JNI_FALSE;

            auto info = handle.torrent_file();
            if (!info || fileIndex < 0 || fileIndex >= info->num_files()) return JNI_FALSE;

            std::int64_t fileSize = info->files().file_size(lt::file_index_t(fileIndex));
            if (fileSize <= 0 || offset < 0 || offset >= fileSize) return JNI_TRUE;
            if (length < 1) length = 1;

            std::int64_t endByte = offset + length - 1;
            if (endByte >= fileSize) endByte = fileSize - 1;

            firstPiece = static_cast<int>(
                    info->map_file(lt::file_index_t(fileIndex), offset, 1).piece);
            lastPiece = static_cast<int>(
                    info->map_file(lt::file_index_t(fileIndex), endByte, 1).piece);

            // Read-ahead: up to 16 more pieces, never past the file's end.
            std::int64_t readAheadEnd = endByte + 16LL * info->piece_length();
            if (readAheadEnd >= fileSize) readAheadEnd = fileSize - 1;
            int aheadPiece = static_cast<int>(
                    info->map_file(lt::file_index_t(fileIndex), readAheadEnd, 1).piece);

            int n = 0;
            // A small request (video playback chunk) only needs the next few
            // pieces urgently, spaced out so playback order is kept. A large
            // request (opening a whole file) marks every piece in the range
            // urgent with tight spacing, so all the bandwidth goes to it.
            int rangePieces = lastPiece - firstPiece + 1;
            bool wholeRange = rangePieces > 16;
            int cap = wholeRange ? (rangePieces + 16) : 40;
            if (cap > 4096) cap = 4096;
            int spacing = wholeRange ? 40 : 400;

            for (int p = firstPiece; p <= aheadPiece && n < cap; ++p, ++n) {
                if (!handle.have_piece(lt::piece_index_t(p))) {
                    handle.set_piece_deadline(lt::piece_index_t(p), 10 + n * spacing);
                }
            }
        } catch (...) {
            return JNI_FALSE;
        }
    }

    int waited = 0;
    while (waited <= timeoutMs) {
        bool all = true;
        try {
            for (int p = firstPiece; p <= lastPiece; ++p) {
                if (!handle.have_piece(lt::piece_index_t(p))) {
                    all = false;
                    break;
                }
            }
        } catch (...) {
            return JNI_FALSE;
        }

        if (all) return JNI_TRUE;

        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        waited += 100;
    }

    return JNI_FALSE;
}

// Feature 3: Sequential Download. set_sequential_download()/
// is_sequential_download() are plain torrent_handle methods - unlike
// first/last piece priority below, they don't need metadata, so they
// work the moment the handle exists (even for a magnet still resolving).
// Returns false (never throws/crashes) when the hash isn't found, so
// Kotlin can tell "not found" apart from "found, now off".
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_setSequentialDownload(
        JNIEnv* env,
        jobject,
        jstring hashValue,
        jboolean enabled) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return JNI_FALSE;
    }

    try {
        handle.set_sequential_download(enabled == JNI_TRUE);
    } catch (...) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_getSequentialDownload(
        JNIEnv* env,
        jobject,
        jstring hashValue) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return JNI_FALSE;
    }

    try {
        return handle.is_sequential_download() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

// Per-torrent DHT switch. "disabled" = true sets libtorrent's disable_dht
// flag for just this torrent (no DHT announces or lookups for it); the rest
// of the app keeps using DHT. No metadata needed, so it works the moment the
// handle exists. Returns false (never throws) when the hash isn't found.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_setTorrentDhtDisabled(
        JNIEnv* env,
        jobject,
        jstring hashValue,
        jboolean disabled) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return JNI_FALSE;
    }

    try {
        if (disabled == JNI_TRUE) {
            handle.set_flags(lt::torrent_flags::disable_dht);
        } else {
            handle.unset_flags(lt::torrent_flags::disable_dht);
        }
    } catch (...) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentDhtDisabled(
        JNIEnv* env,
        jobject,
        jstring hashValue) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return JNI_FALSE;
    }

    try {
        return static_cast<bool>(handle.flags() & lt::torrent_flags::disable_dht)
                ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

// Feature 3: First/Last Piece Priority. Boosts piece 0 and the final
// piece to top_priority (useful for previewing video/audio before the
// whole torrent finishes) or resets them to default_priority when
// turned off - this app has no other per-piece priority control, so
// default_priority is always the correct "off" state to restore.
// Needs the torrent's metadata (to know how many pieces it has and
// which index is actually last), so unlike sequential download this
// can genuinely fail on a magnet that hasn't resolved yet - that is
// reported back as false, not thrown, so the caller can retry once
// metadata is ready instead of mistaking it for a crash.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_setFirstLastPiecePriority(
        JNIEnv* env,
        jobject,
        jstring hashValue,
        jboolean enabled) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return JNI_FALSE;
    }

    auto info = handle.torrent_file();

    if (!info) {
        return JNI_FALSE;
    }

    try {
        int numPieces = info->num_pieces();

        if (numPieces <= 0) {
            return JNI_FALSE;
        }

        lt::download_priority_t priority =
                (enabled == JNI_TRUE) ? lt::top_priority : lt::default_priority;

        handle.piece_priority(lt::piece_index_t{0}, priority);

        if (numPieces > 1) {
            handle.piece_priority(
                    lt::piece_index_t{numPieces - 1},
                    priority
            );
        }
    } catch (...) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_getFirstLastPiecePriority(
        JNIEnv* env,
        jobject,
        jstring hashValue) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return JNI_FALSE;
    }

    auto info = handle.torrent_file();

    if (!info) {
        return JNI_FALSE;
    }

    try {
        auto priority = handle.piece_priority(lt::piece_index_t{0});
        return (priority == lt::top_priority) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

// Feature 5: Custom Save Folder. Moves an already-added torrent's files to
// a new folder. Identified by info-hash (never list index), per the
// project's persistence rules. The move itself happens asynchronously in
// libtorrent - this call only issues it; success or failure is reported
// through storage_moved_alert / storage_moved_failed_alert, which
// logTorrentAlert() already turns into an Execution Log line (see the 'I'
// and 'C' level checks above). This function returns false - never
// throws - when the hash isn't found or the new path is empty.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_moveTorrentStorage(
        JNIEnv* env,
        jobject,
        jstring hashValue,
        jstring newPath) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    std::string path = toString(env, newPath);

    if (path.empty()) {
        return JNI_FALSE;
    }

    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return JNI_FALSE;
    }

    try {
        handle.move_storage(path);
    } catch (...) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

// Piece map with one state per piece (coloured by the app / Web UI):
//   "OK|<totalPieces>|<completedPieces>|<pieceSize>\n<states>"
// where <states> has one character per piece (capped at 20000):
//   '2' = downloaded, '1' = downloading now (some blocks received),
//   '0' = missing but wanted, 'x' = missing and skipped (file not selected).
// Anything not starting with "OK|" is a plain message ("Metadata not
// ready", ...) for the caller to fall back on.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentPieceStates(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    try {
        auto info = handle.torrent_file();

        if (!info) {
            return env->NewStringUTF("Metadata not ready");
        }

        lt::torrent_status st = handle.status(lt::torrent_handle::query_pieces);

        int totalPieces = info->num_pieces();
        int pieceSize = info->piece_length();

        if (totalPieces <= 0) {
            return env->NewStringUTF("No pieces");
        }

        int shown = totalPieces > 20000 ? 20000 : totalPieces;

        std::string states(static_cast<size_t>(shown), '0');
        int completed = 0;

        // Skipped = priority 0 (one call for all pieces, not one per piece).
        std::vector<lt::download_priority_t> priorities;

        try {
            priorities = handle.get_piece_priorities();
        } catch (...) {
            priorities.clear();
        }

        for (int p = 0; p < totalPieces; ++p) {
            bool have = p < static_cast<int>(st.pieces.size()) && st.pieces[p];
            if (have) completed++;

            if (p >= shown) continue;

            if (have) {
                states[p] = '2';
            } else if (p < static_cast<int>(priorities.size()) &&
                       priorities[p] == lt::dont_download) {
                states[p] = 'x';
            }
        }

        // Pieces that are partly there right now.
        try {
            auto queue = handle.get_download_queue();

            for (const auto& pi : queue) {
                int p = static_cast<int>(pi.piece_index);

                if (p >= 0 && p < shown && states[p] != '2' &&
                    (pi.finished + pi.writing) > 0) {
                    states[p] = '1';
                }
            }
        } catch (...) {
        }

        std::string output = "OK|" + std::to_string(totalPieces) + "|" +
                std::to_string(completed) + "|" + std::to_string(pieceSize) + "\n" + states;

        return env->NewStringUTF(output.c_str());
    } catch (...) {
        return env->NewStringUTF("Pieces not ready");
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentPieces(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto info = handle.torrent_file();

    if (!info) {
        return env->NewStringUTF("Metadata not ready");
    }

    lt::torrent_status st;

    try {
        st = handle.status(lt::torrent_handle::query_pieces);
    } catch (...) {
        try {
            st = handle.status();
        } catch (...) {
            return env->NewStringUTF("Pieces not ready");
        }
    }

    int totalPieces = 0;
    int pieceSize = 0;

    try {
        totalPieces = info->num_pieces();
        pieceSize = info->piece_length();
    } catch (...) {
        return env->NewStringUTF("Pieces not ready");
    }

    if (totalPieces <= 0) {
        return env->NewStringUTF("No pieces");
    }

    int completedPieces = 0;

    try {
        for (int p = 0; p < totalPieces; ++p) {
            if (p < static_cast<int>(st.pieces.size()) && st.pieces[p]) {
                completedPieces++;
            }
        }
    } catch (...) {
        completedPieces = static_cast<int>(st.progress * totalPieces);
    }

    int percent =
            static_cast<int>(
                    (static_cast<double>(completedPieces) /
                     static_cast<double>(totalPieces)) * 100.0
            );

    std::string output;

    output += "Pieces\n\n";

    output += "Completed: ";
    output += std::to_string(completedPieces);
    output += " / ";
    output += std::to_string(totalPieces);
    output += "\n";

    output += "Progress: ";
    output += std::to_string(percent);
    output += "%\n";

    output += "Piece size: ";
    output += formatBytes(pieceSize);
    output += "\n\n";

    output += "Piece map:\n";
    output += "■ = downloaded\n";
    output += "□ = missing\n\n";

    int perLine = 50;
    int maxShown = totalPieces;

    if (maxShown > 5000) {
        maxShown = 5000;
    }

    for (int p = 0; p < maxShown; ++p) {
        bool havePiece = false;

        try {
            if (p < static_cast<int>(st.pieces.size()) && st.pieces[p]) {
                havePiece = true;
            }
        } catch (...) {
            havePiece = false;
        }

        output += havePiece ? "■" : "□";

        if ((p + 1) % perLine == 0) {
            output += "\n";
        }
    }

    if (maxShown < totalPieces) {
        output += "\n\nShowing first ";
        output += std::to_string(maxShown);
        output += " of ";
        output += std::to_string(totalPieces);
        output += " pieces.";
    }

    return env->NewStringUTF(output.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentTotalSize(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    long long totalSize = 0;

    try {
        lt::torrent_status st = handle.status();
        totalSize = static_cast<long long>(st.total_wanted);
    } catch (...) {
        totalSize = 0;
    }

    if (totalSize <= 0) {
        try {
            auto info = handle.torrent_file();

            if (!info) {
                return env->NewStringUTF("Size not ready");
            }

            auto const& files = info->files();

            for (int f = 0; f < files.num_files(); ++f) {
                totalSize += static_cast<long long>(files.file_size(f));
            }
        } catch (...) {
            return env->NewStringUTF("Size not ready");
        }
    }

    std::string output = formatBytes(totalSize);

    return env->NewStringUTF(output.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentStatistics(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    lt::torrent_status st;

    try {
        st = handle.status();
    } catch (...) {
        return env->NewStringUTF("Statistics not ready");
    }

    long long downloaded =
            static_cast<long long>(st.total_wanted_done);

    long long uploaded =
            static_cast<long long>(st.all_time_upload);

    long long totalSize =
            static_cast<long long>(st.total_wanted);

    double ratio = 0.0;

    if (downloaded > 0) {
        ratio =
                static_cast<double>(uploaded) /
                static_cast<double>(downloaded);
    }

    int progressPercent =
            static_cast<int>(
                    st.progress * 100.0f
            );

    int downKb = st.download_payload_rate / 1024;
    int upKb = st.upload_payload_rate / 1024;

    long remaining =
            (st.total_wanted > st.total_wanted_done)
            ? static_cast<long>(
                    st.total_wanted -
                    st.total_wanted_done
            )
            : 0L;

    long eta =
            (downKb > 0)
            ? remaining / (downKb * 1024)
            : 0L;

    std::ostringstream ratioStream;
    ratioStream.setf(std::ios::fixed);
    ratioStream.precision(2);
    ratioStream << ratio;

    std::string output;

    output += "Statistics\n\n";

    // Feature: downloaded/uploaded/total size and both speeds now scale
    // automatically (B/KB/MB/GB/TB) via formatBytes instead of always
    // printing raw bytes or a fixed "KB/s", so large torrents and fast
    // speeds read naturally instead of as huge byte counts.
    output += "Downloaded: ";
    output += formatBytes(downloaded);
    output += "\n";

    output += "Uploaded: ";
    output += formatBytes(uploaded);
    output += "\n";

    output += "Ratio: ";
    output += ratioStream.str();
    output += "\n\n";

    output += "Total size: ";
    output += formatBytes(totalSize);
    output += "\n";

    output += "Progress: ";
    output += std::to_string(progressPercent);
    output += "%\n";

    output += "Download speed: ";
    output += formatBytes(st.download_payload_rate);
    output += "/s\n";

    output += "Upload speed: ";
    output += formatBytes(st.upload_payload_rate);
    output += "/s\n";

    // Feature: visibility that this torrent uploads pieces it already has
    // to peers while it's still downloading the rest - libtorrent does
    // this automatically (no separate on/off switch needed), but the app
    // never showed it explicitly. num_uploads is the number of currently
    // unchoked peers, i.e. peers this torrent is actively sending data to
    // right now, regardless of whether the download itself is finished.
    output += "Uploading to: ";
    output += std::to_string(st.num_uploads);
    output += (st.num_uploads == 1) ? " peer\n" : " peers\n";

    // formatEta() already returns a string prefixed with "ETA: ", so don't
    // prepend it again here (that was producing "ETA: ETA: --" on screen).
    output += formatEta(eta);
    output += "\n";

    output += buildTimeLines(st);

    return env->NewStringUTF(output.c_str());
}

// The General tab's time lines ("Time active: ..." and, once seeding,
// "Seeding time: ..."). Returns an empty string if the torrent isn't
// available, so the General tab simply shows nothing extra.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentTimeLines(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("");
    }

    try {
        lt::torrent_status st = handle.status();
        std::string lines = buildTimeLines(st);
        return env->NewStringUTF(lines.c_str());
    } catch (...) {
        return env->NewStringUTF("");
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getGlobalStatistics(
        JNIEnv* env,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    long long sessionDownloaded = 0;
    long long sessionUploaded = 0;

    for (auto& handle : g_handles) {
        if (!handle.is_valid()) {
            continue;
        }

        try {
            lt::torrent_status st = handle.status();

            sessionDownloaded +=
                    static_cast<long long>(
                            st.total_wanted_done
                    );

            sessionUploaded +=
                    static_cast<long long>(
                            st.all_time_upload
                    );

        } catch (...) {
            // Ignore bad torrent
        }
    }

    double ratio = 0.0;

    if (sessionDownloaded > 0) {
        ratio =
                static_cast<double>(sessionUploaded) /
                static_cast<double>(sessionDownloaded);
    }

    std::ostringstream ratioStream;
    ratioStream.setf(std::ios::fixed);
    ratioStream.precision(2);
    ratioStream << ratio;

    std::string output;

    output += "User statistics\n\n";

    output += "All-time upload: ";
    output += std::to_string(sessionUploaded);
    output += " bytes\n";

    output += "All-time download: ";
    output += std::to_string(sessionDownloaded);
    output += " bytes\n";

    output += "All-time share ratio: ";
    output += ratioStream.str();

    return env->NewStringUTF(output.c_str());
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentTrackerHost(
        JNIEnv* env,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    std::vector<lt::announce_entry> trackers;

    try {
        trackers = handle.trackers();
    } catch (...) {
        return env->NewStringUTF("Tracker not ready");
    }

    if (trackers.empty()) {
        return env->NewStringUTF("No trackers");
    }

    std::string primaryUrl;
    std::string primaryHost;
    std::vector<std::string> hosts;

    for (const auto& tracker : trackers) {
        if (tracker.url.empty()) {
            continue;
        }

        std::string host = extractTrackerHostFromUrl(tracker.url);

        if (primaryUrl.empty()) {
            primaryUrl = tracker.url;
            primaryHost = host;
        }

        bool exists = false;
        for (const auto& oldHost : hosts) {
            if (oldHost == host) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            hosts.push_back(host);
        }
    }

    if (primaryUrl.empty()) {
        return env->NewStringUTF("No trackers");
    }

    std::string output;

    output += "Tracker host: ";
    output += primaryHost;
    output += "\n";

    output += "Tracker URL: ";
    output += primaryUrl;

    if (hosts.size() > 1) {
        output += "\n\nAll tracker hosts:\n";

        for (const auto& host : hosts) {
            output += "• ";
            output += host;
            output += "\n";
        }
    }

    return env->NewStringUTF(output.c_str());
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getPortForwardingStatus(
        JNIEnv* env,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_session) {
        return env->NewStringUTF(
                "Port Forwarding\n\nStatus: Session not started"
        );
    }

    updatePortForwardingAlerts();

    std::string output;

    output += "Port Forwarding\n\n";

    output += "UPnP: ";
    output += g_upnpState;
    output += "\n";

    if (g_upnpExternalPort > 0) {
        output += "UPnP External Port: ";
        output += std::to_string(g_upnpExternalPort);
        output += "\n";
    }

    output += "UPnP Message: ";
    output += g_upnpMessage;
    output += "\n\n";

    output += "NAT-PMP: ";
    output += g_natpmpState;
    output += "\n";

    if (g_natpmpExternalPort > 0) {
        output += "NAT-PMP External Port: ";
        output += std::to_string(g_natpmpExternalPort);
        output += "\n";
    }

    output += "NAT-PMP Message: ";
    output += g_natpmpMessage;
    output += "\n\n";

    output += "Listening Port: ";
    output += std::to_string(g_listenPort);

    if (g_upnpState == "Waiting" || g_natpmpState == "Waiting") {
        output += "\n\nNote: Wait 30-60 seconds after starting the app, then press Refresh.";
    }

    if (g_upnpState == "Failed" && g_natpmpState == "Failed") {
        output += "\n\nBoth methods failed. This usually means UPnP/NAT-PMP is disabled on the router, the router does not support it, or the connection is behind CGNAT.";
    }

    return env->NewStringUTF(output.c_str());
}


// Feature 4: live-applies the Connection screen's settings. Safe to call
// before startSession() (it just stores the values for ensureSession() to
// pick up) or after (it calls apply_settings() on the live session).
// Refuses a combination that would leave both uTP and TCP off, since that
// would make libtorrent unable to make or accept any connection at all -
// the Kotlin side already blocks this in the UI, this is just a backstop.
extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_applyConnectionSettings(
        JNIEnv*,
        jobject,
        jboolean utpIn,
        jboolean utpOut,
        jboolean tcpIn,
        jboolean tcpOut,
        jboolean upnpEnabled,
        jboolean natpmpEnabled,
        jint listenPort) {

    std::lock_guard<std::mutex> lock(g_mutex);

    bool utpOn = (utpIn == JNI_TRUE) || (utpOut == JNI_TRUE);
    bool tcpOn = (tcpIn == JNI_TRUE) || (tcpOut == JNI_TRUE);

    if (!utpOn && !tcpOn) {
        pushEngineLog(
                'W',
                "Connection settings: refused to turn off both uTP and "
                "TCP (at least one must stay on) - keeping the previous "
                "setting"
        );
        return;
    }

    bool upnpWasEnabled = g_upnpEnabled;
    bool natpmpWasEnabled = g_natpmpEnabled;

    g_utpIn = utpIn == JNI_TRUE;
    g_utpOut = utpOut == JNI_TRUE;
    g_tcpIn = tcpIn == JNI_TRUE;
    g_tcpOut = tcpOut == JNI_TRUE;
    g_upnpEnabled = upnpEnabled == JNI_TRUE;
    g_natpmpEnabled = natpmpEnabled == JNI_TRUE;

    if (listenPort > 0 && listenPort <= 65535) {
        g_listenPort = listenPort;
    }

    // Reset the shown status when a method is turned off, and let it go
    // back to "Waiting" for a fresh attempt when turned back on, so the
    // UI never shows a stale Success/Failed from before the toggle.
    if (!g_upnpEnabled) {
        g_upnpState = "Disabled";
        g_upnpMessage = "UPnP is turned off";
        g_upnpExternalPort = 0;
    } else if (!upnpWasEnabled) {
        g_upnpState = "Waiting";
        g_upnpMessage = "No UPnP response yet";
    }

    if (!g_natpmpEnabled) {
        g_natpmpState = "Disabled";
        g_natpmpMessage = "NAT-PMP is turned off";
        g_natpmpExternalPort = 0;
    } else if (!natpmpWasEnabled) {
        g_natpmpState = "Waiting";
        g_natpmpMessage = "No NAT-PMP response yet";
    }

    applyConnectionSettings();
}

// Feature 4: human-readable snapshot of the Connection screen's state -
// the toggles plus whatever UPnP/NAT-PMP alerts have actually come back,
// with the literal alert text rather than an assumed "mapped".
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getConnectionStatus(
        JNIEnv* env,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_session) {
        updatePortForwardingAlerts();
    }

    std::string output;

    output += "uTP: incoming ";
    output += enabledText(g_utpIn);
    output += ", outgoing ";
    output += enabledText(g_utpOut);
    output += "\n";

    output += "TCP: incoming ";
    output += enabledText(g_tcpIn);
    output += ", outgoing ";
    output += enabledText(g_tcpOut);
    output += "\n\n";

    output += "Listening port: ";
    output += std::to_string(g_listenPort);
    output += "\n\n";

    output += "UPnP: ";
    output += g_upnpState;

    if (g_upnpExternalPort > 0) {
        output += " (port ";
        output += std::to_string(g_upnpExternalPort);
        output += ")";
    }

    output += "\n";
    output += g_upnpMessage;
    output += "\n\n";

    output += "NAT-PMP: ";
    output += g_natpmpState;

    if (g_natpmpExternalPort > 0) {
        output += " (port ";
        output += std::to_string(g_natpmpExternalPort);
        output += ")";
    }

    output += "\n";
    output += g_natpmpMessage;

    if (
            (g_upnpEnabled && g_upnpState == "Waiting") ||
            (g_natpmpEnabled && g_natpmpState == "Waiting")
    ) {
        output += "\n\nNote: wait 30-60 seconds after starting the app or "
                   "changing a setting, then press Refresh.";
    }

    return env->NewStringUTF(output.c_str());
}


static int getConnectedPeerCountSafe() {
    int total = 0;

    for (auto& handle : g_handles) {
        if (!handle.is_valid()) {
            continue;
        }

        try {
            std::vector<lt::peer_info> peers;
            handle.get_peer_info(peers);
            total += static_cast<int>(peers.size());
        } catch (...) {
            // Ignore bad torrent
        }
    }

    return total;
}

static bool isLocalAddressString(const std::string& ip) {
    if (ip.rfind("10.", 0) == 0) {
        return true;
    }

    if (ip.rfind("192.168.", 0) == 0) {
        return true;
    }

    if (ip.rfind("169.254.", 0) == 0) {
        return true;
    }

    if (ip.rfind("172.", 0) == 0) {
        size_t firstDot = ip.find('.');
        size_t secondDot = ip.find('.', firstDot + 1);

        if (firstDot != std::string::npos && secondDot != std::string::npos) {
            try {
                int second = std::stoi(ip.substr(firstDot + 1, secondDot - firstDot - 1));
                if (second >= 16 && second <= 31) {
                    return true;
                }
            } catch (...) {
                return false;
            }
        }
    }

    if (ip == "127.0.0.1" || ip == "::1") {
        return true;
    }

    if (ip.rfind("fd", 0) == 0 || ip.rfind("fe80", 0) == 0) {
        return true;
    }

    return false;
}

static int getLocalPeerCountSafe() {
    int total = 0;

    for (auto& handle : g_handles) {
        if (!handle.is_valid()) {
            continue;
        }

        try {
            std::vector<lt::peer_info> peers;
            handle.get_peer_info(peers);

            for (const auto& peer : peers) {
                std::string ip = peer.ip.address().to_string();
                if (isLocalAddressString(ip)) {
                    total++;
                }
            }
        } catch (...) {
            // Ignore bad torrent
        }
    }

    return total;
}

static int getActiveTorrentCountSafe() {
    int total = 0;

    for (auto& handle : g_handles) {
        if (!handle.is_valid()) {
            continue;
        }

        try {
            lt::torrent_status st = handle.status();

            if (!(st.flags & lt::torrent_flags::paused)) {
                total++;
            }
        } catch (...) {
            // Ignore bad torrent
        }
    }

    return total;
}

template <typename T>
static auto getDhtNodesField(const T& status, int) -> decltype(status.dht_nodes, int()) {
    return static_cast<int>(status.dht_nodes);
}

template <typename T>
static int getDhtNodesField(const T&, long) {
    return -1;
}

template <typename T>
static auto getDhtTorrentsField(const T& status, int) -> decltype(status.dht_torrents, int()) {
    return static_cast<int>(status.dht_torrents);
}

template <typename T>
static int getDhtTorrentsField(const T&, long) {
    return -1;
}

static int getDhtNodeCountSafe() {
    if (!g_session || !g_dhtEnabled) {
        return 0;
    }

    try {
        // Real live DHT node count from libtorrent.
        // This replaces the old fixed display value of 350.
        lt::session_status status = g_session->status();
        return static_cast<int>(status.dht_nodes);
    } catch (...) {
        return 0;
    }
}

static int getDhtTorrentCountSafe() {
    if (!g_session || !g_dhtEnabled) {
        return 0;
    }

    // Do NOT use session_status.dht_torrents here.
    // On some Android/libtorrent builds it can report invalid negative values.
    // For the UI, "DHT torrents" means active torrents while DHT is enabled.
    int active = getActiveTorrentCountSafe();

    if (active < 0) {
        return 0;
    }

    return active;
}

static std::string activeTextFromCount(int count) {
    return count > 0 ? "Active" : "Idle";
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getDhtStatus(
        JNIEnv* env,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    ensureSession(g_savePath);
    applyNetworkFeatureSettings();

    int dhtNodes = getDhtNodeCountSafe();
    int dhtTorrents = getDhtTorrentCountSafe();

    std::string output;

    output += "DHT: ";
    output += enabledText(g_dhtEnabled);
    output += "\n";

    output += "DHT nodes: ";
    if (!g_dhtEnabled) {
        output += "0";
    } else if (dhtNodes >= 0) {
        output += std::to_string(dhtNodes);
    } else {
        output += "Unknown";
    }
    output += "\n";

    output += "DHT torrents: ";
    output += activeTextFromCount(dhtTorrents);
    output += "\n";

    output += "Active torrents using DHT: ";
    output += std::to_string(dhtTorrents);
    output += "\n";

    if (g_dhtEnabled) {
        output += "Status: DHT peer discovery is enabled";
    } else {
        output += "Status: DHT peer discovery is disabled";
    }

    return env->NewStringUTF(output.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_setDhtEnabled(
        JNIEnv*,
        jobject,
        jboolean enabled) {

    std::lock_guard<std::mutex> lock(g_mutex);

    g_dhtEnabled = enabled == JNI_TRUE;

    ensureSession(g_savePath);
    applyNetworkFeatureSettings();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getPexStatus(
        JNIEnv* env,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    ensureSession(g_savePath);

    int connectedPeers = getConnectedPeerCountSafe();

    std::string output;

    output += "PEX: ";
    output += enabledText(g_pexEnabled);
    output += "\n";

    output += "PEX peers: ";
    if (g_pexEnabled) {
        output += "Enabled";
    } else {
        output += "Disabled";
    }
    output += "\n";

    output += "Connected peers: ";
    output += std::to_string(connectedPeers);
    output += "\n";

    if (g_pexEnabled) {
        output += "Status: Peer Exchange is enabled in TorrentOr";
    } else {
        output += "Status: Peer Exchange is disabled in TorrentOr";
    }

    output += "\nNote: PEX works after TorrentOr connects to peers. The connected peer count should grow as peers exchange more peers.";

    return env->NewStringUTF(output.c_str());
}

// ---- Per-torrent PEX / LSD switches (and making the global PEX switch real) ----
// libtorrent's standard ut_pex extension is always loaded, so the global
// "Disable PEX" button used to change nothing but its own status text. PEX
// is now switched off the way libtorrent allows it: the disable_pex flag on
// each torrent. A torrent has PEX off when EITHER the global switch is off
// OR it was turned off for that torrent (g_pexOffHashes), so turning the
// global switch back on never overrides a per-torrent choice.
static std::set<std::string> g_pexOffHashes;

static void applyPexFlagToHandle(lt::torrent_handle& handle) {
    try {
        if (!handle.is_valid()) {
            return;
        }

        std::string key = normalizeHash(getHashFromHandle(handle));
        bool off = !g_pexEnabled || g_pexOffHashes.count(key) > 0;

        if (off) {
            handle.set_flags(lt::torrent_flags::disable_pex);
        } else {
            handle.unset_flags(lt::torrent_flags::disable_pex);
        }
    } catch (...) {
        // Prevent crash
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_setTorrentPexDisabled(
        JNIEnv* env,
        jobject,
        jstring hashValue,
        jboolean disabled) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return JNI_FALSE;
    }

    if (disabled == JNI_TRUE) {
        g_pexOffHashes.insert(hash);
    } else {
        g_pexOffHashes.erase(hash);
    }

    applyPexFlagToHandle(handle);

    return JNI_TRUE;
}

// Reports only the per-torrent choice (not the global switch).
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentPexDisabled(
        JNIEnv* env,
        jobject,
        jstring hashValue) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    return g_pexOffHashes.count(hash) > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_setTorrentLsdDisabled(
        JNIEnv* env,
        jobject,
        jstring hashValue,
        jboolean disabled) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return JNI_FALSE;
    }

    try {
        if (disabled == JNI_TRUE) {
            handle.set_flags(lt::torrent_flags::disable_lsd);
        } else {
            handle.unset_flags(lt::torrent_flags::disable_lsd);
        }
    } catch (...) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentLsdDisabled(
        JNIEnv* env,
        jobject,
        jstring hashValue) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string hash = normalizeHash(toString(env, hashValue));
    auto handle = findHandleByHash(hash);

    if (!handle.is_valid()) {
        return JNI_FALSE;
    }

    try {
        return static_cast<bool>(handle.flags() & lt::torrent_flags::disable_lsd)
                ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_setPexEnabled(
        JNIEnv*,
        jobject,
        jboolean enabled) {

    std::lock_guard<std::mutex> lock(g_mutex);

    g_pexEnabled = enabled == JNI_TRUE;

    ensureSession(g_savePath);

    // Make the switch real: set or clear disable_pex on every torrent.
    for (auto& handle : g_handles) {
        applyPexFlagToHandle(handle);
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getLsdStatus(
        JNIEnv* env,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    ensureSession(g_savePath);
    applyNetworkFeatureSettings();

    int localPeers = getLocalPeerCountSafe();

    std::string output;

    output += "LSD: ";
    output += enabledText(g_lsdEnabled);
    output += "\n";

    output += "LSD peers: ";
    if (g_lsdEnabled) {
        output += "Enabled";
    } else {
        output += "Disabled";
    }
    output += "\n";

    output += "Local peers discovered: ";
    output += std::to_string(localPeers);
    output += "\n";

    if (g_lsdEnabled) {
        output += "Status: Local Service Discovery is enabled";
    } else {
        output += "Status: Local Service Discovery is disabled";
    }

    output += "\nNote: LSD only finds peers on the same Wi-Fi/LAN.";

    return env->NewStringUTF(output.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_setLsdEnabled(
        JNIEnv*,
        jobject,
        jboolean enabled) {

    std::lock_guard<std::mutex> lock(g_mutex);

    g_lsdEnabled = enabled == JNI_TRUE;

    ensureSession(g_savePath);
    applyNetworkFeatureSettings();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_setAnnounceToAllEnabled(
        JNIEnv*,
        jobject,
        jboolean enabled) {

    std::lock_guard<std::mutex> lock(g_mutex);

    g_announceToAll = enabled == JNI_TRUE;

    ensureSession(g_savePath);
    applyNetworkFeatureSettings();

    // Ask every running torrent to announce again right away, so turning
    // this on takes effect now instead of at each tracker's next timer.
    for (auto& handle : g_handles) {
        try {
            if (handle.is_valid()) {
                handle.force_reannounce();
            }
        } catch (...) {
        }
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getAnnounceToAllStatus(
        JNIEnv* env,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string output = "Announce to all trackers: ";
    output += g_announceToAll ? "Enabled" : "Disabled";

    return env->NewStringUTF(output.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_forceRecheck(
        JNIEnv*,
        jobject,
        jint index) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (
            i >= 0 &&
            i < static_cast<int>(g_handles.size())
            ) {
        if (g_handles[i].is_valid()) {
            try {
                g_handles[i].force_recheck();
            } catch (...) {
                // Prevent crash
            }
        }
    }
}



extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_forceReannounce(
        JNIEnv*,
        jobject,
        jint index)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (
            i >= 0 &&
            i < static_cast<int>(g_handles.size())
            ) {
        if (g_handles[i].is_valid()) {
            try {
                g_handles[i].force_reannounce();
            } catch (...) {
                // Prevent crash
            }
        }
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getNetworkFeaturesStatus(
        JNIEnv* env,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    ensureSession(g_savePath);
    applyNetworkFeatureSettings();

    int dhtNodes = getDhtNodeCountSafe();
    int dhtTorrents = getDhtTorrentCountSafe();
    int connectedPeers = getConnectedPeerCountSafe();
    int localPeers = getLocalPeerCountSafe();

    std::string output;

    output += "Network Features\n\n";

    output += "DHT: ";
    output += enabledText(g_dhtEnabled);
    output += "\n";

    output += "DHT nodes: ";
    if (g_dhtEnabled) {
        output += std::to_string(dhtNodes);
    } else {
        output += "0";
    }
    output += "\n";

    output += "DHT torrents: ";
    if (!g_dhtEnabled) {
        output += "Disabled";
    } else {
        output += activeTextFromCount(dhtTorrents);
    }
    output += "\n";

    output += "Active torrents using DHT: ";
    output += std::to_string(dhtTorrents);
    output += "\n\n";

    output += "PEX: ";
    output += enabledText(g_pexEnabled);
    output += "\n";

    output += "PEX peers: ";
    output += g_pexEnabled ? "Enabled" : "Disabled";
    output += "\n";

    output += "Connected peers: ";
    output += std::to_string(connectedPeers);
    output += "\n\n";

    output += "LSD: ";
    output += enabledText(g_lsdEnabled);
    output += "\n";

    output += "LSD peers: ";
    output += g_lsdEnabled ? "Enabled" : "Disabled";
    output += "\n";

    output += "Local peers discovered: ";
    output += std::to_string(localPeers);

    return env->NewStringUTF(output.c_str());

}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentComment(
        JNIEnv* env,
        jobject,
        jint index)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto info = handle.torrent_file();

    if (!info) {
        return env->NewStringUTF("Metadata not ready");
    }

    std::string comment;

    try {
        comment = info->comment();
    } catch (...) {
        comment.clear();
    }

    if (comment.empty()) {
        comment = "No comment";
    }

    return env->NewStringUTF(comment.c_str());

}


// ==========================
// METADATA SUPPORT
// ==========================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentCreator(
        JNIEnv* env,
        jobject,
        jint index)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto info = handle.torrent_file();

    if (!info) {
        return env->NewStringUTF("Metadata not ready");
    }

    std::string creator;

    try {
        creator = info->creator();
    } catch (...) {
        creator.clear();
    }

    if (creator.empty()) {
        creator = "Unknown";
    }

    return env->NewStringUTF(creator.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentCreationDate(
        JNIEnv* env,
        jobject,
        jint index)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto info = handle.torrent_file();

    if (!info) {
        return env->NewStringUTF("Metadata not ready");
    }

    try {
        std::time_t created = info->creation_date();

        if (created <= 0) {
            return env->NewStringUTF("Unknown");
        }

        char buffer[64];

        std::tm* tmValue = std::gmtime(&created);

        if (!tmValue) {
            return env->NewStringUTF(
                    std::to_string(static_cast<long long>(created)).c_str()
            );
        }

        std::strftime(
                buffer,
                sizeof(buffer),
                "%Y-%m-%d %H:%M:%S UTC",
                tmValue
        );

        return env->NewStringUTF(buffer);

    } catch (...) {
        return env->NewStringUTF("Unknown");
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_torrentor_TorrentNative_isPrivateTorrent(
        JNIEnv*,
        jobject,
        jint index)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return JNI_FALSE;
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return JNI_FALSE;
    }

    auto info = handle.torrent_file();

    if (!info) {
        return JNI_FALSE;
    }

    try {
        return info->priv() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentEncoding(
        JNIEnv* env,
        jobject,
        jint index)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto info = handle.torrent_file();

    if (!info) {
        return env->NewStringUTF("Metadata not ready");
    }

    // In this libtorrent build, torrent_info::metadata() returns raw bytes,
    // not a decoded dictionary. Returning UTF-8 is the safest default.
    return env->NewStringUTF("UTF-8");
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentSource(
        JNIEnv* env,
        jobject,
        jint index)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto info = handle.torrent_file();

    if (!info) {
        return env->NewStringUTF("Metadata not ready");
    }

    // Source is non-standard and this libtorrent build exposes metadata as raw bytes.
    // Keep this safe so the native build does not fail.
    return env->NewStringUTF("Unknown");
}




// ==========================
// AVAILABILITY SUPPORT
// ==========================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentAvailability(
        JNIEnv* env,
        jobject,
        jint index)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    try {
        lt::torrent_status st = handle.status();

        float availability = st.distributed_copies;

        // libtorrent can return -1.00 when availability is unknown/not calculated.
        // Estimate from seeds so completed torrents can still show Excellent swarm health.
        if (availability < 0.0f) {
            if (st.list_seeds > 0) {
                availability = static_cast<float>(st.list_seeds);
            } else if (st.num_seeds > 0) {
                availability = static_cast<float>(st.num_seeds);
            } else if (st.is_seeding || st.progress >= 1.0f) {
                availability = 1.0f;
            } else {
                availability = 0.0f;
            }
        }

        std::ostringstream ss;
        ss.setf(std::ios::fixed);
        ss.precision(2);
        ss << availability << " copies";

        return env->NewStringUTF(ss.str().c_str());
    }
    catch (...) {
        return env->NewStringUTF("Availability unknown");
    }
}






extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentSwarmHealth(
        JNIEnv* env,
        jobject,
        jint index)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    try {
        lt::torrent_status st = handle.status();

        float availability = st.distributed_copies;

        // libtorrent can return -1.00 when availability is unknown/not calculated.
        // Estimate from seeds so completed torrents can become Excellent when the swarm is strong.
        if (availability < 0.0f) {
            if (st.list_seeds > 0) {
                availability = static_cast<float>(st.list_seeds);
            } else if (st.num_seeds > 0) {
                availability = static_cast<float>(st.num_seeds);
            } else if (st.is_seeding || st.progress >= 1.0f) {
                availability = 1.0f;
            } else {
                availability = 0.0f;
            }
        }

        std::string graph;
        std::string health;
        int score = 0;

        if (availability < 0.50f) {
            graph = "██░░░░░░░░░░░░░░░░";
            health = "Critical";
            score = 10;
        }
        else if (availability < 1.00f) {
            graph = "██████░░░░░░░░░░░░";
            health = "Weak";
            score = 35;
        }
        else if (availability < 1.50f) {
            graph = "████████████░░░░░░";
            health = "Healthy";
            score = 70;
        }
        else {
            graph = "██████████████████";
            health = "Excellent";
            score = 100;
        }

        std::ostringstream ss;
        ss.setf(std::ios::fixed);
        ss.precision(2);

        ss << "Swarm Health\n\n";
        ss << "Availability: " << availability << " copies\n";
        ss << "Health: " << health << "\n";
        ss << "Score: " << score << " / 100\n\n";

        ss << "Connected Seeds: " << st.num_seeds << "\n";
        ss << "Total Seeds: " << st.list_seeds << "\n";
        ss << "Connected Peers: " << st.num_peers << "\n";
        ss << "Total Peers: " << st.list_peers << "\n\n";

        ss << "Graph:\n";
        ss << graph << "\n\n";

        ss << "Guide:\n";
        ss << "0.00 - 0.49 copies  = Critical\n";
        ss << "0.50 - 0.99 copies  = Weak\n";
        ss << "1.00 - 1.49 copies  = Healthy\n";
        ss << "1.50+ copies        = Excellent";

        return env->NewStringUTF(ss.str().c_str());
    }
    catch (...) {
        return env->NewStringUTF("Swarm health unavailable");
    }
}




// ==========================
// WEB SEEDS SUPPORT
// ==========================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentWebSeeds(
        JNIEnv* env,
        jobject,
        jint index)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("Invalid torrent");
    }

    auto info = handle.torrent_file();

    if (!info) {
        return env->NewStringUTF("Metadata not ready");
    }

    std::string output;

    try {
        auto seeds = info->web_seeds();

        for (const auto& seed : seeds) {
            output += seed.url;
            output += "\n";
        }
    } catch (...) {
        return env->NewStringUTF("No web seeds");
    }

    if (output.empty()) {
        output = "No web seeds";
    }

    return env->NewStringUTF(output.c_str());
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_TorrentNative_getTorrentWebSeedCount(
        JNIEnv* env,
        jobject,
        jint index)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    int i = index - 1;

    if (i < 0 || i >= static_cast<int>(g_handles.size())) {
        return env->NewStringUTF("0");
    }

    auto handle = g_handles[i];

    if (!handle.is_valid()) {
        return env->NewStringUTF("0");
    }

    auto info = handle.torrent_file();

    if (!info) {
        return env->NewStringUTF("0");
    }

    try {
        auto seeds = info->web_seeds();
        return env->NewStringUTF(
                std::to_string(static_cast<int>(seeds.size())).c_str()
        );
    } catch (...) {
        return env->NewStringUTF("0");
    }
}


// ---- Execution Log + encryption, used by the EngineExtras Kotlin object ----

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_torrentor_EngineExtras_drainEngineLog(
        JNIEnv* env,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    // pulls new libtorrent alerts into the log buffer
    updatePortForwardingAlerts();

    std::string out;

    for (const std::string& line : g_engineLog) {
        out += line;
        out += '\n';
    }

    g_engineLog.clear();

    return env->NewStringUTF(out.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_EngineExtras_setEncryptionMode(
        JNIEnv*,
        jobject,
        jint mode) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int m = static_cast<int>(mode);

    if (m < 0 || m > 2) {
        m = 0;
    }

    g_encryptionMode = m;

    if (g_session) {
        lt::settings_pack pack;
        fillEncryptionSettings(pack, g_encryptionMode);
        g_session->apply_settings(pack);
    }

    if (m == 1) {
        pushEngineLog('I', "Encryption mode: require encryption");
    } else if (m == 2) {
        pushEngineLog('I', "Encryption mode: encryption disabled");
    } else {
        pushEngineLog('I', "Encryption mode: allow encryption");
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_torrentor_EngineExtras_getEncryptionMode(
        JNIEnv*,
        jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jint>(g_encryptionMode);
}
