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

target_link_libraries(
        torrentor
        libtorrent
        ${log-lib}
)