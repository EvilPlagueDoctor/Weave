#pragma once
#include <algorithm>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace vs {

constexpr uint16_t FORMAT_VERSION = 3;
constexpr size_t MAX_PAGES = 64;
constexpr size_t MAX_DEPTH = 16;
constexpr size_t MAX_ELEMENTS_PER_PAGE = 2048;
constexpr size_t MAX_STRING_BYTES = 16 * 1024;
constexpr size_t MAX_GRADIENT_STOPS = 8;
constexpr uint32_t MAX_DECORATION_DIMENSION = 256;
constexpr uint32_t MAX_DECORATION_ITEM_BYTES = 64 * 1024;
constexpr uint32_t MAX_DECORATION_PACK_BYTES = 1024 * 1024;

enum class BackgroundKind : uint8_t { Solid = 0, LinearGradient = 1 };
enum class DecorationKind : uint8_t { Builtin = 0, DecorationPack = 1 };
enum class ElementType : uint8_t { Block = 1, Text = 2, Link = 3, Button = 4, Stamp = 5, Media = 6, Widget = 7 };
enum class LayoutMode : uint8_t { Freeform = 0, Column = 1, Row = 2 };
enum class StickyMode : uint8_t { Normal = 0, StickyParent = 1, FloatViewport = 2 };
enum class LinkTargetType : uint8_t { Page = 0, Profile = 1, Post = 2, Community = 3, Dht = 4, External = 5 };
enum class MediaKind : uint8_t { Image = 0, Audio = 1, Video = 2 };
enum class TextAlign : uint8_t { Left = 0, Center = 1, Right = 2 };

struct GradientStop {
    float position = 0.0f;
    uint32_t argb = 0xFF000000;
};

struct BackgroundSpec {
    BackgroundKind kind = BackgroundKind::Solid;
    uint32_t solidArgb = 0xFFF7F7F7;
    float startX = 0.0f;
    float startY = 0.0f;
    float endX = 1.0f;
    float endY = 1.0f;
    std::vector<GradientStop> stops { {0.0f, 0xFFF7F7F7}, {1.0f, 0xFFE5E7EB} };
};

struct DecorationRef {
    DecorationKind kind = DecorationKind::Builtin;
    std::string builtinName = "Thin1";
    std::string packRecordKey;
    uint32_t itemId = 0;
    std::string contentHash;
    std::string basedOnBuiltin = "Thin1";
};

struct RectSpec {
    float x = 0.05f;
    float y = 0.05f;
    float width = 0.30f;
    float height = 0.12f;
    int32_t zIndex = 0;
    bool visible = true;
};

struct Element {
    ElementType type = ElementType::Block;
    std::string id;
    std::string name;
    RectSpec rect;

    // Block
    LayoutMode layout = LayoutMode::Freeform;
    BackgroundSpec background;
    DecorationRef border;
    float borderThickness = 1.0f;
    bool clipChildren = true;
    bool scrollChildren = false;
    StickyMode sticky = StickyMode::Normal;
    std::vector<std::shared_ptr<Element>> children;

    // Text
    std::string text = "Text";
    std::string fontId = "Default1";
    float fontSize = 18.0f;
    uint32_t textArgb = 0xFF111827;
    TextAlign textAlign = TextAlign::Left;
    bool bold = false;
    bool italic = false;
    bool underline = false;

    // Link/Button
    std::string label = "Link";
    LinkTargetType targetType = LinkTargetType::Page;
    std::string target;
    DecorationRef buttonDecoration;

    // Stamp
    DecorationRef stampDecoration;
    float rotationDegrees = 0.0f;
    float opacity = 1.0f;
    bool flipX = false;
    bool flipY = false;

    // Media
    MediaKind mediaKind = MediaKind::Image;
    std::string mediaRecordKey;
    std::string mediaContentHash;
    uint32_t intrinsicWidth = 640;
    uint32_t intrinsicHeight = 480;
    std::string mediaTitle = "Image";
    std::string mediaDescription = "Media placeholder";

    // Widget
    std::string widgetLabel = "Widget";
    std::string widgetRecordKey;
    uint32_t widgetItemId = 0;
    std::string widgetSourceHash;
    uint32_t widgetDefaultWidth = 320;
    uint32_t widgetDefaultHeight = 180;
    bool widgetWarnOnResize = true;
};

struct Page {
    std::string id;
    std::string name;
    // Width / height. 0.60 is the default tall/narrow page; smaller values create longer pages.
    float aspectRatio = 0.60f;
    std::shared_ptr<Element> root;
};

struct ProfileDocument {
    std::string profileId;
    std::string profileName;
    std::string defaultPageId;
    std::vector<Page> pages;
};

inline float clamp01(float v) { return std::max(0.0f, std::min(1.0f, v)); }

std::string makeId(const char* prefix);
ProfileDocument makeDefaultProfile();
std::shared_ptr<Element> cloneElement(const std::shared_ptr<Element>& src);
ProfileDocument cloneProfile(const ProfileDocument& src);

} // namespace vs
