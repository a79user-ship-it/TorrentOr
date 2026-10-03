#include <jni.h>
#include <string>
#include <memory>
#include <vector>
#include <mutex>
#include <deque>
#include <sstream>
#include <algorithm>
#include <cctype>
#include <cstdio>
#include <ctime>
#include <android/log.h>

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

// Feature 1: Speed Control (global limits only - no scheduling). Bytes
// per second, matching libtorrent's own convention where 0 means
// unlimited. Defaults to unlimited, matching the app's behavior before
// this feature existed.
static int g_globalUploadLimit = 0;
static int g_globalDownloadLimit = 0;

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

        if (!g_upnpEnabled) {
            g_upnpState = "Disabled";
            g_upnpMessage = "UPnP is turned off";
        }

        if (!g_natpmpEnabled) {
            g_natpmpState = "Disabled";
            g_natpmpMessage = "NAT-PMP is turned off";
        }

        g_session = std::make_unique<lt::session>(pack);

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

    long mins = seconds / 60;
    long hrs = mins / 60;
    long days = hrs / 24;

    if (days > 0) {
        return pluralUnit(days, "day", "days") + " " +
               pluralUnit(hrs % 24, "hour", "hours");
    }

    if (hrs > 0) {
        return pluralUnit(hrs, "hour", "hours") + " " +
               pluralUnit(mins % 60, "minute", "minutes");
    }

    if (mins > 0) {
        return pluralUnit(mins, "minute", "minutes") + " " +
               pluralUnit(seconds % 60, "second", "seconds");
    }

    return pluralUnit(seconds, "second", "seconds");
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

    auto handle = g_session->add_torrent(params, ec);

    if (!ec && handle.is_valid()) {
        if (g_pausedAll) {
            handle.unset_flags(lt::torrent_flags::auto_managed);
            handle.pause();
        }

        g_handles.push_back(handle);
        g_pausedHandles.push_back(g_pausedAll);
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

    auto handle = g_session->add_torrent(params, ec);

    if (!ec && handle.is_valid()) {
        if (g_pausedAll) {
            handle.unset_flags(lt::torrent_flags::auto_managed);
            handle.pause();
        }

        g_handles.push_back(handle);
        g_pausedHandles.push_back(g_pausedAll);
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

    auto handle = g_session->add_torrent(params, ec);

    if (!ec && handle.is_valid()) {
        if (g_pausedAll) {
            handle.unset_flags(lt::torrent_flags::auto_managed);
            handle.pause();
        }

        g_handles.push_back(handle);
        g_pausedHandles.push_back(g_pausedAll);
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
        } else if (st.is_seeding) {
            state = "Seeding";
        } else {
            state = "Downloading";
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

        if (trackers.empty()) {
            return env->NewStringUTF("No trackers");
        }

        output += "Tracker Status\n\n";

        for (const auto& tracker : trackers) {
            output += "URL: ";
            output += tracker.url;

            output += "\nTier: ";
            output += std::to_string(tracker.tier);

            output += "\nFails: ";
            output += std::to_string(tracker.fails);

            output += "\nVerified: ";
            output += tracker.verified ? "Yes" : "No";

            output += "\nStatus: ";
            if (tracker.verified) {
                output += "Working";
            } else if (tracker.fails > 0) {
                output += "Error / Failed";
            } else {
                output += "Not contacted yet";
            }

            output += "\nMessage: ";
            if (tracker.verified) {
                output += "Announce OK";
            } else if (tracker.fails > 0) {
                output += "Tracker failed or did not respond";
            } else {
                output += "No tracker message yet";
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

        output += "\nProgress: ";
        output += std::to_string(progressPercent);
        output += "%";

        output += "\n↓ ";
        output += std::to_string(peer.down_speed / 1024);
        output += " KB/s";

        output += "  ↑ ";
        output += std::to_string(peer.up_speed / 1024);
        output += " KB/s";

        output += "\n\n";
    }

    if (output.empty()) {
        output = "No peers";
    }

    return env->NewStringUTF(output.c_str());
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
    output += std::to_string(pieceSize);
    output += " bytes\n";

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
    output += std::to_string(pieceSize);
    output += " bytes\n\n";

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

    // formatEta() already returns a string prefixed with "ETA: ", so don't
    // prepend it again here (that was producing "ETA: ETA: --" on screen).
    output += formatEta(eta);
    output += "\n";

    output += "Time active: ";
    output += formatDuration(static_cast<long>(st.active_duration.count()));

    return env->NewStringUTF(output.c_str());
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

extern "C"
JNIEXPORT void JNICALL
Java_com_example_torrentor_TorrentNative_setPexEnabled(
        JNIEnv*,
        jobject,
        jboolean enabled) {

    std::lock_guard<std::mutex> lock(g_mutex);

    g_pexEnabled = enabled == JNI_TRUE;

    ensureSession(g_savePath);
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
