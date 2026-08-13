#include "profile_codec.h"
#include <iostream>

static std::shared_ptr<vs::Element> findWidget(const std::shared_ptr<vs::Element>& e){
    if(!e) return {};
    if(e->type==vs::ElementType::Widget) return e;
    for(const auto& c:e->children) if(auto w=findWidget(c)) return w;
    return {};
}

int main(int argc,char**argv){
    try{
        if(argc>1){ auto p=vs::loadProfileTextFile(argv[1]); std::cout<<p.profileName<<"|"<<p.pages.size()<<"|"<<p.defaultPageId<<"\n"; return 0; }
        auto p=vs::makeDefaultProfile();
        auto w=findWidget(p.pages.at(0).root);
        if(!w) return 3;
        w->widgetRecordKey="local-widget:test";
        w->widgetSourceHash="0123456789abcdef";
        w->widgetDefaultWidth=444;
        w->widgetDefaultHeight=222;
        w->widgetWarnOnResize=false;
        auto text=vs::encodeProfileText(p);
        auto q=vs::decodeProfileText(text);
        auto qw=findWidget(q.pages.at(0).root);
        if(q.pages.size()!=1||q.profileName!=p.profileName||!qw) return 2;
        if(qw->widgetSourceHash!="0123456789abcdef"||qw->widgetDefaultWidth!=444||qw->widgetDefaultHeight!=222||qw->widgetWarnOnResize) return 4;
        std::cout<<text;
        return 0;
    }catch(const std::exception&e){std::cerr<<e.what()<<"\n";return 1;}
}
