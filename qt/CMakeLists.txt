cmake_minimum_required(VERSION 3.16)
project(cove_shell LANGUAGES CXX)

option(COVE_BUILD_TESTS "Build Cove Qt shell tests" OFF)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

# Quick/Qml for the scene, WebEngineQuick for the QML WebEngineView, OpenGL for
# the GL context plumbing mpv needs, WebChannel for the qwebchannel.js resource.
# Note: WebChannel (not WebChannelQuick) is sufficient — the bridge JS is
# injected via QWebEngineScript; no QML-side WebChannel types are used.
find_package(Qt6 REQUIRED COMPONENTS
  Core Gui Qml Quick WebEngineQuick WebEngineCore WebChannel OpenGL Network
)

# libmpv — pkg-config on Linux/macOS; manual SDK path on Windows.
# On Windows, MPV_SDK must point to the extracted shinchiro mpv-dev directory
# (CI generates mpv.lib from the DLL via dumpbin+lib.exe before this runs).
if(WIN32)
    find_path(MPV_INCLUDE_DIR mpv/client.h
        HINTS "$ENV{MPV_SDK}/include" "$ENV{MPV_SDK}"
        REQUIRED)
    find_library(MPV_LIBRARY NAMES mpv libmpv
        HINTS "$ENV{MPV_SDK}" "$ENV{MPV_SDK}/lib"
        REQUIRED)
    set(MPV_LINK_TARGET "${MPV_LIBRARY}")
    set(MPV_INCLUDE_TARGET "${MPV_INCLUDE_DIR}")
else()
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(MPV REQUIRED IMPORTED_TARGET mpv)
    set(MPV_LINK_TARGET PkgConfig::MPV)
endif()

set(APP_SOURCES
  src/main.cpp
  src/BackendProbe.cpp
  src/BackendProbe.h
  src/MpvObject.cpp
  src/MpvObject.h
  src/MpvHelpers.cpp
  src/MpvHelpers.h
  src/RestartPolicy.cpp
  src/RestartPolicy.h
  src/GpuWorkaround.cpp
  src/GpuWorkaround.h
  src/LinuxGraphicsEnvironment.cpp
  src/LinuxGraphicsEnvironment.h
  src/StaticServer.cpp
  src/StaticServer.h
  resources.qrc
)
# resources/cove.rc embeds resources/cove.ico into cove_shell.exe (taskbar,
# Explorer, shortcut icon). Only Windows toolchains can compile a .rc file.
if(WIN32)
    list(APPEND APP_SOURCES resources/cove.rc)
endif()

# WIN32 suppresses the console window on Windows; no effect on other platforms.
add_executable(cove_shell WIN32 ${APP_SOURCES})

if(WIN32)
    target_include_directories(cove_shell PRIVATE "${MPV_INCLUDE_TARGET}")
endif()

target_link_libraries(cove_shell PRIVATE
  Qt6::Core
  Qt6::Gui
  Qt6::Qml
  Qt6::Quick
  Qt6::WebEngineQuick
  Qt6::WebEngineCore
  Qt6::WebChannel
  Qt6::OpenGL
  Qt6::Network
  ${MPV_LINK_TARGET}
)

if(COVE_BUILD_TESTS)
    enable_testing()
    find_package(Qt6 REQUIRED COMPONENTS Test)

    add_executable(cove_shell_environment_tests
        tests/LinuxGraphicsEnvironmentTest.cpp
        src/LinuxGraphicsEnvironment.cpp
        src/LinuxGraphicsEnvironment.h
    )
    target_include_directories(cove_shell_environment_tests PRIVATE src)
    target_link_libraries(cove_shell_environment_tests PRIVATE
        Qt6::Core
        Qt6::Test
    )
    add_test(NAME cove-shell-environment
             COMMAND cove_shell_environment_tests)

    add_executable(cove_shell_gpu_workaround_tests
        tests/GpuWorkaroundTest.cpp
        src/GpuWorkaround.cpp
        src/GpuWorkaround.h
    )
    target_include_directories(cove_shell_gpu_workaround_tests PRIVATE src)
    target_link_libraries(cove_shell_gpu_workaround_tests PRIVATE
        Qt6::Core
        Qt6::Test
    )
    add_test(NAME cove-shell-gpu-workaround
             COMMAND cove_shell_gpu_workaround_tests)

    add_executable(cove_shell_static_server_tests
        tests/StaticServerTest.cpp
        src/StaticServer.cpp
        src/StaticServer.h
    )
    target_include_directories(cove_shell_static_server_tests PRIVATE src)
    target_link_libraries(cove_shell_static_server_tests PRIVATE
        Qt6::Core
        Qt6::Network
        Qt6::Test
    )
    add_test(NAME cove-shell-static-server
             COMMAND cove_shell_static_server_tests)
    set_tests_properties(cove-shell-static-server PROPERTIES TIMEOUT 10)

    add_executable(cove_shell_backend_probe_tests
        tests/BackendProbeTest.cpp
        src/BackendProbe.cpp
        src/BackendProbe.h
    )
    target_include_directories(cove_shell_backend_probe_tests PRIVATE src)
    target_link_libraries(cove_shell_backend_probe_tests PRIVATE
        Qt6::Core
        Qt6::Network
        Qt6::Test
    )
    add_test(NAME cove-shell-backend-probe
             COMMAND cove_shell_backend_probe_tests)
    set_tests_properties(cove-shell-backend-probe PROPERTIES TIMEOUT 10)

    add_executable(cove_shell_restart_policy_tests
        tests/RestartPolicyTest.cpp
        src/RestartPolicy.cpp
        src/RestartPolicy.h
    )
    target_include_directories(cove_shell_restart_policy_tests PRIVATE src)
    target_link_libraries(cove_shell_restart_policy_tests PRIVATE
        Qt6::Core
        Qt6::Test
    )
    add_test(NAME cove-shell-restart-policy
             COMMAND cove_shell_restart_policy_tests)

    add_executable(cove_shell_mpv_helpers_tests
        tests/MpvHelpersTest.cpp
        src/MpvHelpers.cpp
        src/MpvHelpers.h
    )
    target_include_directories(cove_shell_mpv_helpers_tests PRIVATE
        src
        "${MPV_INCLUDE_TARGET}"
    )
    target_link_libraries(cove_shell_mpv_helpers_tests PRIVATE
        Qt6::Core
        Qt6::Test
    )
    add_test(NAME cove-shell-mpv-helpers
             COMMAND cove_shell_mpv_helpers_tests)
endif()
