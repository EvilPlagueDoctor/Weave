cmake_minimum_required(VERSION 3.20)
project(VeilySocialProfileDesigner LANGUAGES CXX RC)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_executable(VeilySocialProfileDesigner WIN32
    src/main.cpp
    src/profile_model.cpp
    src/profile_codec.cpp
    src/social_network.cpp
    src/resources.rc
)
target_compile_definitions(VeilySocialProfileDesigner PRIVATE UNICODE _UNICODE NOMINMAX)
target_link_libraries(VeilySocialProfileDesigner PRIVATE comctl32 comdlg32 shell32 gdi32 user32 ole32 msimg32 d3d9 uxtheme)

add_executable(vspf_codec_test
    src/codec_test.cpp
    src/profile_model.cpp
    src/profile_codec.cpp
)

if(MSVC)
    target_compile_options(VeilySocialProfileDesigner PRIVATE /utf-8)
    target_compile_options(vspf_codec_test PRIVATE /utf-8)
endif()
