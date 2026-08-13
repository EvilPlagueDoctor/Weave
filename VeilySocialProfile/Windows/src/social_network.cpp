#include "social_network.h"
#include <commctrl.h>
#include <shlobj.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace fs = std::filesystem;
namespace vs_social {

static const wchar_t* SOCIAL_CLASS = L"VeilySocialNetworkWindow";

enum : int {
    ID_RECENT = 3001, ID_SEARCH_RESULTS, ID_NAME_QUERY, ID_TERM_QUERY,
    ID_RUN_SEARCH, ID_MORE_LIKE, ID_AVOID_LIKE, ID_CLEAR_LIKE_AVOID,
    ID_AVOIDANCE, ID_COMMON, ID_STUFFING, ID_NOVELTY,
    ID_META_NAME, ID_META_DESC, ID_META_FEATURES, ID_PUBLISH,
    ID_REFRESH, ID_GOSSIP, ID_DEBUG, ID_COPY_DEBUG, ID_CLUSTERS,
};

struct VsnProfileSummary {
    char main_dht[384];
    char profile_root_dht[384];
    char name[128];
    char description[512];
    char features[512];
    char verification[32];
    uint64_t generation;
    uint64_t updated_at;
    uint64_t observed_at;
    float score;
    float positive_similarity;
    float negative_similarity;
    float name_score;
    float term_score;
};
struct VsnCounters { uint32_t peers, verified; uint64_t gossip_sent, gossip_received; };

struct Api {
    HMODULE dll = nullptr;
    void* handle = nullptr;
    void* (*start)() = nullptr;
    void (*stop)(void*) = nullptr;
    int (*publish)(void*,const char*,const char*,const char*,const char*) = nullptr;
    int (*search)(void*,const char*,const char*,const char*,const char*,float,float,float,float) = nullptr;
    int (*refresh)(void*) = nullptr;
    int (*gossip)(void*) = nullptr;
    size_t (*recentCount)(void*) = nullptr;
    int (*recentAt)(void*,size_t,VsnProfileSummary*) = nullptr;
    size_t (*searchCount)(void*) = nullptr;
    int (*searchAt)(void*,size_t,VsnProfileSummary*) = nullptr;
    size_t (*profileText)(void*,const char*,char*,size_t) = nullptr;
    size_t (*status)(void*,char*,size_t) = nullptr;
    size_t (*mainDht)(void*,char*,size_t) = nullptr;
    size_t (*profileRoot)(void*,char*,size_t) = nullptr;
    size_t (*debug)(void*,char*,size_t) = nullptr;
    size_t (*clusters)(void*,char*,size_t) = nullptr;
    int (*counters)(void*,VsnCounters*) = nullptr;
};

struct State {
    HWND owner = nullptr, window = nullptr;
    HostCallbacks callbacks{};
    Api api{};
    HWND status{}, counters{}, recent{}, searchResults{}, nameQuery{}, termQuery{};
    HWND avoidance{}, common{}, stuffing{}, novelty{};
    HWND metaName{}, metaDesc{}, metaFeatures{}, debug{}, clusters{};
    std::vector<VsnProfileSummary> recentData, searchData;
    std::string positiveDht, negativeDht;
    fs::path metadataFile;
} g;

static std::wstring wide(const std::string& s){
    if(s.empty()) return L""; int n=MultiByteToWideChar(CP_UTF8,0,s.data(),(int)s.size(),nullptr,0); std::wstring out(n,L'\0'); if(n)MultiByteToWideChar(CP_UTF8,0,s.data(),(int)s.size(),out.data(),n); return out;
}
static std::string utf8(const std::wstring& s){
    if(s.empty()) return {}; int n=WideCharToMultiByte(CP_UTF8,0,s.data(),(int)s.size(),nullptr,0,nullptr,nullptr); std::string out(n,'\0'); if(n)WideCharToMultiByte(CP_UTF8,0,s.data(),(int)s.size(),out.data(),n,nullptr,nullptr); return out;
}
static std::wstring getTextW(HWND h){ int n=GetWindowTextLengthW(h); std::wstring s((size_t)n+1,L'\0'); if(n)GetWindowTextW(h,s.data(),n+1); s.resize((size_t)n); return s; }
static std::string getText(HWND h){ return utf8(getTextW(h)); }
static void setText(HWND h,const std::string&s){ auto w=wide(s);SetWindowTextW(h,w.c_str()); }
static HWND ctl(const wchar_t* cls,const wchar_t* text,DWORD style,int id,int x,int y,int w,int h){
    HWND c=CreateWindowExW((wcscmp(cls,L"EDIT")==0||wcscmp(cls,L"LISTBOX")==0)?WS_EX_CLIENTEDGE:0,cls,text,WS_CHILD|WS_VISIBLE|style,x,y,w,h,g.window,(HMENU)(INT_PTR)id,GetModuleHandleW(nullptr),nullptr);
    SendMessageW(c,WM_SETFONT,(WPARAM)GetStockObject(DEFAULT_GUI_FONT),TRUE); return c;
}
static std::string apiString(size_t(*fn)(void*,char*,size_t)){
    if(!fn||!g.api.handle)return{}; size_t need=fn(g.api.handle,nullptr,0); if(!need)return{}; std::vector<char>b(need); fn(g.api.handle,b.data(),b.size()); return std::string(b.data());
}
static std::string profileText(const std::string& dht){
    if(!g.api.profileText||!g.api.handle)return{}; size_t need=g.api.profileText(g.api.handle,dht.c_str(),nullptr,0); if(!need)return{}; std::vector<char>b(need); g.api.profileText(g.api.handle,dht.c_str(),b.data(),b.size()); return std::string(b.data());
}
static void clipboard(const std::string& value){
    auto w=wide(value); if(!OpenClipboard(g.window))return;EmptyClipboard();size_t bytes=(w.size()+1)*sizeof(wchar_t);HGLOBAL mem=GlobalAlloc(GMEM_MOVEABLE,bytes);if(mem){void*p=GlobalLock(mem);memcpy(p,w.c_str(),bytes);GlobalUnlock(mem);SetClipboardData(CF_UNICODETEXT,mem);}CloseClipboard();
}
static std::string esc(const std::string&s){std::string o;for(char c:s){if(c=='\\')o+="\\\\";else if(c=='\n')o+="\\n";else if(c=='\r'){}else o+=c;}return o;}
static std::string unesc(const std::string&s){std::string o;for(size_t i=0;i<s.size();++i){if(s[i]=='\\'&&i+1<s.size()){char n=s[++i];o+=n=='n'?'\n':n;}else o+=s[i];}return o;}
static void saveMetadata(){
    if(g.metadataFile.empty())return;fs::create_directories(g.metadataFile.parent_path());std::ofstream f(g.metadataFile,std::ios::binary);f<<"name="<<esc(getText(g.metaName))<<"\n"<<"description="<<esc(getText(g.metaDesc))<<"\n"<<"features="<<esc(getText(g.metaFeatures))<<"\n";
}
static void loadMetadata(){
    std::string n,d,fe;std::ifstream f(g.metadataFile,std::ios::binary);std::string line;while(std::getline(f,line)){auto p=line.find('=');if(p==std::string::npos)continue;auto k=line.substr(0,p),v=unesc(line.substr(p+1));if(k=="name")n=v;else if(k=="description")d=v;else if(k=="features")fe=v;}
    if(n.empty()&&g.callbacks.getOwnProfileName)n=g.callbacks.getOwnProfileName();setText(g.metaName,n);setText(g.metaDesc,d);setText(g.metaFeatures,fe);
}

template<class T> static T proc(const char* name){return reinterpret_cast<T>(GetProcAddress(g.api.dll,name));}
static bool loadApi(){
    wchar_t exe[MAX_PATH]{};GetModuleFileNameW(nullptr,exe,MAX_PATH);fs::path dll=fs::path(exe).parent_path()/L"veilysocial_profile_network_bridge.dll";g.api.dll=LoadLibraryW(dll.c_str());if(!g.api.dll)return false;
    g.api.start=proc<decltype(g.api.start)>("vsn_start");g.api.stop=proc<decltype(g.api.stop)>("vsn_stop");g.api.publish=proc<decltype(g.api.publish)>("vsn_publish");g.api.search=proc<decltype(g.api.search)>("vsn_search");g.api.refresh=proc<decltype(g.api.refresh)>("vsn_refresh");g.api.gossip=proc<decltype(g.api.gossip)>("vsn_gossip_now");g.api.recentCount=proc<decltype(g.api.recentCount)>("vsn_recent_count");g.api.recentAt=proc<decltype(g.api.recentAt)>("vsn_recent_at");g.api.searchCount=proc<decltype(g.api.searchCount)>("vsn_search_count");g.api.searchAt=proc<decltype(g.api.searchAt)>("vsn_search_at");g.api.profileText=proc<decltype(g.api.profileText)>("vsn_get_profile_text");g.api.status=proc<decltype(g.api.status)>("vsn_get_status");g.api.mainDht=proc<decltype(g.api.mainDht)>("vsn_get_main_dht");g.api.profileRoot=proc<decltype(g.api.profileRoot)>("vsn_get_profile_root");g.api.debug=proc<decltype(g.api.debug)>("vsn_get_debug_log");g.api.clusters=proc<decltype(g.api.clusters)>("vsn_get_cluster_text");g.api.counters=proc<decltype(g.api.counters)>("vsn_get_counters");
    if(!g.api.start||!g.api.stop||!g.api.publish||!g.api.search||!g.api.recentCount||!g.api.recentAt||!g.api.profileText)return false;g.api.handle=g.api.start();return g.api.handle!=nullptr;
}
static std::wstring rowText(const VsnProfileSummary&p,bool scored){
    std::wstringstream s;s<<wide(p.name[0]?p.name:"(unnamed)");if(scored)s<<L"  score "<<(int)(p.score*100)<<L"%";s<<L"  ["<<wide(p.verification)<<L"]";if(p.description[0])s<<L" — "<<wide(p.description);return s.str();
}
static void refreshLists(){
    if(!g.api.handle)return;
    setText(g.status,apiString(g.api.status));
    VsnCounters c{};if(g.api.counters&&g.api.counters(g.api.handle,&c)){std::ostringstream s;s<<"Peers "<<c.peers<<" | verified "<<c.verified<<" | gossip ↑"<<c.gossip_sent<<" ↓"<<c.gossip_received;setText(g.counters,s.str());}
    g.recentData.clear();SendMessageW(g.recent,LB_RESETCONTENT,0,0);size_t rn=std::min<size_t>(50,g.api.recentCount(g.api.handle));for(size_t i=0;i<rn;++i){VsnProfileSummary p{};if(g.api.recentAt(g.api.handle,i,&p)){g.recentData.push_back(p);auto row=rowText(p,false);SendMessageW(g.recent,LB_ADDSTRING,0,(LPARAM)row.c_str());}}
    g.searchData.clear();SendMessageW(g.searchResults,LB_RESETCONTENT,0,0);size_t sn=g.api.searchCount?std::min<size_t>(100,g.api.searchCount(g.api.handle)):0;for(size_t i=0;i<sn;++i){VsnProfileSummary p{};if(g.api.searchAt(g.api.handle,i,&p)){g.searchData.push_back(p);auto row=rowText(p,true);SendMessageW(g.searchResults,LB_ADDSTRING,0,(LPARAM)row.c_str());}}
    if(g.api.debug)setText(g.debug,apiString(g.api.debug));if(g.api.clusters)setText(g.clusters,apiString(g.api.clusters));
}
static const VsnProfileSummary* selectedProfile(){
    int i=(int)SendMessageW(g.searchResults,LB_GETCURSEL,0,0);if(i>=0&&i<(int)g.searchData.size())return &g.searchData[i];i=(int)SendMessageW(g.recent,LB_GETCURSEL,0,0);if(i>=0&&i<(int)g.recentData.size())return &g.recentData[i];return nullptr;
}
static void runSearch(){
    if(!g.api.search||!g.api.handle)return;float av=(float)SendMessageW(g.avoidance,TBM_GETPOS,0,0)/100.f,co=(float)SendMessageW(g.common,TBM_GETPOS,0,0)/100.f,st=(float)SendMessageW(g.stuffing,TBM_GETPOS,0,0)/100.f,no=(float)SendMessageW(g.novelty,TBM_GETPOS,0,0)/100.f;auto nq=getText(g.nameQuery),tq=getText(g.termQuery);g.api.search(g.api.handle,nq.c_str(),tq.c_str(),g.positiveDht.c_str(),g.negativeDht.c_str(),av,co,st,no);
}
static void openSelected(){
    const auto*p=selectedProfile();if(!p)return;std::string text=profileText(p->main_dht);if(text.empty()){MessageBoxW(g.window,L"That profile is currently only a gossip hint, or its DHT/blob has not been verified yet. Try Refresh peers and open it again.",L"Profile not verified yet",MB_OK|MB_ICONINFORMATION);return;}if(g.callbacks.openRemoteProfile)g.callbacks.openRemoteProfile(p->main_dht,p->name,text);
}
static void layout(int w,int h){
    const int pad=10,left=10,mid=w/2+5,col=w/2-15;MoveWindow(g.status,left,8,w-20,22,TRUE);MoveWindow(g.counters,left,30,w-20,20,TRUE);
    MoveWindow(g.recent,left,74,col,std::max(150,h-360),TRUE);MoveWindow(g.searchResults,mid,190,col,std::max(120,h-476),TRUE);
    MoveWindow(g.nameQuery,mid,74,col,26,TRUE);MoveWindow(g.termQuery,mid,106,col,50,TRUE);
    HWND run=GetDlgItem(g.window,ID_RUN_SEARCH),more=GetDlgItem(g.window,ID_MORE_LIKE),avoid=GetDlgItem(g.window,ID_AVOID_LIKE),clear=GetDlgItem(g.window,ID_CLEAR_LIKE_AVOID);MoveWindow(run,mid,160,110,26,TRUE);MoveWindow(more,mid+116,160,110,26,TRUE);MoveWindow(avoid,mid+232,160,110,26,TRUE);MoveWindow(clear,mid+348,160,std::max(90,col-348),26,TRUE);
    int y=h-270;MoveWindow(g.metaName,left,y,col,26,TRUE);MoveWindow(g.metaDesc,left,y+32,col,48,TRUE);MoveWindow(g.metaFeatures,left,y+86,col,48,TRUE);MoveWindow(GetDlgItem(g.window,ID_PUBLISH),left,y+140,col,30,TRUE);MoveWindow(GetDlgItem(g.window,ID_REFRESH),left,y+176,100,28,TRUE);MoveWindow(GetDlgItem(g.window,ID_GOSSIP),left+106,y+176,100,28,TRUE);
    MoveWindow(g.avoidance,mid,y, col,26,TRUE);MoveWindow(g.common,mid,y+32,col,26,TRUE);MoveWindow(g.stuffing,mid,y+64,col,26,TRUE);MoveWindow(g.novelty,mid,y+96,col,26,TRUE);MoveWindow(g.clusters,mid,y+128,col,76,TRUE);MoveWindow(g.debug,left,h-58,w-130,48,TRUE);MoveWindow(GetDlgItem(g.window,ID_COPY_DEBUG),w-115,h-58,105,28,TRUE);
}
static LRESULT CALLBACK Proc(HWND h,UINT m,WPARAM wp,LPARAM lp){
    switch(m){
    case WM_CREATE:{
        g.window=h;g.status=ctl(L"STATIC",L"Starting network…",SS_LEFT,0,10,8,700,22);g.counters=ctl(L"STATIC",L"",SS_LEFT,0,10,30,700,20);
        ctl(L"STATIC",L"Recent 50 — newest first (double-click to view)",SS_LEFT,0,10,54,450,20);g.recent=ctl(L"LISTBOX",L"",LBS_NOTIFY|WS_VSCROLL,ID_RECENT,10,74,450,300);
        ctl(L"STATIC",L"Search name / prefix",SS_LEFT,0,470,54,260,20);g.nameQuery=ctl(L"EDIT",L"",ES_AUTOHSCROLL,ID_NAME_QUERY,470,74,400,26);g.termQuery=ctl(L"EDIT",L"",ES_MULTILINE|ES_AUTOVSCROLL,ID_TERM_QUERY,470,106,400,50);
        ctl(L"BUTTON",L"Search / continue",BS_PUSHBUTTON,ID_RUN_SEARCH,470,160,110,26);ctl(L"BUTTON",L"More like selected",BS_PUSHBUTTON,ID_MORE_LIKE,586,160,110,26);ctl(L"BUTTON",L"Avoid selected",BS_PUSHBUTTON,ID_AVOID_LIKE,702,160,110,26);ctl(L"BUTTON",L"Clear ±",BS_PUSHBUTTON,ID_CLEAR_LIKE_AVOID,818,160,80,26);g.searchResults=ctl(L"LISTBOX",L"",LBS_NOTIFY|WS_VSCROLL,ID_SEARCH_RESULTS,470,190,430,200);
        g.metaName=ctl(L"EDIT",L"",ES_AUTOHSCROLL,ID_META_NAME,10,430,450,26);g.metaDesc=ctl(L"EDIT",L"",ES_MULTILINE|ES_AUTOVSCROLL,ID_META_DESC,10,462,450,48);g.metaFeatures=ctl(L"EDIT",L"",ES_MULTILINE|ES_AUTOVSCROLL,ID_META_FEATURES,10,516,450,48);ctl(L"BUTTON",L"Publish current Profile Page",BS_PUSHBUTTON,ID_PUBLISH,10,570,450,30);ctl(L"BUTTON",L"Refresh peers",BS_PUSHBUTTON,ID_REFRESH,10,606,100,28);ctl(L"BUTTON",L"Gossip now",BS_PUSHBUTTON,ID_GOSSIP,116,606,100,28);
        g.avoidance=ctl(TRACKBAR_CLASSW,L"",TBS_AUTOTICKS,ID_AVOIDANCE,470,430,430,26);SendMessageW(g.avoidance,TBM_SETRANGE,TRUE,MAKELPARAM(0,200));SendMessageW(g.avoidance,TBM_SETPOS,TRUE,75);g.common=ctl(TRACKBAR_CLASSW,L"",TBS_AUTOTICKS,ID_COMMON,470,462,430,26);SendMessageW(g.common,TBM_SETRANGE,TRUE,MAKELPARAM(0,100));SendMessageW(g.common,TBM_SETPOS,TRUE,20);g.stuffing=ctl(TRACKBAR_CLASSW,L"",TBS_AUTOTICKS,ID_STUFFING,470,494,430,26);SendMessageW(g.stuffing,TBM_SETRANGE,TRUE,MAKELPARAM(0,100));SendMessageW(g.stuffing,TBM_SETPOS,TRUE,25);g.novelty=ctl(TRACKBAR_CLASSW,L"",TBS_AUTOTICKS,ID_NOVELTY,470,526,430,26);SendMessageW(g.novelty,TBM_SETRANGE,TRUE,MAKELPARAM(0,75));SendMessageW(g.novelty,TBM_SETPOS,TRUE,10);g.clusters=ctl(L"EDIT",L"",ES_MULTILINE|ES_READONLY|WS_VSCROLL,ID_CLUSTERS,470,558,430,76);g.debug=ctl(L"EDIT",L"",ES_MULTILINE|ES_READONLY|WS_VSCROLL,ID_DEBUG,10,650,760,48);ctl(L"BUTTON",L"Copy debug",BS_PUSHBUTTON,ID_COPY_DEBUG,780,650,110,28);
        loadMetadata();SetTimer(h,1,700,nullptr);refreshLists();return 0;
    }
    case WM_SIZE:layout(LOWORD(lp),HIWORD(lp));return 0;
    case WM_TIMER:refreshLists();return 0;
    case WM_COMMAND:{int id=LOWORD(wp),code=HIWORD(wp);if((id==ID_RECENT||id==ID_SEARCH_RESULTS)&&code==LBN_DBLCLK){openSelected();return 0;}if(id==ID_RUN_SEARCH){runSearch();return 0;}if(id==ID_MORE_LIKE){if(auto*p=selectedProfile()){g.positiveDht=p->main_dht;runSearch();}return 0;}if(id==ID_AVOID_LIKE){if(auto*p=selectedProfile()){g.negativeDht=p->main_dht;runSearch();}return 0;}if(id==ID_CLEAR_LIKE_AVOID){g.positiveDht.clear();g.negativeDht.clear();runSearch();return 0;}if(id==ID_PUBLISH){if(!g.callbacks.getOwnProfileText)return 0;auto text=g.callbacks.getOwnProfileText(),name=getText(g.metaName),desc=getText(g.metaDesc),features=getText(g.metaFeatures);if(name.empty()&&g.callbacks.getOwnProfileName)name=g.callbacks.getOwnProfileName();if(text.empty()){MessageBoxW(h,L"No local profile page is available to publish.",L"Publish",MB_OK|MB_ICONWARNING);return 0;}saveMetadata();if(g.api.publish)g.api.publish(g.api.handle,text.c_str(),name.c_str(),desc.c_str(),features.c_str());return 0;}if(id==ID_REFRESH){if(g.api.refresh)g.api.refresh(g.api.handle);return 0;}if(id==ID_GOSSIP){if(g.api.gossip)g.api.gossip(g.api.handle);return 0;}if(id==ID_COPY_DEBUG){clipboard(apiString(g.api.debug));return 0;}break;}
    case WM_CLOSE:ShowWindow(h,SW_HIDE);return 0;
    case WM_DESTROY:g.window=nullptr;return 0;
    }
    return DefWindowProcW(h,m,wp,lp);
}

bool initialize(HWND owner, HostCallbacks callbacks){
    g.owner=owner;g.callbacks=callbacks;wchar_t p[MAX_PATH]{};SHGetFolderPathW(nullptr,CSIDL_LOCAL_APPDATA|CSIDL_FLAG_CREATE,nullptr,SHGFP_TYPE_CURRENT,p);g.metadataFile=fs::path(p)/L"VeilySocial"/L"ProfileDesigner"/L"discovery_metadata.txt";
    WNDCLASSEXW wc{sizeof(wc)};wc.lpfnWndProc=Proc;wc.hInstance=GetModuleHandleW(nullptr);wc.hCursor=LoadCursor(nullptr,IDC_ARROW);wc.hbrBackground=(HBRUSH)(COLOR_WINDOW+1);wc.lpszClassName=SOCIAL_CLASS;RegisterClassExW(&wc);
    bool ok=loadApi();g.window=CreateWindowExW(WS_EX_TOOLWINDOW,SOCIAL_CLASS,L"VeilySocial — Network Profiles",WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN,CW_USEDEFAULT,CW_USEDEFAULT,980,780,owner,nullptr,GetModuleHandleW(nullptr),nullptr);if(!g.window)return false;if(!ok)setText(g.status,"Network bridge DLL could not be loaded. Build Windows/network_bridge first.");return true;
}
void show(){if(!g.window)return;ShowWindow(g.window,SW_SHOW);SetForegroundWindow(g.window);}
void shutdown(){saveMetadata();if(g.api.handle&&g.api.stop)g.api.stop(g.api.handle);g.api.handle=nullptr;if(g.api.dll)FreeLibrary(g.api.dll);g.api.dll=nullptr;if(g.window&&IsWindow(g.window))DestroyWindow(g.window);g.window=nullptr;}

} // namespace vs_social
