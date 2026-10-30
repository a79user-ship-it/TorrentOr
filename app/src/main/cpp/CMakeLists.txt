cmake_minimum_required(VERSION 3.10)

project(torrentor)

add_library(torrentor SHARED torrentor.cpp)

# The original project used machine-specific Windows paths. Keep the
# include directories configurable so GitHub Actions and local builds work.
set(LIBTORRENT_INCLUDE_DIR "$ENV{LIBTORRENT_INCLUDE_DIR}")
set(BOOST_INCLUDE_DIR "$ENV{BOOST_INCLUDE_DIR}")

if(NOT LIBTORRENT_INCLUDE_DIR)
    set(LIBTORRENT_INCLUDE_DIR "/usr/include")
endif()

if(NOT BOOST_INCLUDE_DIR)
    set(BOOST_INCLUDE_DIR "/usr/include")
endif()

target_include_directories(torrentor PRIVATE
        ${LIBTORRENT_INCLUDE_DIR}
        ${BOOST_INCLUDE_DIR}
)

# Import the Android libtorrent binary shipped with the project.
add_library(libtorrent SHARED IMPORTED)

set_target_properties(libtorrent PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtorrent-rasterbar.so
)

find_library(log-lib log)

target_link_libraries(
        torrentor
        libtorrent
        ${log-lib}
)
