#pragma once
#include <windows.h>
#include <string>

namespace vs_social {

using GetProfileTextFn = std::string (*)();
using GetProfileNameFn = std::string (*)();
using OpenRemoteProfileFn = void (*)(const std::string& mainDht, const std::string& name, const std::string& profileText);

struct HostCallbacks {
    GetProfileTextFn getOwnProfileText = nullptr;
    GetProfileNameFn getOwnProfileName = nullptr;
    OpenRemoteProfileFn openRemoteProfile = nullptr;
};

bool initialize(HWND owner, HostCallbacks callbacks);
void show();
void shutdown();

} // namespace vs_social
