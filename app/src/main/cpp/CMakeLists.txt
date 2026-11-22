cmake_minimum_required(VERSION 3.10)

project(torrentor)

add_library(torrentor SHARED torrentor.cpp)

# Tell C++ where libtorrent headers are
target_include_directories(torrentor PRIVATE
        C:/Users/Administrator/Downloads/libtorrent/include
        C:/Users/Administrator/Downloads/boost_1_91_0/boost_1_91_0
)

# Import compiled libtorrent .so
add_library(libtorrent SHARED IMPORTED)

set_target_properties(libtorrent PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtorrent-rasterbar.so
)

find_library(log-lib log)

# Optional OpenSSL (HTTPS trackers). Only enabled when libssl.so and libcrypto.so
# are present in jniLibs/<abi>, i.e. libtorrent was rebuilt with OpenSSL for that ABI.
set(OSSL_SSL    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libssl.so)
set(OSSL_CRYPTO ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libcrypto.so)

if(EXISTS ${OSSL_SSL} AND EXISTS ${OSSL_CRYPTO})
    add_library(ossl_ssl SHARED IMPORTED)
    set_target_properties(ossl_ssl PROPERTIES IMPORTED_LOCATION ${OSSL_SSL})
    add_library(ossl_crypto SHARED IMPORTED)
    set_target_properties(ossl_crypto PROPERTIES IMPORTED_LOCATION ${OSSL_CRYPTO})

    # These must match how libtorrent itself was compiled.
    target_compile_definitions(torrentor PRIVATE
            TORRENT_USE_OPENSSL
            TORRENT_USE_LIBCRYPTO
            TORRENT_SSL_PEERS
            OPENSSL_NO_SSL2
    )
    target_include_directories(torrentor PRIVATE
            C:/Users/Administrator/Downloads/openssl-android/include
    )
    target_link_libraries(torrentor ossl_ssl ossl_crypto)
endif()

target_link_libraries(
        torrentor
        libtorrent
        ${log-lib}
)
