#include "profile_model.h"
#include <chrono>
#include <iomanip>
#include <random>
#include <sstream>

namespace vs {

std::string makeId(const char* prefix) {
    static std::mt19937_64 rng(
        static_cast<uint64_t>(std::chrono::high_resolution_clock::now().time_since_epoch().count()));
    std::ostringstream out;
    out << prefix << "_" << std::hex << std::setw(12) << std::setfill('0') << (rng() & 0xFFFFFFFFFFFFULL);
    return out.str();
}

std::shared_ptr<Element> cloneElement(const std::shared_ptr<Element>& src) {
    if (!src) return nullptr;
    auto e = std::make_shared<Element>(*src);
    e->children.clear();
    for (const auto& c : src->children) e->children.push_back(cloneElement(c));
    return e;
}

ProfileDocument cloneProfile(const ProfileDocument& src) {
    ProfileDocument out = src;
    out.pages.clear();
    for (const auto& page : src.pages) {
        Page p = page;
        p.root = cloneElement(page.root);
        out.pages.push_back(std::move(p));
    }
    return out;
}

ProfileDocument makeDefaultProfile() {
    ProfileDocument doc;
    doc.profileId = makeId("profile");
    doc.profileName = "My Profile";
    doc.defaultPageId = "home";

    Page page;
    page.id = "home";
    page.name = "Home";
    page.root = std::make_shared<Element>();
    page.root->type = ElementType::Block;
    page.root->id = makeId("root");
    page.root->name = "Home Page";
    page.root->rect = {0, 0, 1, 1, 0, true};
    page.root->background.kind = BackgroundKind::LinearGradient;
    page.root->background.startX = 0.0f;
    page.root->background.startY = 0.0f;
    page.root->background.endX = 1.0f;
    page.root->background.endY = 1.0f;
    page.root->background.stops = {{0.0f, 0xFFF7F4FF}, {0.55f, 0xFFEAF4FF}, {1.0f, 0xFFFDF2F8}};

    auto star1 = std::make_shared<Element>();
    star1->type = ElementType::Stamp;
    star1->id = makeId("stamp");
    star1->name = "Star";
    star1->rect = {0.05f, 0.05f, 0.08f, 0.08f, 0, true};
    star1->stampDecoration.builtinName = "Star1";
    star1->stampDecoration.basedOnBuiltin = "Star1";
    star1->rotationDegrees = -12.0f;
    star1->opacity = 0.75f;

    auto star2 = cloneElement(star1);
    star2->id = makeId("stamp");
    star2->name = "Star 2";
    star2->rect = {0.84f, 0.13f, 0.055f, 0.055f, 1, true};
    star2->rotationDegrees = 23.0f;

    auto header = std::make_shared<Element>();
    header->type = ElementType::Block;
    header->id = makeId("block");
    header->name = "Header Box";
    header->rect = {0.07f, 0.055f, 0.86f, 0.13f, 2, true};
    header->background.kind = BackgroundKind::Solid;
    header->background.solidArgb = 0x00FFFFFF;
    header->border.builtinName = "None";
    header->border.basedOnBuiltin = "None";

    auto title = std::make_shared<Element>();
    title->type = ElementType::Text;
    title->id = makeId("text");
    title->name = "Profile Title";
    title->rect = {0.02f, 0.12f, 0.96f, 0.70f, 1, true};
    title->text = "My VeilySocial Page";
    title->fontSize = 30.0f;
    title->bold = true;
    title->textAlign = TextAlign::Center;
    header->children.push_back(title);

    auto box = std::make_shared<Element>();
    box->type = ElementType::Block;
    box->id = makeId("block");
    box->name = "Welcome Box";
    box->rect = {0.10f, 0.22f, 0.80f, 0.58f, 3, true};
    box->background.kind = BackgroundKind::Solid;
    box->background.solidArgb = 0xFFFFFFFF;
    box->border.builtinName = "Thin1";
    box->border.basedOnBuiltin = "Thin1";
    box->borderThickness = 2.0f;

    auto body = std::make_shared<Element>();
    body->type = ElementType::Text;
    body->id = makeId("text");
    body->name = "Welcome Text";
    body->rect = {0.07f, 0.08f, 0.86f, 0.34f, 1, true};
    body->text = "Drag, resize and decorate this page. The profile document stays non-executable; compiled widgets run only inside their own sandboxed rectangles.";
    body->fontSize = 17.0f;
    box->children.push_back(body);

    auto widget = std::make_shared<Element>();
    widget->type = ElementType::Widget;
    widget->id = makeId("widget");
    widget->name = "Widget Placeholder";
    widget->rect = {0.18f, 0.52f, 0.64f, 0.35f, 2, true};
    widget->widgetLabel = "Future Widget";
    box->children.push_back(widget);

    page.root->children = {star1, star2, header, box};
    doc.pages.push_back(page);
    return doc;
}

} // namespace vs
