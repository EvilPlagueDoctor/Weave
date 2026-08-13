#include "profile_codec.h"
#include <cstring>
#include <fstream>
#include <sstream>
#include <stdexcept>

namespace vs {
namespace {

class Writer {
public:
    std::vector<uint8_t> data;
    void u8(uint8_t v) { data.push_back(v); }
    void b(bool v) { u8(v ? 1 : 0); }
    void u16(uint16_t v) { u8(static_cast<uint8_t>(v)); u8(static_cast<uint8_t>(v >> 8)); }
    void u32(uint32_t v) { for (int i=0;i<4;i++) u8(static_cast<uint8_t>(v >> (8*i))); }
    void i32(int32_t v) { u32(static_cast<uint32_t>(v)); }
    void f32(float v) { uint32_t n; std::memcpy(&n, &v, 4); u32(n); }
    void str(const std::string& s) {
        if (s.size() > MAX_STRING_BYTES) throw std::runtime_error("string exceeds VSPF limit");
        u32(static_cast<uint32_t>(s.size()));
        data.insert(data.end(), s.begin(), s.end());
    }
};

class Reader {
public:
    const std::vector<uint8_t>& data;
    size_t pos = 0;
    explicit Reader(const std::vector<uint8_t>& d): data(d) {}
    void need(size_t n) { if (pos + n > data.size()) throw std::runtime_error("truncated VSPF payload"); }
    uint8_t u8(){ need(1); return data[pos++]; }
    bool b(){ auto v=u8(); if(v>1) throw std::runtime_error("invalid bool"); return v!=0; }
    uint16_t u16(){ uint16_t v=u8(); v|=static_cast<uint16_t>(u8())<<8; return v; }
    uint32_t u32(){ uint32_t v=0; for(int i=0;i<4;i++) v|=static_cast<uint32_t>(u8())<<(8*i); return v; }
    int32_t i32(){ return static_cast<int32_t>(u32()); }
    float f32(){ uint32_t n=u32(); float v; std::memcpy(&v,&n,4); return v; }
    std::string str(){ uint32_t n=u32(); if(n>MAX_STRING_BYTES) throw std::runtime_error("string exceeds VSPF limit"); need(n); std::string s(reinterpret_cast<const char*>(data.data()+pos),n); pos+=n; return s; }
};

void writeDecoration(Writer& w, const DecorationRef& d){
    w.u8(static_cast<uint8_t>(d.kind)); w.str(d.builtinName); w.str(d.packRecordKey); w.u32(d.itemId); w.str(d.contentHash); w.str(d.basedOnBuiltin);
}
DecorationRef readDecoration(Reader& r){
    DecorationRef d; d.kind=static_cast<DecorationKind>(r.u8()); d.builtinName=r.str(); d.packRecordKey=r.str(); d.itemId=r.u32(); d.contentHash=r.str(); d.basedOnBuiltin=r.str(); return d;
}
void writeBackground(Writer& w, const BackgroundSpec& bg){
    w.u8(static_cast<uint8_t>(bg.kind)); w.u32(bg.solidArgb); w.f32(bg.startX); w.f32(bg.startY); w.f32(bg.endX); w.f32(bg.endY);
    if(bg.stops.size()>MAX_GRADIENT_STOPS) throw std::runtime_error("too many gradient stops");
    w.u8(static_cast<uint8_t>(bg.stops.size()));
    for(const auto& s:bg.stops){ w.f32(s.position); w.u32(s.argb); }
}
BackgroundSpec readBackground(Reader& r){
    BackgroundSpec bg; bg.kind=static_cast<BackgroundKind>(r.u8()); bg.solidArgb=r.u32(); bg.startX=r.f32(); bg.startY=r.f32(); bg.endX=r.f32(); bg.endY=r.f32();
    auto n=r.u8(); if(n>MAX_GRADIENT_STOPS) throw std::runtime_error("too many gradient stops"); bg.stops.clear(); for(uint8_t i=0;i<n;i++) bg.stops.push_back({r.f32(),r.u32()}); return bg;
}
void writeRect(Writer& w,const RectSpec& x){ w.f32(x.x);w.f32(x.y);w.f32(x.width);w.f32(x.height);w.i32(x.zIndex);w.b(x.visible); }
RectSpec readRect(Reader& r){ RectSpec x; x.x=r.f32();x.y=r.f32();x.width=r.f32();x.height=r.f32();x.zIndex=r.i32();x.visible=r.b();return x; }

void writeElement(Writer& w, const std::shared_ptr<Element>& e, size_t depth){
    if(!e) throw std::runtime_error("null element");
    if(depth>MAX_DEPTH) throw std::runtime_error("element nesting too deep");
    w.u8(static_cast<uint8_t>(e->type)); w.str(e->id); w.str(e->name); writeRect(w,e->rect);
    switch(e->type){
        case ElementType::Block:
            w.u8(static_cast<uint8_t>(e->layout)); writeBackground(w,e->background); writeDecoration(w,e->border); w.f32(e->borderThickness); w.b(e->clipChildren); w.b(e->scrollChildren); w.u8(static_cast<uint8_t>(e->sticky));
            if(e->children.size()>UINT16_MAX) throw std::runtime_error("too many children");
            w.u16(static_cast<uint16_t>(e->children.size()));
            for(auto& c:e->children) writeElement(w,c,depth+1);
            break;
        case ElementType::Text:
            w.str(e->text); w.str(e->fontId); w.f32(e->fontSize); w.u32(e->textArgb); w.u8(static_cast<uint8_t>(e->textAlign)); w.b(e->bold); w.b(e->italic); w.b(e->underline); break;
        case ElementType::Link:
            w.str(e->label); w.u8(static_cast<uint8_t>(e->targetType)); w.str(e->target); break;
        case ElementType::Button:
            w.str(e->label); writeDecoration(w,e->buttonDecoration); writeBackground(w,e->background); w.str(e->fontId); w.f32(e->fontSize); w.u32(e->textArgb); w.u8(static_cast<uint8_t>(e->textAlign)); w.b(e->bold); w.b(e->italic); w.b(e->underline); w.u8(static_cast<uint8_t>(e->targetType)); w.str(e->target); break;
        case ElementType::Stamp:
            writeDecoration(w,e->stampDecoration); w.f32(e->rotationDegrees); w.f32(e->opacity); w.b(e->flipX); w.b(e->flipY); break;
        case ElementType::Media:
            w.u8(static_cast<uint8_t>(e->mediaKind)); w.str(e->mediaRecordKey); w.str(e->mediaContentHash); w.u32(e->intrinsicWidth); w.u32(e->intrinsicHeight); w.str(e->mediaTitle); w.str(e->mediaDescription); break;
        case ElementType::Widget:
            w.str(e->widgetLabel); w.str(e->widgetRecordKey); w.u32(e->widgetItemId); w.str(e->widgetSourceHash); w.u32(e->widgetDefaultWidth); w.u32(e->widgetDefaultHeight); w.b(e->widgetWarnOnResize); break;
    }
}

std::shared_ptr<Element> readElement(Reader& r,size_t depth,size_t& count,uint16_t version){
    if(depth>MAX_DEPTH) throw std::runtime_error("element nesting too deep");
    if(++count>MAX_ELEMENTS_PER_PAGE) throw std::runtime_error("too many elements");
    auto e=std::make_shared<Element>(); e->type=static_cast<ElementType>(r.u8()); e->id=r.str(); e->name=r.str(); e->rect=readRect(r);
    switch(e->type){
        case ElementType::Block:{ e->layout=static_cast<LayoutMode>(r.u8()); e->background=readBackground(r); e->border=readDecoration(r); e->borderThickness=r.f32(); e->clipChildren=r.b(); e->scrollChildren=r.b(); e->sticky=static_cast<StickyMode>(r.u8()); auto n=r.u16(); e->children.clear(); for(uint16_t i=0;i<n;i++) e->children.push_back(readElement(r,depth+1,count,version)); break; }
        case ElementType::Text: e->text=r.str(); if(version>=2)e->fontId=r.str(); e->fontSize=r.f32(); e->textArgb=r.u32(); e->textAlign=static_cast<TextAlign>(r.u8()); e->bold=r.b(); e->italic=r.b(); if(version>=2)e->underline=r.b(); break;
        case ElementType::Link: e->label=r.str(); e->targetType=static_cast<LinkTargetType>(r.u8()); e->target=r.str(); break;
        case ElementType::Button: e->label=r.str(); e->buttonDecoration=readDecoration(r); if(version>=2){e->background=readBackground(r);e->fontId=r.str();e->fontSize=r.f32();e->textArgb=r.u32();e->textAlign=static_cast<TextAlign>(r.u8());e->bold=r.b();e->italic=r.b();e->underline=r.b();}else{e->background.solidArgb=0xFFECF0F8;e->fontId="Default1";e->fontSize=16.0f;e->textArgb=0xFF111827;e->textAlign=TextAlign::Center;} e->targetType=static_cast<LinkTargetType>(r.u8()); e->target=r.str(); break;
        case ElementType::Stamp: e->stampDecoration=readDecoration(r); e->rotationDegrees=r.f32(); e->opacity=r.f32(); e->flipX=r.b(); e->flipY=r.b(); break;
        case ElementType::Media: e->mediaKind=static_cast<MediaKind>(r.u8()); e->mediaRecordKey=r.str(); e->mediaContentHash=r.str(); e->intrinsicWidth=r.u32(); e->intrinsicHeight=r.u32(); e->mediaTitle=r.str(); e->mediaDescription=r.str(); break;
        case ElementType::Widget: e->widgetLabel=r.str(); e->widgetRecordKey=r.str(); e->widgetItemId=r.u32(); if(version>=3){e->widgetSourceHash=r.str();e->widgetDefaultWidth=r.u32();e->widgetDefaultHeight=r.u32();e->widgetWarnOnResize=r.b();} break;
        default: throw std::runtime_error("unknown VSPF element type");
    }
    return e;
}

size_t countElements(const std::shared_ptr<Element>& e,size_t depth,bool& valid){ if(!e){valid=false;return 0;} if(depth>MAX_DEPTH){valid=false;return 0;} size_t n=1; for(auto&c:e->children)n+=countElements(c,depth+1,valid); return n; }
void validateElement(const std::shared_ptr<Element>& e,size_t depth,ValidationResult& out){
    if(!out.ok)return;
    if(!e){out={false,"null element"};return;}
    if(depth>MAX_DEPTH){out={false,"nesting exceeds limit"};return;}
    const auto& q=e->rect; if(q.x<0||q.y<0||q.width<=0||q.height<=0||q.x>1||q.y>1||q.width>1||q.height>1){out={false,"element rectangle outside normalized range"};return;}
    if(e->background.stops.size()>MAX_GRADIENT_STOPS){out={false,"too many gradient stops"};return;}
    if(e->background.kind==BackgroundKind::LinearGradient && e->background.stops.size()<2){out={false,"gradient needs at least two stops"};return;}
    for(const auto&s:e->background.stops) if(s.position<0||s.position>1){out={false,"gradient stop outside 0..1"};return;}
    if(e->opacity<0||e->opacity>1){out={false,"stamp opacity outside 0..1"};return;}
    for(auto&c:e->children) validateElement(c,depth+1,out);
}

} // namespace

ValidationResult validateProfile(const ProfileDocument& doc){
    if(doc.pages.empty()) return {false,"profile has no pages"};
    if(doc.pages.size()>MAX_PAGES) return {false,"too many pages"};
    bool found=false; for(const auto&p:doc.pages){ if(p.aspectRatio<0.20f||p.aspectRatio>1.20f)return{false,"page aspect ratio outside 0.20..1.20"}; if(p.id==doc.defaultPageId)found=true; bool valid=true; auto count=countElements(p.root,0,valid); if(!valid||count>MAX_ELEMENTS_PER_PAGE)return{false,"page element/depth limit exceeded"}; ValidationResult r;validateElement(p.root,0,r);if(!r.ok)return r; }
    if(!found)return{false,"default page id does not exist"};
    return {};
}

std::vector<uint8_t> encodeProfileBinary(const ProfileDocument& doc){
    auto vr=validateProfile(doc); if(!vr.ok) throw std::runtime_error(vr.message); Writer w; w.data.insert(w.data.end(),{'V','S','P','F'}); w.u16(FORMAT_VERSION); w.str(doc.profileId); w.str(doc.profileName); w.str(doc.defaultPageId); w.u16(static_cast<uint16_t>(doc.pages.size()));
    for(const auto&p:doc.pages){w.str(p.id);w.str(p.name);w.f32(p.aspectRatio);writeElement(w,p.root,0);} return w.data;
}

ProfileDocument decodeProfileBinary(const std::vector<uint8_t>& bytes){
    Reader r(bytes); if(r.u8()!='V'||r.u8()!='S'||r.u8()!='P'||r.u8()!='F')throw std::runtime_error("not a VSPF profile"); auto v=r.u16(); if(v<1||v>FORMAT_VERSION)throw std::runtime_error("unsupported VSPF version"); ProfileDocument d; d.profileId=r.str();d.profileName=r.str();d.defaultPageId=r.str();auto n=r.u16();if(n==0||n>MAX_PAGES)throw std::runtime_error("invalid page count"); for(uint16_t i=0;i<n;i++){Page p;p.id=r.str();p.name=r.str();p.aspectRatio=r.f32();if(p.aspectRatio<0.20f||p.aspectRatio>1.20f)throw std::runtime_error("invalid page aspect ratio");size_t count=0;p.root=readElement(r,0,count,v);d.pages.push_back(std::move(p));} if(r.pos!=bytes.size())throw std::runtime_error("trailing data in VSPF payload"); auto vr=validateProfile(d);if(!vr.ok)throw std::runtime_error(vr.message);return d;
}

static const char* B64="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
std::string base64Encode(const std::vector<uint8_t>& data){std::string out;int val=0,valb=-6;for(uint8_t c:data){val=(val<<8)+c;valb+=8;while(valb>=0){out.push_back(B64[(val>>valb)&0x3F]);valb-=6;}}if(valb>-6)out.push_back(B64[((val<<8)>>(valb+8))&0x3F]);while(out.size()%4)out.push_back('=');return out;}
std::vector<uint8_t> base64Decode(const std::string& text){std::vector<int>T(256,-1);for(int i=0;i<64;i++)T[(unsigned char)B64[i]]=i;std::vector<uint8_t> out;int val=0,valb=-8;for(unsigned char c:text){if(c=='='||c=='\r'||c=='\n'||c==' '||c=='\t')continue;if(T[c]==-1)throw std::runtime_error("invalid base64");val=(val<<6)+T[c];valb+=6;if(valb>=0){out.push_back(static_cast<uint8_t>((val>>valb)&0xFF));valb-=8;}}return out;}
std::string encodeProfileText(const ProfileDocument& doc){return std::string("VEILYSOCIAL_PROFILE_V2\n")+base64Encode(encodeProfileBinary(doc))+"\n";}
ProfileDocument decodeProfileText(const std::string& text){const std::string h1="VEILYSOCIAL_PROFILE_V1",h2="VEILYSOCIAL_PROFILE_V2";auto p=text.find('\n');if(p==std::string::npos||(text.substr(0,p)!=h1&&text.substr(0,p)!=h2))throw std::runtime_error("invalid profile text envelope");return decodeProfileBinary(base64Decode(text.substr(p+1)));}
void saveProfileTextFile(const ProfileDocument& doc,const std::string& path){std::ofstream f(path,std::ios::binary|std::ios::trunc);if(!f)throw std::runtime_error("could not create profile file");auto s=encodeProfileText(doc);f.write(s.data(),static_cast<std::streamsize>(s.size()));if(!f)throw std::runtime_error("could not write profile file");}
ProfileDocument loadProfileTextFile(const std::string& path){std::ifstream f(path,std::ios::binary);if(!f)throw std::runtime_error("could not open profile file");std::ostringstream s;s<<f.rdbuf();return decodeProfileText(s.str());}

} // namespace vs
