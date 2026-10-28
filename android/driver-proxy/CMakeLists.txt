cmake_minimum_required(VERSION 3.22)
project(muguet_driver_proxy LANGUAGES C CXX)
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "Driver proxy requires Android arm64-v8a")
endif()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_link_options("-Wl,-z,max-page-size=16384")
include(FetchContent)
FetchContent_Declare(adrenotools
  GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
  GIT_TAG 8fae8ce254dfc1344527e05301e43f37dea2df80
  GIT_SHALLOW FALSE
  GIT_SUBMODULES_RECURSE TRUE
)
FetchContent_MakeAvailable(adrenotools)
add_library(vulkan SHARED driver_proxy.cpp)
target_compile_definitions(vulkan PRIVATE VK_NO_PROTOTYPES)
target_compile_options(vulkan PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(vulkan PRIVATE adrenotools log dl)
target_link_options(vulkan PRIVATE
  "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
  "-Wl,-Bsymbolic-functions"
  "-Wl,--exclude-libs,ALL")
set_target_properties(vulkan PROPERTIES LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
