cmake_minimum_required(VERSION 3.16)
project(MaqamCore LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(maqam_core STATIC
    src/fft.cpp
    src/levels.cpp
    src/tuning.cpp
    src/maqam.cpp
    src/pitch_detector.cpp
    src/notes.cpp
    capi/maqam_core.cpp)
target_include_directories(maqam_core PUBLIC include capi)
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(maqam_core PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

enable_testing()
add_executable(maqam_core_tests
    tests/test_main.cpp
    tests/test_levels.cpp
    tests/test_tuning.cpp
    tests/test_maqam.cpp
    tests/test_pitch.cpp
    tests/test_notes.cpp
    tests/test_capi.cpp)
target_link_libraries(maqam_core_tests PRIVATE maqam_core)
add_test(NAME maqam_core_tests COMMAND maqam_core_tests)
