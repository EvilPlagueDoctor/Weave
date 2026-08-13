#pragma once
#include "profile_model.h"
#include <cstdint>
#include <string>
#include <vector>

namespace vs {

struct ValidationResult {
    bool ok = true;
    std::string message;
};

ValidationResult validateProfile(const ProfileDocument& doc);
std::vector<uint8_t> encodeProfileBinary(const ProfileDocument& doc);
ProfileDocument decodeProfileBinary(const std::vector<uint8_t>& bytes);
std::string encodeProfileText(const ProfileDocument& doc);
ProfileDocument decodeProfileText(const std::string& text);
void saveProfileTextFile(const ProfileDocument& doc, const std::string& path);
ProfileDocument loadProfileTextFile(const std::string& path);
std::string base64Encode(const std::vector<uint8_t>& data);
std::vector<uint8_t> base64Decode(const std::string& text);

} // namespace vs
