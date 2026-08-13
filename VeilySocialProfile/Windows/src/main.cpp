#include <windows.h>
#include <windowsx.h>
#include <commctrl.h>
#include <commdlg.h>
#include <shlobj.h>
#include <d3d9.h>
#include <uxtheme.h>
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <functional>
#include <sstream>
#include <string>
#include <utility>
#include <vector>
#include "profile_codec.h"
#include "social_network.h"
#include "resource.h"

#pragma comment(lib, "Comctl32.lib")
#pragma comment(lib, "Comdlg32.lib")
#pragma comment(lib, "Shell32.lib")
#pragma comment(lib, "Msimg32.lib")
#pragma comment(lib, "d3d9.lib")
#pragma comment(lib, "UxTheme.lib")

using namespace vs;
namespace fs = std::filesystem;

static const wchar_t* APP_CLASS = L"VeilySocialProfileDesignerWindow";
static const int PANEL_W = 370;
static const int HEADER_H = 54;
static const int TOOLBAR_H = 78;
static const int PROP_ROW_H = 42;

enum ControlId {
    ID_HIERARCHY = 100, ID_NAME,
    ID_X, ID_Y, ID_W, ID_H, ID_PAGE_ASPECT,
    ID_BG_KIND, ID_SOLID_COLOR, ID_SOLID_PICK,
    ID_GSX, ID_GSY, ID_GEX, ID_GEY,
    ID_STOP_SELECT, ID_STOP_POS, ID_STOP_COLOR, ID_STOP_PICK, ID_ADD_STOP, ID_REMOVE_STOP,
    ID_BORDER, ID_TEXT, ID_TARGET_TYPE, ID_TARGET,
    ID_STAMP, ID_ROTATION, ID_OPACITY,
    ID_MEDIA_KIND, ID_MEDIA_TITLE, ID_MEDIA_DESC,
    ID_WIDGET_LABEL,
    ID_DELETE, ID_DUPLICATE, ID_BACKWARD, ID_FORWARD,
    ID_OPEN, ID_UNDO, ID_REDO, ID_REVERT, ID_SAVE, ID_SAVE_AS, ID_PUBLISH,
    ID_COLLAPSE, ID_PANEL_SCROLL,
    ID_SEC_DEPTH, ID_SEC_MOVE, ID_SEC_APPEARANCE, ID_SEC_CONTENT, ID_SEC_FILE, ID_DEPTH_LIST,
    ID_MODE_BG, ID_MODE_BOXES, ID_MODE_FG, ID_EDIT_CONTENTS, ID_EXIT_BOX,
    ID_SOCIAL, ID_RETURN_OWN
};

enum class Tool { Move, Text, Block, Link, Button, Stamp, Image, Widget, Page };
enum class EditMode { Background, Boxes, Foreground };
struct NRect { float x=0,y=0,w=1,h=1; };

struct HierarchyEntry { std::wstring label; size_t pageIndex{}; std::string elementId; };
struct Hit { std::shared_ptr<Element> element; std::shared_ptr<Element> parent; RECT rect{}; size_t pageIndex{}; };

struct AppState {
    ProfileDocument doc = makeDefaultProfile();
    size_t pageIndex = 0;
    std::string selectedId;
    bool panelCollapsed = false;
    Tool tool = Tool::Move;
    int toolbarOffset = 0;
    bool dragging = false;
    bool resizing = false;
    POINT dragStart{};
    RectSpec dragOriginal{};
    RECT selectedScreenRect{};
    std::vector<std::string> undo;
    std::vector<std::string> redo;
    std::string savedSnapshot;
    fs::path currentFile;
    fs::path profileDir;
    std::vector<HierarchyEntry> hierarchy;
    int selectedGradientStop = 0;
    bool applyingControls = false;
    int panelScroll = 0;
    int panelContentHeight = 0;
    EditMode mode = EditMode::Boxes;
    std::string focusedBoxId;
    bool secDepth = true, secMove = true, secAppearance = false, secContent = true, secFile = false;
    std::string depthArmedId;
    bool depthInvalidDrop = false;
    bool depthDragging = false;
    bool depthDragSnapshot = false;
    bool sliderEditing = false;
    HWND sliderEditingHandle = nullptr;
    NRect camera{0,0,1,1}, cameraTarget{0,0,1,1};
    float workspaceZoom = 1.0f;
    bool zoomGesture = false;
    int zoomStartY = 0;
    float zoomStartValue = 1.0f;
    bool viewingRemote = false;
    bool ownPanelCollapsed = false;
    std::string ownEditSnapshot;
    std::string ownProfileName;
    std::string remoteMainDht;
} g;

HWND g_hwnd = nullptr;
HWND cHierarchy,cName,cX,cY,cW,cH,cPageAspect,cBgKind,cSolidColor,cSolidPick,cGSX,cGSY,cGEX,cGEY,cStopSelect,cStopPos,cStopColor,cStopPick,cAddStop,cRemoveStop,cBorder,cText,cTargetType,cTarget,cStamp,cRotation,cOpacity,cMediaKind,cMediaTitle,cMediaDesc,cWidgetLabel;
HWND cDelete,cDuplicate,cBackward,cForward,cOpen,cUndo,cRedo,cRevert,cSave,cSaveAs,cPublish,cCollapse,cPanelScroll;
HWND cSecDepth,cSecMove,cSecAppearance,cSecContent,cSecFile,cDepthHelp,cDepthList,cModeBg,cModeBoxes,cModeFg,cEditContents,cExitBox,cTooltip,cSocial,cReturnOwn;
std::vector<HWND> propertyControls;
std::vector<std::pair<HWND,RECT>> sliderValueRects;

static std::wstring tr(int id){ wchar_t b[512]{}; int n=LoadStringW(GetModuleHandleW(nullptr),id,b,(int)_countof(b)); return n>0?std::wstring(b,n):L""; }


// Direct3D 9 is used as the presentation/back-buffer layer.  The existing
// Win32 drawing code renders into an off-screen D3D surface through an HDC,
// then Direct3D copies that completed frame to the swap chain in one present.
// This keeps the very broad D3D9 hardware compatibility while eliminating the
// visible erase/partial-redraw cycle that caused the editor to flash while
// dragging or moving sliders.
static IDirect3D9* g_d3d = nullptr;
static IDirect3DDevice9* g_d3dDevice = nullptr;
static IDirect3DSurface9* g_d3dSystemSurface = nullptr;
static IDirect3DSurface9* g_d3dGpuSurface = nullptr;
static D3DPRESENT_PARAMETERS g_d3dpp{};
static int g_d3dWidth = 0;
static int g_d3dHeight = 0;

static void releaseD3DSurfaces(){
    if(g_d3dGpuSurface){ g_d3dGpuSurface->Release(); g_d3dGpuSurface=nullptr; }
    if(g_d3dSystemSurface){ g_d3dSystemSurface->Release(); g_d3dSystemSurface=nullptr; }
    g_d3dWidth=0; g_d3dHeight=0;
}

static void shutdownD3D(){
    releaseD3DSurfaces();
    if(g_d3dDevice){ g_d3dDevice->Release(); g_d3dDevice=nullptr; }
    if(g_d3d){ g_d3d->Release(); g_d3d=nullptr; }
}

static bool createD3DSurfaces(int width,int height){
    if(!g_d3dDevice || width<=0 || height<=0) return false;
    releaseD3DSurfaces();
    HRESULT hr=g_d3dDevice->CreateOffscreenPlainSurface(width,height,D3DFMT_X8R8G8B8,D3DPOOL_SYSTEMMEM,&g_d3dSystemSurface,nullptr);
    if(FAILED(hr)) return false;
    hr=g_d3dDevice->CreateOffscreenPlainSurface(width,height,D3DFMT_X8R8G8B8,D3DPOOL_DEFAULT,&g_d3dGpuSurface,nullptr);
    if(FAILED(hr)){ releaseD3DSurfaces(); return false; }
    g_d3dWidth=width; g_d3dHeight=height;
    return true;
}

static bool resetD3D(int width,int height){
    if(!g_d3dDevice || width<=0 || height<=0) return false;
    releaseD3DSurfaces();
    g_d3dpp.BackBufferWidth=width;
    g_d3dpp.BackBufferHeight=height;
    HRESULT hr=g_d3dDevice->Reset(&g_d3dpp);
    if(FAILED(hr)) return false;
    return createD3DSurfaces(width,height);
}

static bool initD3D(HWND hwnd){
    g_d3d=Direct3DCreate9(D3D_SDK_VERSION);
    if(!g_d3d) return false;
    RECT r{}; GetClientRect(hwnd,&r);
    int width=std::max(1,static_cast<int>(r.right-r.left));
    int height=std::max(1,static_cast<int>(r.bottom-r.top));
    ZeroMemory(&g_d3dpp,sizeof(g_d3dpp));
    g_d3dpp.Windowed=TRUE;
    g_d3dpp.SwapEffect=D3DSWAPEFFECT_DISCARD;
    g_d3dpp.hDeviceWindow=hwnd;
    g_d3dpp.BackBufferFormat=D3DFMT_X8R8G8B8;
    g_d3dpp.BackBufferWidth=width;
    g_d3dpp.BackBufferHeight=height;
    g_d3dpp.PresentationInterval=D3DPRESENT_INTERVAL_IMMEDIATE;

    DWORD flags=D3DCREATE_HARDWARE_VERTEXPROCESSING|D3DCREATE_FPU_PRESERVE;
    HRESULT hr=g_d3d->CreateDevice(D3DADAPTER_DEFAULT,D3DDEVTYPE_HAL,hwnd,flags,&g_d3dpp,&g_d3dDevice);
    if(FAILED(hr)){
        flags=D3DCREATE_SOFTWARE_VERTEXPROCESSING|D3DCREATE_FPU_PRESERVE;
        hr=g_d3d->CreateDevice(D3DADAPTER_DEFAULT,D3DDEVTYPE_HAL,hwnd,flags,&g_d3dpp,&g_d3dDevice);
    }
    if(FAILED(hr)){
        // Last-resort reference rasterizer.  It is slow but lets very old or
        // unusual machines still open the editor rather than simply failing.
        hr=g_d3d->CreateDevice(D3DADAPTER_DEFAULT,D3DDEVTYPE_REF,hwnd,D3DCREATE_SOFTWARE_VERTEXPROCESSING|D3DCREATE_FPU_PRESERVE,&g_d3dpp,&g_d3dDevice);
    }
    if(FAILED(hr)){ shutdownD3D(); return false; }
    return createD3DSurfaces(width,height);
}

static std::wstring w(const std::string& s){ if(s.empty())return {}; int n=MultiByteToWideChar(CP_UTF8,0,s.data(),(int)s.size(),nullptr,0); std::wstring r(n,0); MultiByteToWideChar(CP_UTF8,0,s.data(),(int)s.size(),r.data(),n); return r; }
static std::string u8(const std::wstring& s){ if(s.empty())return {}; int n=WideCharToMultiByte(CP_UTF8,0,s.data(),(int)s.size(),nullptr,0,nullptr,nullptr); std::string r(n,0); WideCharToMultiByte(CP_UTF8,0,s.data(),(int)s.size(),r.data(),n,nullptr,nullptr); return r; }

static void localizeDefaultDocument(ProfileDocument& d){
    d.profileName=u8(tr(IDS_DEFAULT_PROFILE_NAME));
    if(d.pages.empty())return;auto&p=d.pages.front();p.name=u8(tr(IDS_DEFAULT_PAGE_NAME));p.root->name=u8(tr(IDS_DEFAULT_PAGE_EDITOR_NAME));
    int stampIndex=0,boxIndex=0;
    for(auto&e:p.root->children){
        if(e->type==ElementType::Stamp){e->name=u8(tr(stampIndex++==0?IDS_DEFAULT_STAR_NAME:IDS_DEFAULT_STAR2_NAME));continue;}
        if(e->type!=ElementType::Block)continue;
        if(boxIndex++==0){e->name=u8(tr(IDS_DEFAULT_HEADER_BOX));for(auto&c:e->children)if(c->type==ElementType::Text){c->name=u8(tr(IDS_DEFAULT_PROFILE_TITLE_NAME));c->text=u8(tr(IDS_DEFAULT_PROFILE_TITLE));break;}}
        else{e->name=u8(tr(IDS_DEFAULT_WELCOME_BOX));for(auto&c:e->children){if(c->type==ElementType::Text){c->name=u8(tr(IDS_DEFAULT_WELCOME_TEXT_NAME));c->text=u8(tr(IDS_DEFAULT_WELCOME_TEXT));}else if(c->type==ElementType::Widget){c->name=u8(tr(IDS_DEFAULT_WIDGET_NAME));c->widgetLabel=u8(tr(IDS_FUTURE_WIDGET));}}}
    }
}

static std::wstring getText(HWND h){int n=GetWindowTextLengthW(h);std::wstring s((size_t)n+1,L'\0');GetWindowTextW(h,s.data(),n+1);s.resize((size_t)n);return s;}
static void setText(HWND h,const std::wstring&s){SetWindowTextW(h,s.c_str());}
static uint32_t parseColor(const std::wstring&s,uint32_t fallback){ std::wstring t=s; if(!t.empty()&&t[0]==L'#')t.erase(t.begin()); if(t.size()!=6&&t.size()!=8)return fallback; wchar_t*e=nullptr; unsigned long v=wcstoul(t.c_str(),&e,16); if(!e||*e)return fallback; if(t.size()==6)v|=0xFF000000UL; return (uint32_t)v; }
static std::wstring colorText(uint32_t c){ wchar_t b[16]; swprintf_s(b,L"#%06X",c&0xFFFFFF); return b; }
static COLORREF rgb(uint32_t c){ return RGB((c>>16)&255,(c>>8)&255,c&255); }

struct HsvColor { double h=0.0,s=0.0,v=1.0; };
static HsvColor toHsv(uint32_t argb){
    double r=((argb>>16)&255)/255.0,g=((argb>>8)&255)/255.0,b=(argb&255)/255.0;
    double mx=std::max(r,std::max(g,b)),mn=std::min(r,std::min(g,b)),d=mx-mn;HsvColor o;o.v=mx;o.s=mx<=0.0?0.0:d/mx;
    if(d<=1e-9)o.h=0.0;else if(mx==r)o.h=60.0*std::fmod(((g-b)/d),6.0);else if(mx==g)o.h=60.0*(((b-r)/d)+2.0);else o.h=60.0*(((r-g)/d)+4.0);
    if(o.h<0)o.h+=360.0;return o;
}
static uint32_t fromHsv(double h,double s,double v){
    h=std::fmod(h,360.0);if(h<0)h+=360.0;s=std::max(0.0,std::min(1.0,s));v=std::max(0.0,std::min(1.0,v));double c=v*s,x=c*(1.0-std::fabs(std::fmod(h/60.0,2.0)-1.0)),m=v-c;double r=0,g=0,b=0;
    if(h<60){r=c;g=x;}else if(h<120){r=x;g=c;}else if(h<180){g=c;b=x;}else if(h<240){g=x;b=c;}else if(h<300){r=x;b=c;}else{r=c;b=x;}
    auto q=[&](double z){return (uint32_t)std::lround((z+m)*255.0);};return 0xFF000000u|(q(r)<<16)|(q(g)<<8)|q(b);
}

struct ColorPickerState{
    HsvColor hsv{};uint32_t result=0xFFFFFFFFu;bool done=false,ok=false,dragWheel=false,dragValue=false,updatingHex=false;HWND hexEdit=nullptr;
};
static RECT pickerWheelRect(){return RECT{22,52,282,312};}
static RECT pickerValueRect(){return RECT{302,52,330,312};}
static void pickerSyncHex(ColorPickerState*st){if(!st||!st->hexEdit)return;st->updatingHex=true;setText(st->hexEdit,colorText(fromHsv(st->hsv.h,st->hsv.s,st->hsv.v)));st->updatingHex=false;}
static void pickerSetWheelPoint(ColorPickerState*st,POINT p){RECT r=pickerWheelRect();double cx=(r.left+r.right)/2.0,cy=(r.top+r.bottom)/2.0,rad=(r.right-r.left)/2.0;double dx=p.x-cx,dy=p.y-cy,dist=std::sqrt(dx*dx+dy*dy);st->hsv.s=std::min(1.0,dist/rad);double deg=std::atan2(dy,dx)*180.0/3.14159265358979323846+90.0;while(deg<0)deg+=360;while(deg>=360)deg-=360;st->hsv.h=deg;pickerSyncHex(st);}
static void pickerSetValuePoint(ColorPickerState*st,POINT p){RECT r=pickerValueRect();double t=(p.y-r.top)/(double)std::max<LONG>(1L,r.bottom-r.top);st->hsv.v=std::max(0.0,std::min(1.0,1.0-t));pickerSyncHex(st);}
static void paintColorWheel(HDC dc,ColorPickerState*st){
    RECT wr=pickerWheelRect();const int W=260,H=260;std::vector<uint32_t> pixels((size_t)W*H,0x00F6F7FBu);double cx=(W-1)/2.0,cy=(H-1)/2.0,rad=(W-2)/2.0;
    for(int y=0;y<H;y++)for(int x=0;x<W;x++){double dx=x-cx,dy=y-cy,dist=std::sqrt(dx*dx+dy*dy);if(dist>rad)continue;double sat=dist/rad;double hue=std::atan2(dy,dx)*180.0/3.14159265358979323846+90.0;while(hue<0)hue+=360;pixels[(size_t)y*W+x]=fromHsv(hue,sat,st->hsv.v)&0x00FFFFFFu;}
    BITMAPINFO bi{};bi.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);bi.bmiHeader.biWidth=W;bi.bmiHeader.biHeight=-H;bi.bmiHeader.biPlanes=1;bi.bmiHeader.biBitCount=32;bi.bmiHeader.biCompression=BI_RGB;SetDIBitsToDevice(dc,wr.left,wr.top,W,H,0,0,0,H,pixels.data(),&bi,DIB_RGB_COLORS);
    RECT vr=pickerValueRect();for(int y=vr.top;y<vr.bottom;y++){double v=1.0-(y-vr.top)/(double)std::max<LONG>(1L,vr.bottom-vr.top-1);HPEN p=CreatePen(PS_SOLID,1,rgb(fromHsv(st->hsv.h,st->hsv.s,v)));auto old=SelectObject(dc,p);MoveToEx(dc,vr.left,y,nullptr);LineTo(dc,vr.right,y);SelectObject(dc,old);DeleteObject(p);}FrameRect(dc,&vr,(HBRUSH)GetStockObject(GRAY_BRUSH));
    double a=(st->hsv.h-90.0)*3.14159265358979323846/180.0,mr=rad*st->hsv.s;int mx=(int)std::lround(wr.left+W/2.0+std::cos(a)*mr),my=(int)std::lround(wr.top+H/2.0+std::sin(a)*mr);HPEN black=CreatePen(PS_SOLID,2,RGB(30,30,30)),white=CreatePen(PS_SOLID,2,RGB(255,255,255));auto ob=SelectObject(dc,GetStockObject(HOLLOW_BRUSH)),op=SelectObject(dc,black);Ellipse(dc,mx-7,my-7,mx+7,my+7);SelectObject(dc,white);Ellipse(dc,mx-5,my-5,mx+5,my+5);SelectObject(dc,op);SelectObject(dc,ob);DeleteObject(black);DeleteObject(white);
    int vy=vr.top+(int)std::lround((1.0-st->hsv.v)*(vr.bottom-vr.top));HPEN mark=CreatePen(PS_SOLID,2,RGB(40,40,45));auto om=SelectObject(dc,mark);MoveToEx(dc,vr.left-4,vy,nullptr);LineTo(dc,vr.right+4,vy);SelectObject(dc,om);DeleteObject(mark);
    RECT preview{350,52,406,108};HBRUSH pb=CreateSolidBrush(rgb(fromHsv(st->hsv.h,st->hsv.s,st->hsv.v)));FillRect(dc,&preview,pb);DeleteObject(pb);FrameRect(dc,&preview,(HBRUSH)GetStockObject(GRAY_BRUSH));
    SetBkMode(dc,TRANSPARENT);SetTextColor(dc,RGB(55,58,65));RECT bl{294,318,414,340};DrawTextW(dc,tr(IDS_BRIGHTNESS).c_str(),-1,&bl,DT_CENTER|DT_SINGLELINE);
}
static LRESULT CALLBACK ColorPickerProc(HWND h,UINT m,WPARAM wp,LPARAM lp){
    auto st=(ColorPickerState*)GetWindowLongPtrW(h,GWLP_USERDATA);
    if(m==WM_CREATE){auto cs=(CREATESTRUCTW*)lp;st=(ColorPickerState*)cs->lpCreateParams;SetWindowLongPtrW(h,GWLP_USERDATA,(LONG_PTR)st);HFONT font=(HFONT)GetStockObject(DEFAULT_GUI_FONT);CreateWindowW(L"STATIC",tr(IDS_HEX_COLOUR).c_str(),WS_CHILD|WS_VISIBLE,22,326,88,22,h,nullptr,GetModuleHandleW(nullptr),nullptr);st->hexEdit=CreateWindowExW(WS_EX_CLIENTEDGE,L"EDIT",L"",WS_CHILD|WS_VISIBLE|ES_AUTOHSCROLL,112,322,142,27,h,(HMENU)10,GetModuleHandleW(nullptr),nullptr);HWND ok=CreateWindowW(L"BUTTON",tr(IDS_APPLY).c_str(),WS_CHILD|WS_VISIBLE|BS_DEFPUSHBUTTON,252,360,76,30,h,(HMENU)IDOK,GetModuleHandleW(nullptr),nullptr);HWND cancel=CreateWindowW(L"BUTTON",tr(IDS_CANCEL).c_str(),WS_CHILD|WS_VISIBLE,334,360,76,30,h,(HMENU)IDCANCEL,GetModuleHandleW(nullptr),nullptr);SendMessageW(st->hexEdit,WM_SETFONT,(WPARAM)font,TRUE);SendMessageW(ok,WM_SETFONT,(WPARAM)font,TRUE);SendMessageW(cancel,WM_SETFONT,(WPARAM)font,TRUE);pickerSyncHex(st);return 0;}
    if(!st)return DefWindowProcW(h,m,wp,lp);
    if(m==WM_PAINT){PAINTSTRUCT ps;HDC dc=BeginPaint(h,&ps);RECT c;GetClientRect(h,&c);HBRUSH bg=CreateSolidBrush(RGB(246,247,251));FillRect(dc,&c,bg);DeleteObject(bg);paintColorWheel(dc,st);EndPaint(h,&ps);return 0;}
    if(m==WM_LBUTTONDOWN){POINT p{GET_X_LPARAM(lp),GET_Y_LPARAM(lp)};RECT wr=pickerWheelRect(),vr=pickerValueRect();if(PtInRect(&wr,p)){st->dragWheel=true;pickerSetWheelPoint(st,p);SetCapture(h);InvalidateRect(h,nullptr,FALSE);return 0;}if(PtInRect(&vr,p)){st->dragValue=true;pickerSetValuePoint(st,p);SetCapture(h);InvalidateRect(h,nullptr,FALSE);return 0;}}
    if(m==WM_MOUSEMOVE&&(wp&MK_LBUTTON)){POINT p{GET_X_LPARAM(lp),GET_Y_LPARAM(lp)};if(st->dragWheel){pickerSetWheelPoint(st,p);InvalidateRect(h,nullptr,FALSE);return 0;}if(st->dragValue){pickerSetValuePoint(st,p);InvalidateRect(h,nullptr,FALSE);return 0;}}
    if(m==WM_LBUTTONUP){if(st->dragWheel||st->dragValue){st->dragWheel=st->dragValue=false;ReleaseCapture();return 0;}}
    if(m==WM_COMMAND){int id=LOWORD(wp),code=HIWORD(wp);if(id==10&&code==EN_CHANGE&&!st->updatingHex){uint32_t parsed=parseColor(getText(st->hexEdit),0);if(parsed){st->hsv=toHsv(parsed);InvalidateRect(h,nullptr,FALSE);}return 0;}if(id==IDOK){uint32_t parsed=parseColor(getText(st->hexEdit),fromHsv(st->hsv.h,st->hsv.s,st->hsv.v));st->result=parsed;st->ok=true;st->done=true;DestroyWindow(h);return 0;}if(id==IDCANCEL){st->done=true;DestroyWindow(h);return 0;}}
    if(m==WM_CLOSE){st->done=true;DestroyWindow(h);return 0;}return DefWindowProcW(h,m,wp,lp);
}
static bool chooseColour(uint32_t initial,uint32_t&out){
    static bool reg=false;if(!reg){WNDCLASSEXW wc{sizeof(wc)};wc.lpfnWndProc=ColorPickerProc;wc.hInstance=GetModuleHandleW(nullptr);wc.hCursor=LoadCursor(nullptr,IDC_CROSS);wc.hbrBackground=nullptr;wc.lpszClassName=L"VeilySocialColourPicker";RegisterClassExW(&wc);reg=true;}
    RECT pr{};GetWindowRect(g_hwnd,&pr);int ww=440,hh=430,x=pr.left+(pr.right-pr.left-ww)/2,y=pr.top+(pr.bottom-pr.top-hh)/2;ColorPickerState st;st.hsv=toHsv(initial);st.result=initial;HWND d=CreateWindowExW(WS_EX_DLGMODALFRAME,L"VeilySocialColourPicker",tr(IDS_CHOOSE_COLOUR).c_str(),WS_POPUP|WS_CAPTION|WS_SYSMENU,x,y,ww,hh,g_hwnd,nullptr,GetModuleHandleW(nullptr),&st);if(!d)return false;EnableWindow(g_hwnd,FALSE);ShowWindow(d,SW_SHOW);UpdateWindow(d);MSG msg;while(!st.done&&GetMessageW(&msg,nullptr,0,0)>0){if(!IsDialogMessageW(d,&msg)){TranslateMessage(&msg);DispatchMessageW(&msg);}}EnableWindow(g_hwnd,TRUE);SetForegroundWindow(g_hwnd);if(st.ok){out=st.result;return true;}return false;
}

static fs::path localProfileDir(){ wchar_t p[MAX_PATH]{}; SHGetFolderPathW(nullptr,CSIDL_LOCAL_APPDATA|CSIDL_FLAG_CREATE,nullptr,SHGFP_TYPE_CURRENT,p); fs::path d=fs::path(p)/L"VeilySocial"/L"ProfileDesigner"/L"profiles"; fs::create_directories(d); return d; }
static std::string pathUtf8(const fs::path&p){return u8(p.wstring());}

static std::shared_ptr<Element> findElementRec(const std::shared_ptr<Element>&e,const std::string&id,std::shared_ptr<Element>*parent=nullptr,std::shared_ptr<Element>p=nullptr){ if(!e)return nullptr; if(e->id==id){if(parent)*parent=p;return e;} for(auto&c:e->children){auto f=findElementRec(c,id,parent,e);if(f)return f;} return nullptr; }
static void pushUndo();
static std::shared_ptr<Element> selected(std::shared_ptr<Element>*parent=nullptr){ if(g.pageIndex>=g.doc.pages.size())return nullptr; if(g.selectedId.empty())return g.doc.pages[g.pageIndex].root; return findElementRec(g.doc.pages[g.pageIndex].root,g.selectedId,parent); }

static std::string firstBoxId(){ if(g.pageIndex>=g.doc.pages.size())return {};auto root=g.doc.pages[g.pageIndex].root;for(auto&c:root->children)if(c->type==ElementType::Block)return c->id;return {}; }
static bool isRootBox(const std::shared_ptr<Element>&e){if(!e||e->type!=ElementType::Block)return false;std::shared_ptr<Element>p;findElementRec(g.doc.pages[g.pageIndex].root,e->id,&p);return p==g.doc.pages[g.pageIndex].root;}
static NRect childN(const NRect&p,const RectSpec&q){return{p.x+q.x*p.w,p.y+q.y*p.h,q.width*p.w,q.height*p.h};}
static bool globalRectRec(const std::shared_ptr<Element>&e,const std::string&id,const NRect&p,NRect&out){if(!e)return false;NRect r=childN(p,e->rect);if(e->id==id){out=r;return true;}for(auto&c:e->children)if(globalRectRec(c,id,r,out))return true;return false;}
static NRect globalRectFor(const std::string&id){auto root=g.doc.pages[g.pageIndex].root;if(id.empty()||id==root->id)return{0,0,1,1};NRect out{0,0,1,1};for(auto&c:root->children)if(globalRectRec(c,id,{0,0,1,1},out))return out;return{0,0,1,1};}
static void setCameraTargetForMode(){if(g.mode==EditMode::Foreground&&!g.focusedBoxId.empty()){auto r=globalRectFor(g.focusedBoxId);float mx=r.w*.06f,my=r.h*.06f;float l=std::max(0.0f,r.x-mx),t=std::max(0.0f,r.y-my),rr=std::min(1.0f,r.x+r.w+mx),bb=std::min(1.0f,r.y+r.h+my);g.cameraTarget={l,t,std::max(.02f,rr-l),std::max(.02f,bb-t)};}else g.cameraTarget={0,0,1,1};SetTimer(g_hwnd,77,16,nullptr);}
static void enterBox(const std::string&id){auto e=findElementRec(g.doc.pages[g.pageIndex].root,id);if(!isRootBox(e))return;g.focusedBoxId=id;g.selectedId=id;g.mode=EditMode::Foreground;setCameraTargetForMode();}
static void exitBox(){g.mode=EditMode::Boxes;g.selectedId=!g.focusedBoxId.empty()?g.focusedBoxId:firstBoxId();if(g.selectedId.empty())g.selectedId=g.doc.pages[g.pageIndex].root->id;setCameraTargetForMode();}
static void setMode(EditMode m){if(m==EditMode::Foreground){std::string id=isRootBox(selected())?g.selectedId:(!g.focusedBoxId.empty()?g.focusedBoxId:firstBoxId());if(!id.empty())enterBox(id);return;}g.mode=m;if(m==EditMode::Background)g.selectedId=g.doc.pages[g.pageIndex].root->id;else{if(g.focusedBoxId.empty())g.focusedBoxId=firstBoxId();g.selectedId=!g.focusedBoxId.empty()?g.focusedBoxId:g.doc.pages[g.pageIndex].root->id;}setCameraTargetForMode();}
static std::vector<std::shared_ptr<Element>> depthScope(){std::vector<std::shared_ptr<Element>>v;auto root=g.doc.pages[g.pageIndex].root;if(g.mode==EditMode::Background){for(auto&c:root->children)if(c->type==ElementType::Stamp)v.push_back(c);}else if(g.mode==EditMode::Boxes){for(auto&c:root->children)if(c->type!=ElementType::Stamp)v.push_back(c);}else{auto f=findElementRec(root,g.focusedBoxId);if(f)v=f->children;}std::sort(v.begin(),v.end(),[](auto&a,auto&b){return a->rect.zIndex>b->rect.zIndex;});return v;}
static void normalizeDepth(const std::vector<std::shared_ptr<Element>>&v){for(size_t i=0;i<v.size();++i)v[i]->rect.zIndex=(int32_t)(v.size()-1-i);}
static void moveDepthItem(const std::string&id,int to){auto v=depthScope();int from=-1;for(int i=0;i<(int)v.size();++i)if(v[i]->id==id){from=i;break;}if(from<0||v.empty())return;to=std::max(0,std::min(to,(int)v.size()-1));if(to==from)return;auto item=v[from];v.erase(v.begin()+from);v.insert(v.begin()+to,item);normalizeDepth(v);g.selectedId=id;}

static void pushUndo(){ if(g.applyingControls)return; try{g.undo.push_back(encodeProfileText(g.doc)); if(g.undo.size()>60)g.undo.erase(g.undo.begin()); g.redo.clear();}catch(...){} }
static void restoreSnapshot(const std::string&s){ try{g.doc=decodeProfileText(s); if(g.pageIndex>=g.doc.pages.size())g.pageIndex=0; g.selectedId=g.doc.pages[g.pageIndex].root->id;}catch(...){} }

static void flattenElement(const std::shared_ptr<Element>&e,size_t page,int depth){ if(!e)return; std::wstring label(depth*2,L' '); label += (depth==0?tr(IDS_PAGE_PREFIX):L""); label += w(e->name); g.hierarchy.push_back({label,page,e->id}); for(auto&c:e->children)flattenElement(c,page,depth+1); }
static void rebuildHierarchy(){
    g.hierarchy.clear(); SendMessageW(cHierarchy,CB_RESETCONTENT,0,0);
    for(size_t i=0;i<g.doc.pages.size();++i)flattenElement(g.doc.pages[i].root,i,0);
    int sel=0; for(size_t i=0;i<g.hierarchy.size();++i){SendMessageW(cHierarchy,CB_ADDSTRING,0,(LPARAM)g.hierarchy[i].label.c_str());if(g.hierarchy[i].pageIndex==g.pageIndex&&g.hierarchy[i].elementId==g.selectedId)sel=(int)i;}
    SendMessageW(cHierarchy,CB_SETCURSEL,sel,0);
}

static HWND makeCtl(const wchar_t*cls,const wchar_t*text,DWORD style,int id){ HWND h=CreateWindowExW(0,cls,text,WS_CHILD|WS_VISIBLE|style,0,0,10,10,g_hwnd,(HMENU)(INT_PTR)id,GetModuleHandleW(nullptr),nullptr); propertyControls.push_back(h); return h; }
static HWND labelCtl(const wchar_t*text){return makeCtl(L"STATIC",text,SS_LEFT,-1);}
static void addCombo(HWND h,const std::vector<std::wstring>&items){for(auto&s:items)SendMessageW(h,CB_ADDSTRING,0,(LPARAM)s.c_str());}
static void setupSlider(HWND h,int lo=0,int hi=100){SendMessageW(h,TBM_SETRANGE,TRUE,MAKELPARAM(lo,hi));SendMessageW(h,TBM_SETTICFREQ,10,0);}
static void addTooltip(HWND target,int stringId){if(!cTooltip||!target)return;TOOLINFOW ti{};ti.cbSize=sizeof(ti);ti.uFlags=TTF_IDISHWND|TTF_SUBCLASS;ti.hwnd=g_hwnd;ti.uId=(UINT_PTR)target;ti.hinst=GetModuleHandleW(nullptr);ti.lpszText=MAKEINTRESOURCEW(stringId);SendMessageW(cTooltip,TTM_ADDTOOLW,0,(LPARAM)&ti);}

static void createControls(){
    cSocial=makeCtl(L"BUTTON",L"Social / Network",BS_PUSHBUTTON,ID_SOCIAL);
    cReturnOwn=makeCtl(L"BUTTON",L"Return to My Profile",BS_PUSHBUTTON,ID_RETURN_OWN);
    cHierarchy=makeCtl(WC_COMBOBOXW,L"",CBS_DROPDOWNLIST|WS_VSCROLL,ID_HIERARCHY);
    cName=makeCtl(L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_NAME);
    cSecDepth=makeCtl(L"BUTTON",L"",BS_PUSHBUTTON,ID_SEC_DEPTH);cDepthHelp=makeCtl(L"STATIC",tr(IDS_DEPTH_HELP).c_str(),SS_LEFT, -1);cDepthList=makeCtl(L"LISTBOX",L"",LBS_NOTIFY|WS_BORDER|WS_VSCROLL,ID_DEPTH_LIST);
    cSecMove=makeCtl(L"BUTTON",L"",BS_PUSHBUTTON,ID_SEC_MOVE);
    cX=makeCtl(TRACKBAR_CLASSW,L"",TBS_NOTICKS,ID_X); setupSlider(cX); cY=makeCtl(TRACKBAR_CLASSW,L"",TBS_NOTICKS,ID_Y);setupSlider(cY);
    cW=makeCtl(TRACKBAR_CLASSW,L"",TBS_NOTICKS,ID_W);setupSlider(cW,2,100); cH=makeCtl(TRACKBAR_CLASSW,L"",TBS_NOTICKS,ID_H);setupSlider(cH,2,100); cPageAspect=makeCtl(TRACKBAR_CLASSW,L"",TBS_NOTICKS,ID_PAGE_ASPECT);setupSlider(cPageAspect,20,120);
    cEditContents=makeCtl(L"BUTTON",L"",BS_PUSHBUTTON,ID_EDIT_CONTENTS);
    cSecAppearance=makeCtl(L"BUTTON",L"",BS_PUSHBUTTON,ID_SEC_APPEARANCE);
    cBgKind=makeCtl(WC_COMBOBOXW,L"",CBS_DROPDOWNLIST,ID_BG_KIND);addCombo(cBgKind,{tr(IDS_SOLID),tr(IDS_LINEAR_FADE)});
    cSolidColor=makeCtl(L"EDIT",L"#FFFFFF",WS_BORDER|ES_AUTOHSCROLL,ID_SOLID_COLOR); cSolidPick=makeCtl(L"BUTTON",L"",BS_OWNERDRAW,ID_SOLID_PICK);
    cGSX=makeCtl(TRACKBAR_CLASSW,L"",TBS_NOTICKS,ID_GSX);setupSlider(cGSX);cGSY=makeCtl(TRACKBAR_CLASSW,L"",TBS_NOTICKS,ID_GSY);setupSlider(cGSY);cGEX=makeCtl(TRACKBAR_CLASSW,L"",TBS_NOTICKS,ID_GEX);setupSlider(cGEX);cGEY=makeCtl(TRACKBAR_CLASSW,L"",TBS_NOTICKS,ID_GEY);setupSlider(cGEY);
    cStopSelect=makeCtl(WC_COMBOBOXW,L"",CBS_DROPDOWNLIST,ID_STOP_SELECT); cStopPos=makeCtl(TRACKBAR_CLASSW,L"",TBS_NOTICKS,ID_STOP_POS);setupSlider(cStopPos); cStopColor=makeCtl(L"EDIT",L"#FFFFFF",WS_BORDER,ID_STOP_COLOR); cStopPick=makeCtl(L"BUTTON",L"",BS_OWNERDRAW,ID_STOP_PICK);
    cAddStop=makeCtl(L"BUTTON",tr(IDS_ADD_STOP).c_str(),BS_PUSHBUTTON,ID_ADD_STOP);cRemoveStop=makeCtl(L"BUTTON",tr(IDS_REMOVE_STOP).c_str(),BS_PUSHBUTTON,ID_REMOVE_STOP);
    cBorder=makeCtl(WC_COMBOBOXW,L"",CBS_DROPDOWNLIST,ID_BORDER);addCombo(cBorder,{tr(IDS_NONE),tr(IDS_THIN1),tr(IDS_THICK1),tr(IDS_DOTTED1),tr(IDS_CUSTOM_THIN)});
    cRotation=makeCtl(TRACKBAR_CLASSW,L"",TBS_NOTICKS,ID_ROTATION);setupSlider(cRotation,0,360); cOpacity=makeCtl(TRACKBAR_CLASSW,L"",TBS_NOTICKS,ID_OPACITY);setupSlider(cOpacity,0,100);
    cStamp=makeCtl(WC_COMBOBOXW,L"",CBS_DROPDOWNLIST,ID_STAMP);addCombo(cStamp,{tr(IDS_STAR1),tr(IDS_STAR2),tr(IDS_MOON1),tr(IDS_DOT1),tr(IDS_CUSTOM_STAR)});
    cSecContent=makeCtl(L"BUTTON",L"",BS_PUSHBUTTON,ID_SEC_CONTENT);
    cText=makeCtl(L"EDIT",L"",WS_BORDER|ES_MULTILINE|ES_AUTOVSCROLL,ID_TEXT);
    cTargetType=makeCtl(WC_COMBOBOXW,L"",CBS_DROPDOWNLIST,ID_TARGET_TYPE);addCombo(cTargetType,{tr(IDS_PAGE),tr(IDS_PROFILE),tr(IDS_POST),tr(IDS_COMMUNITY),tr(IDS_DHT),tr(IDS_EXTERNAL)}); cTarget=makeCtl(L"EDIT",L"",WS_BORDER,ID_TARGET);
    cMediaKind=makeCtl(WC_COMBOBOXW,L"",CBS_DROPDOWNLIST,ID_MEDIA_KIND);addCombo(cMediaKind,{tr(IDS_IMAGE),tr(IDS_AUDIO),tr(IDS_VIDEO)}); cMediaTitle=makeCtl(L"EDIT",L"",WS_BORDER,ID_MEDIA_TITLE); cMediaDesc=makeCtl(L"EDIT",L"",WS_BORDER|ES_MULTILINE,ID_MEDIA_DESC);
    cWidgetLabel=makeCtl(L"EDIT",L"",WS_BORDER,ID_WIDGET_LABEL);
    cDelete=makeCtl(L"BUTTON",L"✕",BS_PUSHBUTTON,ID_DELETE);cDuplicate=makeCtl(L"BUTTON",L"⧉",BS_PUSHBUTTON,ID_DUPLICATE);cBackward=makeCtl(L"BUTTON",L"↓",BS_PUSHBUTTON,ID_BACKWARD);cForward=makeCtl(L"BUTTON",L"↑",BS_PUSHBUTTON,ID_FORWARD);
    cSecFile=makeCtl(L"BUTTON",L"",BS_PUSHBUTTON,ID_SEC_FILE);
    cOpen=makeCtl(L"BUTTON",tr(IDS_OPEN).c_str(),BS_PUSHBUTTON,ID_OPEN);cUndo=makeCtl(L"BUTTON",L"↶",BS_PUSHBUTTON,ID_UNDO);cRedo=makeCtl(L"BUTTON",L"↷",BS_PUSHBUTTON,ID_REDO);cRevert=makeCtl(L"BUTTON",tr(IDS_REVERT).c_str(),BS_PUSHBUTTON,ID_REVERT);cSave=makeCtl(L"BUTTON",tr(IDS_SAVE).c_str(),BS_PUSHBUTTON,ID_SAVE);cSaveAs=makeCtl(L"BUTTON",tr(IDS_SAVE_AS).c_str(),BS_PUSHBUTTON,ID_SAVE_AS);cPublish=makeCtl(L"BUTTON",tr(IDS_PUBLISH).c_str(),BS_PUSHBUTTON,ID_PUBLISH);
    cCollapse=CreateWindowExW(0,L"BUTTON",L">",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,26,40,g_hwnd,(HMENU)ID_COLLAPSE,GetModuleHandleW(nullptr),nullptr);
    cPanelScroll=CreateWindowExW(0,L"SCROLLBAR",L"",WS_CHILD|WS_VISIBLE|SBS_VERT,0,0,16,100,g_hwnd,(HMENU)ID_PANEL_SCROLL,GetModuleHandleW(nullptr),nullptr);
    cModeBg=CreateWindowExW(0,L"BUTTON",tr(IDS_MODE_BG).c_str(),WS_CHILD|WS_VISIBLE|BS_OWNERDRAW,0,0,10,10,g_hwnd,(HMENU)ID_MODE_BG,GetModuleHandleW(nullptr),nullptr);
    cModeBoxes=CreateWindowExW(0,L"BUTTON",tr(IDS_MODE_BOXES).c_str(),WS_CHILD|WS_VISIBLE|BS_OWNERDRAW,0,0,10,10,g_hwnd,(HMENU)ID_MODE_BOXES,GetModuleHandleW(nullptr),nullptr);
    cModeFg=CreateWindowExW(0,L"BUTTON",tr(IDS_MODE_FOREGROUND).c_str(),WS_CHILD|WS_VISIBLE|BS_OWNERDRAW,0,0,10,10,g_hwnd,(HMENU)ID_MODE_FG,GetModuleHandleW(nullptr),nullptr);
    cExitBox=CreateWindowExW(0,L"BUTTON",tr(IDS_EXIT_BOX).c_str(),WS_CHILD|BS_PUSHBUTTON,0,0,10,10,g_hwnd,(HMENU)ID_EXIT_BOX,GetModuleHandleW(nullptr),nullptr);
    cTooltip=CreateWindowExW(WS_EX_TOPMOST,TOOLTIPS_CLASSW,nullptr,WS_POPUP|TTS_ALWAYSTIP|TTS_NOPREFIX,CW_USEDEFAULT,CW_USEDEFAULT,CW_USEDEFAULT,CW_USEDEFAULT,g_hwnd,nullptr,GetModuleHandleW(nullptr),nullptr);
    addTooltip(cDelete,IDS_DELETE);addTooltip(cDuplicate,IDS_DUPLICATE);addTooltip(cBackward,IDS_BACK);addTooltip(cForward,IDS_FORWARD);addTooltip(cUndo,IDS_UNDO);addTooltip(cRedo,IDS_REDO);addTooltip(cSolidPick,IDS_COLOUR_WHEEL);addTooltip(cStopPick,IDS_COLOUR_WHEEL);
    HFONT ui=(HFONT)GetStockObject(DEFAULT_GUI_FONT);for(HWND h:propertyControls)SendMessageW(h,WM_SETFONT,(WPARAM)ui,TRUE);SendMessageW(cModeBg,WM_SETFONT,(WPARAM)ui,TRUE);SendMessageW(cModeBoxes,WM_SETFONT,(WPARAM)ui,TRUE);SendMessageW(cModeFg,WM_SETFONT,(WPARAM)ui,TRUE);SendMessageW(cExitBox,WM_SETFONT,(WPARAM)ui,TRUE);
    for(HWND h:{cX,cY,cW,cH,cPageAspect,cGSX,cGSY,cGEX,cGEY,cStopPos,cRotation,cOpacity})SetWindowTheme(h,L"",L"");
}

static void hideAllProps(){for(HWND h:propertyControls)ShowWindow(h,SW_HIDE);}
static void show(HWND h,bool yes=true){ShowWindow(h,yes?SW_SHOW:SW_HIDE);}

static std::wstring sectionTitle(bool open,int id){return std::wstring(open?L"- ":L"+ ")+tr(id);}
static void rebuildDepthList(){SendMessageW(cDepthList,LB_RESETCONTENT,0,0);auto v=depthScope();for(auto&e:v)SendMessageW(cDepthList,LB_ADDSTRING,0,(LPARAM)w(e->name).c_str());int sel=-1;for(int i=0;i<(int)v.size();++i)if(v[i]->id==g.selectedId){sel=i;break;}if(sel>=0)SendMessageW(cDepthList,LB_SETCURSEL,sel,0);}

static void layoutControls(int cw,int ch){
    MoveWindow(cSocial,std::max(8,cw-166),10,150,32,TRUE);
    ShowWindow(cReturnOwn,g.viewingRemote?SW_SHOW:SW_HIDE);
    if(g.viewingRemote) MoveWindow(cReturnOwn,std::max(8,cw-338),10,164,32,TRUE);
    int by=std::max(0,ch-TOOLBAR_H+10);
    MoveWindow(cModeBg,50,by,88,58,TRUE);MoveWindow(cModeBoxes,144,by,88,58,TRUE);MoveWindow(cModeFg,238,by,88,58,TRUE);
    if(g.viewingRemote){
        hideAllProps(); ShowWindow(cPanelScroll,SW_HIDE); ShowWindow(cCollapse,SW_HIDE);
        ShowWindow(cModeBg,SW_HIDE); ShowWindow(cModeBoxes,SW_HIDE); ShowWindow(cModeFg,SW_HIDE); ShowWindow(cExitBox,SW_HIDE);
        ShowWindow(cSocial,SW_SHOW); ShowWindow(cReturnOwn,SW_SHOW); return;
    }
    ShowWindow(cModeBg,SW_SHOW);ShowWindow(cModeBoxes,SW_SHOW);ShowWindow(cModeFg,SW_SHOW);ShowWindow(cCollapse,SW_SHOW);
    ShowWindow(cExitBox,g.mode==EditMode::Foreground?SW_SHOW:SW_HIDE);if(g.mode==EditMode::Foreground)MoveWindow(cExitBox,332,by,94,58,TRUE);
    int panelViewportH=std::max(100,ch-HEADER_H-TOOLBAR_H);
    if(g.panelCollapsed){hideAllProps();ShowWindow(cPanelScroll,SW_HIDE);MoveWindow(cCollapse,cw-45,HEADER_H+8,40,44,TRUE);setText(cCollapse,L"‹");return;}
    ShowWindow(cPanelScroll,SW_SHOW);setText(cCollapse,L"›");MoveWindow(cCollapse,cw-44,HEADER_H+7,38,42,TRUE);MoveWindow(cPanelScroll,cw-18,HEADER_H,18,panelViewportH,TRUE);
    int x=cw-PANEL_W+14,y=HEADER_H+58-g.panelScroll,wid=PANEL_W-46;
    auto row=[&](HWND h,int hh=28){if(!IsWindowVisible(h))return;y+=18;MoveWindow(h,x,y,wid,hh,TRUE);y+=hh+8;};
    auto section=[&](HWND h,bool open,int sid){show(h,true);setText(h,sectionTitle(open,sid));y+=8;MoveWindow(h,x,y,wid,32,TRUE);y+=36;};
    show(cHierarchy,true);show(cName,true);row(cHierarchy,220);y-=172;row(cName);

    section(cSecDepth,g.secDepth,IDS_SECTION_DEPTH);show(cDepthHelp,g.secDepth);show(cDepthList,g.secDepth);if(g.secDepth){MoveWindow(cDepthHelp,x,y,wid,34,TRUE);y+=38;MoveWindow(cDepthList,x,y,wid,150,TRUE);y+=158;}

    section(cSecMove,g.secMove,IDS_SECTION_MOVE_SIZE);
    std::vector<HWND> moveControls={cX,cY,cW,cH,cPageAspect,cEditContents,cDelete,cDuplicate,cBackward,cForward};for(HWND h:moveControls)if(!g.secMove)show(h,false);
    if(g.secMove){row(cX);row(cY);row(cW);row(cH);row(cPageAspect);row(cEditContents);int bw=(wid-12)/4;y+=8;MoveWindow(cDelete,x,y,bw,30,TRUE);MoveWindow(cDuplicate,x+bw+4,y,bw,30,TRUE);MoveWindow(cBackward,x+(bw+4)*2,y,bw,30,TRUE);MoveWindow(cForward,x+(bw+4)*3,y,bw,30,TRUE);y+=38;}

    section(cSecAppearance,g.secAppearance,IDS_SECTION_APPEARANCE);
    std::vector<HWND> appearance={cBgKind,cSolidColor,cSolidPick,cGSX,cGSY,cGEX,cGEY,cStopSelect,cStopPos,cStopColor,cStopPick,cAddStop,cRemoveStop,cBorder,cStamp,cRotation,cOpacity};for(HWND h:appearance)if(!g.secAppearance)show(h,false);
    if(g.secAppearance){row(cBgKind);if(IsWindowVisible(cSolidColor)){y+=18;MoveWindow(cSolidColor,x,y,wid-50,28,TRUE);MoveWindow(cSolidPick,x+wid-42,y-3,42,34,TRUE);y+=36;}row(cGSX);row(cGSY);row(cGEX);row(cGEY);row(cStopSelect,180);if(IsWindowVisible(cStopSelect))y-=132;row(cStopPos);if(IsWindowVisible(cStopColor)){y+=18;MoveWindow(cStopColor,x,y,wid-50,28,TRUE);MoveWindow(cStopPick,x+wid-42,y-3,42,34,TRUE);y+=36;}if(IsWindowVisible(cAddStop)){y+=6;MoveWindow(cAddStop,x,y,(wid-8)/2,30,TRUE);MoveWindow(cRemoveStop,x+(wid+8)/2,y,(wid-8)/2,30,TRUE);y+=38;}row(cBorder);row(cStamp);row(cRotation);row(cOpacity);}

    section(cSecContent,g.secContent,IDS_SECTION_CONTENT);std::vector<HWND> content={cText,cTargetType,cTarget,cMediaKind,cMediaTitle,cMediaDesc,cWidgetLabel};for(HWND h:content)if(!g.secContent)show(h,false);if(g.secContent){row(cText,72);row(cTargetType);row(cTarget);row(cMediaKind);row(cMediaTitle);row(cMediaDesc,58);row(cWidgetLabel);}

    section(cSecFile,g.secFile,IDS_SECTION_FILE);std::vector<HWND> file={cOpen,cUndo,cRedo,cRevert,cSave,cSaveAs,cPublish};for(HWND h:file)show(h,g.secFile);if(g.secFile){int bw=(wid-8)/3;y+=6;MoveWindow(cOpen,x,y,bw,30,TRUE);MoveWindow(cUndo,x+bw+4,y,bw,30,TRUE);MoveWindow(cRedo,x+(bw+4)*2,y,bw,30,TRUE);y+=36;MoveWindow(cRevert,x,y,wid,30,TRUE);y+=36;MoveWindow(cSave,x,y,(wid-4)/2,32,TRUE);MoveWindow(cSaveAs,x+(wid+4)/2,y,(wid-4)/2,32,TRUE);y+=38;MoveWindow(cPublish,x,y,wid,34,TRUE);y+=42;}

    g.panelContentHeight=y+g.panelScroll-HEADER_H+12;int maxScroll=std::max(0,g.panelContentHeight-panelViewportH);if(g.panelScroll>maxScroll){g.panelScroll=maxScroll;layoutControls(cw,ch);return;}SCROLLINFO si{sizeof(si),SIF_RANGE|SIF_PAGE|SIF_POS,0,std::max(panelViewportH,g.panelContentHeight),static_cast<UINT>(panelViewportH),g.panelScroll,0};SetScrollInfo(cPanelScroll,SB_CTL,&si,TRUE);
}

static void rebuildStopCombo(const std::shared_ptr<Element>&e){SendMessageW(cStopSelect,CB_RESETCONTENT,0,0);if(!e)return;auto&st=e->background.stops;if(g.selectedGradientStop>=(int)st.size())g.selectedGradientStop=std::max(0,(int)st.size()-1);auto fmt=tr(IDS_STOP_FORMAT);for(size_t i=0;i<st.size();++i){wchar_t b[96];swprintf_s(b,fmt.c_str(),(unsigned)i+1,(int)std::round(st[i].position*100));SendMessageW(cStopSelect,CB_ADDSTRING,0,(LPARAM)b);}if(!st.empty())SendMessageW(cStopSelect,CB_SETCURSEL,g.selectedGradientStop,0);}

static void syncControls(){
    auto e=selected(); if(!e)return; g.applyingControls=true; rebuildHierarchy(); setText(cName,w(e->name)); SendMessageW(cX,TBM_SETPOS,TRUE,(LPARAM)std::lround(e->rect.x*100));SendMessageW(cY,TBM_SETPOS,TRUE,(LPARAM)std::lround(e->rect.y*100));SendMessageW(cW,TBM_SETPOS,TRUE,(LPARAM)std::lround(e->rect.width*100));SendMessageW(cH,TBM_SETPOS,TRUE,(LPARAM)std::lround(e->rect.height*100));
    bool isRoot=(e==g.doc.pages[g.pageIndex].root);bool foregroundRoot=(g.mode==EditMode::Foreground&&e->id==g.focusedBoxId); show(cX,!isRoot&&!foregroundRoot);show(cY,!isRoot&&!foregroundRoot);show(cW,!isRoot&&!foregroundRoot);show(cH,!isRoot&&!foregroundRoot);show(cPageAspect,isRoot);if(isRoot)SendMessageW(cPageAspect,TBM_SETPOS,TRUE,(LPARAM)std::lround(g.doc.pages[g.pageIndex].aspectRatio*100));
    bool block=e->type==ElementType::Block;bool blockEditable=block&&!foregroundRoot&&(!isRoot||g.mode==EditMode::Background); show(cBgKind,blockEditable);show(cSolidColor,blockEditable&&e->background.kind==BackgroundKind::Solid);show(cSolidPick,blockEditable&&e->background.kind==BackgroundKind::Solid);show(cGSX,blockEditable&&e->background.kind==BackgroundKind::LinearGradient);show(cGSY,blockEditable&&e->background.kind==BackgroundKind::LinearGradient);show(cGEX,blockEditable&&e->background.kind==BackgroundKind::LinearGradient);show(cGEY,blockEditable&&e->background.kind==BackgroundKind::LinearGradient);show(cStopSelect,blockEditable&&e->background.kind==BackgroundKind::LinearGradient);show(cStopPos,blockEditable&&e->background.kind==BackgroundKind::LinearGradient);show(cStopColor,blockEditable&&e->background.kind==BackgroundKind::LinearGradient);show(cStopPick,blockEditable&&e->background.kind==BackgroundKind::LinearGradient);show(cAddStop,blockEditable&&e->background.kind==BackgroundKind::LinearGradient);show(cRemoveStop,blockEditable&&e->background.kind==BackgroundKind::LinearGradient);show(cBorder,blockEditable||e->type==ElementType::Button);
    if(blockEditable){SendMessageW(cBgKind,CB_SETCURSEL,(WPARAM)e->background.kind,0);setText(cSolidColor,colorText(e->background.solidArgb));SendMessageW(cGSX,TBM_SETPOS,TRUE,(LPARAM)std::lround(e->background.startX*100));SendMessageW(cGSY,TBM_SETPOS,TRUE,(LPARAM)std::lround(e->background.startY*100));SendMessageW(cGEX,TBM_SETPOS,TRUE,(LPARAM)std::lround(e->background.endX*100));SendMessageW(cGEY,TBM_SETPOS,TRUE,(LPARAM)std::lround(e->background.endY*100));rebuildStopCombo(e);if(!e->background.stops.empty()){auto&s=e->background.stops[g.selectedGradientStop];SendMessageW(cStopPos,TBM_SETPOS,TRUE,(LPARAM)std::lround(s.position*100));setText(cStopColor,colorText(s.argb));}}
    if(blockEditable){std::wstring n=w(e->border.kind==DecorationKind::Builtin?e->border.builtinName:"Custom");int idx=0;if(n==L"Thin1")idx=1;else if(n==L"Thick1")idx=2;else if(n==L"Dotted1")idx=3;else if(e->border.kind==DecorationKind::DecorationPack)idx=4;SendMessageW(cBorder,CB_SETCURSEL,idx,0);}else if(e->type==ElementType::Button){SendMessageW(cBorder,CB_SETCURSEL,1,0);}
    show(cText,e->type==ElementType::Text);if(e->type==ElementType::Text)setText(cText,w(e->text));
    bool target=e->type==ElementType::Link||e->type==ElementType::Button;show(cTargetType,target);show(cTarget,target);if(target){SendMessageW(cTargetType,CB_SETCURSEL,(WPARAM)e->targetType,0);setText(cTarget,w(e->target));}
    show(cStamp,e->type==ElementType::Stamp);show(cRotation,e->type==ElementType::Stamp);show(cOpacity,e->type==ElementType::Stamp);if(e->type==ElementType::Stamp){int idx=0;auto n=e->stampDecoration.builtinName;if(n=="Star2")idx=1;else if(n=="Moon1")idx=2;else if(n=="Dot1")idx=3;else if(e->stampDecoration.kind==DecorationKind::DecorationPack)idx=4;SendMessageW(cStamp,CB_SETCURSEL,idx,0);SendMessageW(cRotation,TBM_SETPOS,TRUE,(LPARAM)std::lround(e->rotationDegrees));SendMessageW(cOpacity,TBM_SETPOS,TRUE,(LPARAM)std::lround(e->opacity*100));}
    show(cMediaKind,e->type==ElementType::Media);show(cMediaTitle,e->type==ElementType::Media);show(cMediaDesc,e->type==ElementType::Media);if(e->type==ElementType::Media){SendMessageW(cMediaKind,CB_SETCURSEL,(WPARAM)e->mediaKind,0);setText(cMediaTitle,w(e->mediaTitle));setText(cMediaDesc,w(e->mediaDescription));}
    show(cWidgetLabel,e->type==ElementType::Widget);if(e->type==ElementType::Widget)setText(cWidgetLabel,w(e->widgetLabel));
    show(cEditContents,g.mode==EditMode::Boxes&&isRootBox(e));setText(cEditContents,tr(IDS_EDIT_CONTENTS));
    show(cDelete,!isRoot&&!foregroundRoot);show(cDuplicate,!isRoot&&!foregroundRoot);show(cBackward,!isRoot&&!foregroundRoot);show(cForward,!isRoot&&!foregroundRoot);show(cOpen,true);show(cUndo,true);show(cRedo,true);show(cRevert,true);show(cSave,true);show(cSaveAs,true);show(cPublish,true);
    rebuildDepthList();
    SendMessageW(cModeBg,BM_SETSTATE,g.mode==EditMode::Background,0);SendMessageW(cModeBoxes,BM_SETSTATE,g.mode==EditMode::Boxes,0);SendMessageW(cModeFg,BM_SETSTATE,g.mode==EditMode::Foreground,0);InvalidateRect(cModeBg,nullptr,TRUE);InvalidateRect(cModeBoxes,nullptr,TRUE);InvalidateRect(cModeFg,nullptr,TRUE);InvalidateRect(cSolidPick,nullptr,TRUE);InvalidateRect(cStopPick,nullptr,TRUE);
    EnableWindow(cSocial,TRUE); EnableWindow(cReturnOwn,TRUE);
    g.applyingControls=false; RECT rc;GetClientRect(g_hwnd,&rc);layoutControls(rc.right,rc.bottom);InvalidateRect(g_hwnd,nullptr,FALSE);
}

static uint32_t lerpColor(uint32_t a,uint32_t b,float t){auto l=[&](int sh){return (uint32_t)std::lround(((a>>sh)&255)*(1-t)+((b>>sh)&255)*t);};return 0xFF000000|(l(16)<<16)|(l(8)<<8)|l(0);}
static uint32_t gradientColor(const BackgroundSpec&bg,float x,float y){if(bg.kind==BackgroundKind::Solid||bg.stops.empty())return bg.solidArgb;float dx=bg.endX-bg.startX,dy=bg.endY-bg.startY,den=dx*dx+dy*dy;float t=den<0.00001f?0.0f:((x-bg.startX)*dx+(y-bg.startY)*dy)/den;t=clamp01(t);auto stops=bg.stops;std::sort(stops.begin(),stops.end(),[](auto&a,auto&b){return a.position<b.position;});if(t<=stops.front().position)return stops.front().argb;if(t>=stops.back().position)return stops.back().argb;for(size_t i=1;i<stops.size();++i)if(t<=stops[i].position){float d=std::max(0.00001f,stops[i].position-stops[i-1].position);return lerpColor(stops[i-1].argb,stops[i].argb,(t-stops[i-1].position)/d);}return stops.back().argb;}
static void fillBackground(HDC hdc,const RECT&r,const BackgroundSpec&bg){int W=r.right-r.left,H=r.bottom-r.top;if(W<=0||H<=0)return;if(bg.kind==BackgroundKind::Solid){HBRUSH b=CreateSolidBrush(rgb(bg.solidArgb));FillRect(hdc,&r,b);DeleteObject(b);return;}int sw=std::min(W,420),sh=std::min(H,720);std::vector<uint32_t> pixels((size_t)sw*sh);for(int y=0;y<sh;++y)for(int x=0;x<sw;++x){auto c=gradientColor(bg,(x+.5f)/sw,(y+.5f)/sh);pixels[(size_t)y*sw+x]=c&0x00FFFFFFu;}BITMAPINFO bi{};bi.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);bi.bmiHeader.biWidth=sw;bi.bmiHeader.biHeight=-sh;bi.bmiHeader.biPlanes=1;bi.bmiHeader.biBitCount=32;bi.bmiHeader.biCompression=BI_RGB;StretchDIBits(hdc,r.left,r.top,W,H,0,0,sw,sh,pixels.data(),&bi,DIB_RGB_COLORS,SRCCOPY);}
static void drawBorder(HDC hdc,const RECT&r,const DecorationRef&d,float thick){std::string n=d.kind==DecorationKind::DecorationPack?(d.basedOnBuiltin.empty()?"Thin1":d.basedOnBuiltin):d.builtinName;if(n.empty()||n=="None")return;HPEN pen=nullptr;if(n=="Dotted1")pen=CreatePen(PS_DOT,std::max(1,(int)thick),RGB(70,70,80));else pen=CreatePen(PS_SOLID,std::max(1,(int)thick),n=="Thick1"?RGB(40,45,60):RGB(95,100,115));auto old=SelectObject(hdc,pen);auto oldb=SelectObject(hdc,GetStockObject(HOLLOW_BRUSH));Rectangle(hdc,r.left,r.top,r.right,r.bottom);if(n=="Thick1"){RECT q=r;InflateRect(&q,-4,-4);Rectangle(hdc,q.left,q.top,q.right,q.bottom);}SelectObject(hdc,oldb);SelectObject(hdc,old);DeleteObject(pen);}
static void starPoints(std::vector<POINT>&pts,float cx,float cy,float ro,float ri,float rot){pts.resize(10);for(int i=0;i<10;++i){float a=rot-3.14159265f/2+i*3.14159265f/5;float rr=(i%2==0)?ro:ri;pts[i]={(LONG)std::lround(cx+std::cos(a)*rr),(LONG)std::lround(cy+std::sin(a)*rr)};}}
static void drawStamp(HDC hdc,const RECT&r,const Element&e){std::string n=e.stampDecoration.kind==DecorationKind::DecorationPack?(e.stampDecoration.basedOnBuiltin.empty()?"Star1":e.stampDecoration.basedOnBuiltin):e.stampDecoration.builtinName;int W=r.right-r.left,H=r.bottom-r.top;float cx=(r.left+r.right)/2.0f,cy=(r.top+r.bottom)/2.0f;HBRUSH b=CreateSolidBrush(n=="Moon1"?RGB(250,211,90):RGB(120,102,220));HPEN p=CreatePen(PS_SOLID,1,RGB(70,60,140));auto ob=SelectObject(hdc,b),op=SelectObject(hdc,p);if(n=="Dot1")Ellipse(hdc,r.left,r.top,r.right,r.bottom);else if(n=="Moon1"){Ellipse(hdc,r.left,r.top,r.right,r.bottom);HBRUSH cut=CreateSolidBrush(RGB(245,245,250));SelectObject(hdc,cut);Ellipse(hdc,r.left+W/3,r.top-H/10,r.right+W/6,r.bottom-H/10);DeleteObject(cut);}else{std::vector<POINT>pts;starPoints(pts,cx,cy,std::min(W,H)*0.48f,std::min(W,H)*0.21f,e.rotationDegrees*3.14159265f/180.0f);Polygon(hdc,pts.data(),(int)pts.size());}SelectObject(hdc,op);SelectObject(hdc,ob);DeleteObject(p);DeleteObject(b);}

static RECT childRect(const RECT&parent,const RectSpec&q){int pw=parent.right-parent.left,ph=parent.bottom-parent.top;RECT r{parent.left+(LONG)std::lround(q.x*pw),parent.top+(LONG)std::lround(q.y*ph),parent.left+(LONG)std::lround((q.x+q.width)*pw),parent.top+(LONG)std::lround((q.y+q.height)*ph)};return r;}
static void drawElement(HDC hdc,const std::shared_ptr<Element>&e,const RECT&parent){if(!e||!e->rect.visible)return;RECT r=childRect(parent,e->rect);if(e->type==ElementType::Block){fillBackground(hdc,r,e->background);drawBorder(hdc,r,e->border,e->borderThickness);auto sorted=e->children;std::sort(sorted.begin(),sorted.end(),[](auto&a,auto&b){return a->rect.zIndex<b->rect.zIndex;});for(auto&c:sorted)drawElement(hdc,c,r);}
    else if(e->type==ElementType::Stamp)drawStamp(hdc,r,*e);
    else if(e->type==ElementType::Text){SetBkMode(hdc,TRANSPARENT);SetTextColor(hdc,rgb(e->textArgb));int fs=std::max(10,(int)std::lround(e->fontSize));HFONT f=CreateFontW(-fs,0,0,0,e->bold?FW_BOLD:FW_NORMAL,e->italic,0,0,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI");auto of=SelectObject(hdc,f);RECT t=r;UINT flags=DT_WORDBREAK|DT_TOP;if(e->textAlign==TextAlign::Center)flags|=DT_CENTER;else if(e->textAlign==TextAlign::Right)flags|=DT_RIGHT;DrawTextW(hdc,w(e->text).c_str(),-1,&t,flags);SelectObject(hdc,of);DeleteObject(f);}
    else if(e->type==ElementType::Widget){HBRUSH b=CreateSolidBrush(RGB(238,240,244));FillRect(hdc,&r,b);DeleteObject(b);HPEN p=CreatePen(PS_DASH,1,RGB(95,100,115));auto op=SelectObject(hdc,p),ob=SelectObject(hdc,GetStockObject(HOLLOW_BRUSH));Rectangle(hdc,r.left,r.top,r.right,r.bottom);SelectObject(hdc,ob);SelectObject(hdc,op);DeleteObject(p);SetBkMode(hdc,TRANSPARENT);RECT t=r;std::wstring s=w(e->widgetLabel)+L"\n"+tr(IDS_WIDGET_PREVIEW_SUFFIX);DrawTextW(hdc,s.c_str(),-1,&t,DT_CENTER|DT_VCENTER|DT_WORDBREAK);}
    else if(e->type==ElementType::Media){HBRUSH b=CreateSolidBrush(RGB(229,231,235));FillRect(hdc,&r,b);DeleteObject(b);drawBorder(hdc,r,DecorationRef{},1);std::wstring k=e->mediaKind==MediaKind::Image?tr(IDS_IMAGE):e->mediaKind==MediaKind::Audio?tr(IDS_AUDIO):tr(IDS_VIDEO);std::wstring s=k+L"\n"+w(e->mediaTitle)+L"\n"+std::to_wstring(e->intrinsicWidth)+L" x "+std::to_wstring(e->intrinsicHeight)+L"\n"+tr(IDS_MEDIA_PREVIEW_SUFFIX);RECT t=r;SetBkMode(hdc,TRANSPARENT);DrawTextW(hdc,s.c_str(),-1,&t,DT_CENTER|DT_VCENTER|DT_WORDBREAK);}
    else {HBRUSH b=CreateSolidBrush(e->type==ElementType::Button?RGB(236,239,248):RGB(255,255,255));FillRect(hdc,&r,b);DeleteObject(b);drawBorder(hdc,r,e->type==ElementType::Button?e->buttonDecoration:DecorationRef{},1);SetBkMode(hdc,TRANSPARENT);SetTextColor(hdc,e->targetType==LinkTargetType::External?RGB(180,40,45):RGB(55,70,180));RECT t=r;DrawTextW(hdc,w(e->label).c_str(),-1,&t,DT_CENTER|DT_VCENTER|DT_WORDBREAK);}
}

static RECT workspaceRect(){RECT c{};GetClientRect(g_hwnd,&c);return RECT{0,HEADER_H,c.right,std::max<LONG>(HEADER_H,c.bottom-TOOLBAR_H)};}
static RECT inspectorRect(){RECT c{};GetClientRect(g_hwnd,&c);return RECT{std::max<LONG>(0,c.right-PANEL_W),HEADER_H,c.right,std::max<LONG>(HEADER_H,c.bottom-TOOLBAR_H)};}
static RECT gZoomPillRect{};
static RECT viewportRect(){
    RECT ws=workspaceRect();int ww=std::max(100,(int)(ws.right-ws.left)-28),wh=std::max(100,(int)(ws.bottom-ws.top)-28);
    float target=(g.pageIndex<g.doc.pages.size()?g.doc.pages[g.pageIndex].aspectRatio:0.60f);int h=wh,wid=(int)std::lround(h*target);if(wid>ww){wid=ww;h=(int)std::lround(wid/target);}wid=std::max(20,(int)std::lround(wid*g.workspaceZoom));h=std::max(20,(int)std::lround(h*g.workspaceZoom));int cx=(ws.left+ws.right)/2,cy=(ws.top+ws.bottom)/2;return RECT{cx-wid/2,cy-h/2,cx+(wid-wid/2),cy+(h-h/2)};
}
static RECT rootDisplayRect(){RECT vp=viewportRect();int vw=vp.right-vp.left,vh=vp.bottom-vp.top;float cw=std::max(.02f,g.camera.w),ch=std::max(.02f,g.camera.h);return{vp.left-(LONG)std::lround(g.camera.x/cw*vw),vp.top-(LONG)std::lround(g.camera.y/ch*vh),vp.left+(LONG)std::lround((1-g.camera.x)/cw*vw),vp.top+(LONG)std::lround((1-g.camera.y)/ch*vh)};}
static RECT screenRectFromN(const NRect&r){RECT root=rootDisplayRect();int rw=root.right-root.left,rh=root.bottom-root.top;return{root.left+(LONG)std::lround(r.x*rw),root.top+(LONG)std::lround(r.y*rh),root.left+(LONG)std::lround((r.x+r.w)*rw),root.top+(LONG)std::lround((r.y+r.h)*rh)};}
static Hit hitAt(POINT pt){RECT vp=viewportRect();auto root=g.doc.pages[g.pageIndex].root;float nx=g.camera.x+(pt.x-vp.left)/(float)std::max<LONG>(1L,vp.right-vp.left)*g.camera.w;float ny=g.camera.y+(pt.y-vp.top)/(float)std::max<LONG>(1L,vp.bottom-vp.top)*g.camera.h;
    auto mk=[&](const std::shared_ptr<Element>&e,const std::shared_ptr<Element>&p,const NRect&r,const NRect&pr){return Hit{e,p,screenRectFromN(r),g.pageIndex};};
    if(g.mode==EditMode::Background){auto v=depthScope();for(auto&e:v){NRect r=childN({0,0,1,1},e->rect);if(nx>=r.x&&nx<=r.x+r.w&&ny>=r.y&&ny<=r.y+r.h)return mk(e,root,r,{0,0,1,1});}return{root,nullptr,vp,g.pageIndex};}
    if(g.mode==EditMode::Boxes){auto v=depthScope();for(auto&e:v){NRect r=childN({0,0,1,1},e->rect);if(nx>=r.x&&nx<=r.x+r.w&&ny>=r.y&&ny<=r.y+r.h)return mk(e,root,r,{0,0,1,1});}return{root,nullptr,vp,g.pageIndex};}
    auto focus=findElementRec(root,g.focusedBoxId);if(!focus)return{root,nullptr,vp,g.pageIndex};NRect fr=globalRectFor(focus->id);std::vector<std::pair<int,Hit>>hits;std::function<void(const std::shared_ptr<Element>&,const std::shared_ptr<Element>&,const NRect&)>walk=[&](const std::shared_ptr<Element>&e,const std::shared_ptr<Element>&p,const NRect&pr){NRect r=childN(pr,e->rect);if(nx>=r.x&&nx<=r.x+r.w&&ny>=r.y&&ny<=r.y+r.h)hits.push_back({e->rect.zIndex,mk(e,p,r,pr)});for(auto&c:e->children)walk(c,e,r);};for(auto&c:focus->children)walk(c,focus,fr);if(!hits.empty()){std::sort(hits.begin(),hits.end(),[](auto&a,auto&b){return a.first>b.first;});return hits.front().second;}return{focus,root,screenRectFromN(fr),g.pageIndex};}

static std::vector<Tool> allowedTools(){if(g.mode==EditMode::Background)return{Tool::Move,Tool::Stamp,Tool::Page};if(g.mode==EditMode::Boxes)return{Tool::Move,Tool::Block,Tool::Page};return{Tool::Move,Tool::Text,Tool::Link,Tool::Button,Tool::Stamp,Tool::Image,Tool::Widget};}
static const wchar_t* toolGlyph(Tool t){switch(t){case Tool::Move:return L"✥";case Tool::Text:return L"T";case Tool::Block:return L"▭";case Tool::Link:return L"↗";case Tool::Button:return L"▰";case Tool::Stamp:return L"★";case Tool::Image:return L"▧";case Tool::Widget:return L"⌘";case Tool::Page:return L"＋";}return L"?";}
static std::wstring toolLabel(Tool t){switch(t){case Tool::Move:return tr(IDS_TOOL_MOVE);case Tool::Text:return tr(IDS_TOOL_TEXT);case Tool::Block:return tr(IDS_TOOL_BOX);case Tool::Link:return tr(IDS_TOOL_LINK);case Tool::Button:return tr(IDS_TOOL_BUTTON);case Tool::Stamp:return tr(IDS_TOOL_STAMP);case Tool::Image:return tr(IDS_TOOL_MEDIA);case Tool::Widget:return tr(IDS_TOOL_WIDGET);case Tool::Page:return tr(IDS_TOOL_PAGE);}return L"";}
static int toolStripStart(){return g.mode==EditMode::Foreground?450:350;}
static void paintToolbar(HDC hdc,const RECT&client){
    RECT bar{0,client.bottom-TOOLBAR_H,client.right,client.bottom};HBRUSH b=CreateSolidBrush(RGB(255,255,255));FillRect(hdc,&bar,b);DeleteObject(b);HPEN sep=CreatePen(PS_SOLID,1,RGB(218,218,228));auto oldp=SelectObject(hdc,sep);MoveToEx(hdc,0,bar.top,nullptr);LineTo(hdc,bar.right,bar.top);SelectObject(hdc,oldp);DeleteObject(sep);
    SetBkMode(hdc,TRANSPARENT);SetTextColor(hdc,RGB(105,106,120));HFONT small=CreateFontW(-11,0,0,0,FW_SEMIBOLD,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI");auto oldf=SelectObject(hdc,small);RECT el{8,bar.top+5,48,bar.bottom-5};DrawTextW(hdc,tr(IDS_EDIT_LABEL).c_str(),-1,&el,DT_LEFT|DT_VCENTER|DT_SINGLELINE);int start=toolStripStart();RECT tl{start-48,bar.top+5,start-5,bar.bottom-5};DrawTextW(hdc,tr(IDS_TOOLS_LABEL).c_str(),-1,&tl,DT_CENTER|DT_VCENTER|DT_SINGLELINE);SelectObject(hdc,oldf);DeleteObject(small);
    auto tools=allowedTools();int cell=72;int x=start;RECT left{x,bar.top+10,x+28,bar.bottom-10};DrawTextW(hdc,L"‹",-1,&left,DT_CENTER|DT_VCENTER|DT_SINGLELINE);x+=32;int visible=std::max(1,((int)client.right-x-42)/cell);
    HFONT glyph=CreateFontW(-18,0,0,0,FW_BOLD,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI Symbol");HFONT label=CreateFontW(-11,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI");
    for(int i=0;i<visible&&g.toolbarOffset+i<(int)tools.size();++i){Tool t=tools[g.toolbarOffset+i];RECT r{x+i*cell,bar.top+8,x+(i+1)*cell-5,bar.bottom-8};HBRUSH tb=CreateSolidBrush(g.tool==t?RGB(233,229,255):RGB(240,241,247));HPEN tp=CreatePen(PS_SOLID,1,g.tool==t?RGB(190,182,238):RGB(224,225,234));auto ob=SelectObject(hdc,tb),op=SelectObject(hdc,tp);RoundRect(hdc,r.left,r.top,r.right,r.bottom,14,14);SelectObject(hdc,op);SelectObject(hdc,ob);DeleteObject(tp);DeleteObject(tb);RECT gr{r.left,r.top+5,r.right,r.top+32};auto of=SelectObject(hdc,glyph);SetTextColor(hdc,RGB(67,61,105));DrawTextW(hdc,toolGlyph(t),-1,&gr,DT_CENTER|DT_VCENTER|DT_SINGLELINE);SelectObject(hdc,label);RECT lr{r.left+2,r.top+34,r.right-2,r.bottom-3};SetTextColor(hdc,RGB(75,76,88));auto lab=toolLabel(t);DrawTextW(hdc,lab.c_str(),-1,&lr,DT_CENTER|DT_VCENTER|DT_SINGLELINE|DT_END_ELLIPSIS);SelectObject(hdc,of);}
    DeleteObject(glyph);DeleteObject(label);RECT rr{client.right-36,bar.top+10,client.right-6,bar.bottom-10};DrawTextW(hdc,L"›",-1,&rr,DT_CENTER|DT_VCENTER|DT_SINGLELINE);
}
static int toolbarHit(POINT p){RECT c;GetClientRect(g_hwnd,&c);if(p.y<c.bottom-TOOLBAR_H)return -999;int x=toolStripStart();if(p.x<x+28&&p.x>=x)return -1;x+=32;if(p.x>=c.right-36)return -2;int cell=72,visible=std::max(1,((int)c.right-x-42)/cell);int i=(p.x-x)/cell;if(i<0||i>=visible)return -999;auto tools=allowedTools();int at=g.toolbarOffset+i;if(at<0||at>=(int)tools.size())return -999;return 1000+(int)tools[at];}

static bool isSlider(HWND h);
static void manualSlider(HWND h);
static std::wstring sliderValueText(HWND h){int p=(int)SendMessageW(h,TBM_GETPOS,0,0);wchar_t b[48]{};if(h==cRotation)swprintf_s(b,L"%d",p);else swprintf_s(b,L"%.2f",p/100.0);return b;}

static void paintPropertyLabels(HDC hdc){
    if(g.panelCollapsed)return;
    sliderValueRects.clear();
    struct L{HWND h;int id;};
    L labels[]={
        {cHierarchy,IDS_HIERARCHY},{cName,IDS_ITEM_NAME},{cX,IDS_X_POS},{cY,IDS_Y_POS},{cW,IDS_WIDTH},{cH,IDS_HEIGHT},{cPageAspect,IDS_PAGE_ASPECT},
        {cBgKind,IDS_BACKGROUND},{cSolidColor,IDS_SOLID_COLOR},{cGSX,IDS_FADE_START_X},{cGSY,IDS_FADE_START_Y},{cGEX,IDS_FADE_END_X},{cGEY,IDS_FADE_END_Y},
        {cStopSelect,IDS_FADE_STOP},{cStopPos,IDS_STOP_POSITION},{cStopColor,IDS_STOP_COLOR},{cBorder,IDS_BORDER},{cText,IDS_TEXT},
        {cTargetType,IDS_LINK_TYPE},{cTarget,IDS_TARGET},{cStamp,IDS_STAMP},{cRotation,IDS_ROTATION},{cOpacity,IDS_OPACITY},{cMediaKind,IDS_MEDIA_KIND},
        {cMediaTitle,IDS_MEDIA_TITLE},{cMediaDesc,IDS_MEDIA_DESC},{cWidgetLabel,IDS_WIDGET_LABEL}
    };
    SetBkMode(hdc,TRANSPARENT);SetTextColor(hdc,RGB(55,58,65));HFONT f=CreateFontW(-13,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Segoe UI");auto of=SelectObject(hdc,f);
    for(auto&l:labels){
        if(!IsWindowVisible(l.h))continue;RECT wr{};GetWindowRect(l.h,&wr);POINT a{wr.left,wr.top};ScreenToClient(g_hwnd,&a);int width=wr.right-wr.left;auto label=tr(l.id);
        if(isSlider(l.h)){RECT lr{a.x,a.y-18,a.x+std::max(40,width-72),a.y-1};DrawTextW(hdc,label.c_str(),-1,&lr,DT_LEFT|DT_SINGLELINE|DT_END_ELLIPSIS);RECT vr{a.x+std::max(40,width-70),a.y-19,a.x+width,a.y-1};auto val=sliderValueText(l.h);DrawTextW(hdc,val.c_str(),-1,&vr,DT_RIGHT|DT_SINGLELINE);sliderValueRects.push_back({l.h,vr});}
        else TextOutW(hdc,a.x,a.y-16,label.c_str(),(int)label.size());
    }
    SelectObject(hdc,of);DeleteObject(f);
}


static void paint(HDC hdc){
    RECT c{};GetClientRect(g_hwnd,&c);HBRUSH all=CreateSolidBrush(RGB(246,247,251));FillRect(hdc,&c,all);DeleteObject(all);
    // Header
    RECT header{0,0,c.right,HEADER_H};HBRUSH hb=CreateSolidBrush(RGB(255,255,255));FillRect(hdc,&header,hb);DeleteObject(hb);HPEN line=CreatePen(PS_SOLID,1,RGB(225,226,235));auto oldp=SelectObject(hdc,line);MoveToEx(hdc,0,HEADER_H-1,nullptr);LineTo(hdc,c.right,HEADER_H-1);SelectObject(hdc,oldp);DeleteObject(line);
    SetBkMode(hdc,TRANSPARENT);SetTextColor(hdc,RGB(35,36,45));HFONT title=CreateFontW(-17,0,0,0,FW_BOLD,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI");auto oldf=SelectObject(hdc,title);RECT trc{16,6,c.right-180,28};auto app=tr(IDS_APP_TITLE);DrawTextW(hdc,app.c_str(),-1,&trc,DT_LEFT|DT_SINGLELINE|DT_END_ELLIPSIS);SelectObject(hdc,oldf);DeleteObject(title);HFONT sub=CreateFontW(-12,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI");oldf=SelectObject(hdc,sub);SetTextColor(hdc,RGB(105,106,120));std::wstring pageLabel=tr(IDS_PAGE_PREFIX)+(g.pageIndex<g.doc.pages.size()?w(g.doc.pages[g.pageIndex].name):L"");RECT pr{16,29,c.right-180,49};DrawTextW(hdc,pageLabel.c_str(),-1,&pr,DT_LEFT|DT_SINGLELINE|DT_END_ELLIPSIS);SelectObject(hdc,oldf);DeleteObject(sub);
    std::wstring modeText=g.mode==EditMode::Background?tr(IDS_MODE_BG):g.mode==EditMode::Boxes?tr(IDS_MODE_BOXES):tr(IDS_MODE_FOREGROUND);SIZE ms{};HFONT mf=CreateFontW(-12,0,0,0,FW_SEMIBOLD,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI");oldf=SelectObject(hdc,mf);GetTextExtentPoint32W(hdc,modeText.c_str(),(int)modeText.size(),&ms);RECT chip{c.right-ms.cx-42,11,c.right-16,40};HBRUSH cb=CreateSolidBrush(RGB(233,229,255));HPEN cp=CreatePen(PS_SOLID,1,RGB(214,207,250));auto ob=SelectObject(hdc,cb);oldp=SelectObject(hdc,cp);RoundRect(hdc,chip.left,chip.top,chip.right,chip.bottom,18,18);SelectObject(hdc,oldp);SelectObject(hdc,ob);DeleteObject(cp);DeleteObject(cb);SetTextColor(hdc,RGB(65,50,125));DrawTextW(hdc,modeText.c_str(),-1,&chip,DT_CENTER|DT_VCENTER|DT_SINGLELINE);SelectObject(hdc,oldf);DeleteObject(mf);

    RECT ws=workspaceRect();HBRUSH wb=CreateSolidBrush(RGB(246,247,251));FillRect(hdc,&ws,wb);DeleteObject(wb);
    HPEN dot=CreatePen(PS_SOLID,1,RGB(218,220,230));auto od=SelectObject(hdc,dot);for(int x=13;x<ws.right;x+=26)for(int y=ws.top+13;y<ws.bottom;y+=26){MoveToEx(hdc,x,y,nullptr);LineTo(hdc,x+2,y);}SelectObject(hdc,od);DeleteObject(dot);

    RECT vp=viewportRect();RECT visiblePage=vp;IntersectRect(&visiblePage,&visiblePage,&ws);RECT sh=vp;OffsetRect(&sh,5,7);HBRUSH shadow=CreateSolidBrush(RGB(194,196,205));HPEN nullp=(HPEN)GetStockObject(NULL_PEN);oldp=SelectObject(hdc,nullp);ob=SelectObject(hdc,shadow);RoundRect(hdc,sh.left,sh.top,sh.right,sh.bottom,14,14);SelectObject(hdc,ob);SelectObject(hdc,oldp);DeleteObject(shadow);
    int saved=SaveDC(hdc);IntersectClipRect(hdc,ws.left,ws.top,ws.right,ws.bottom);IntersectClipRect(hdc,vp.left,vp.top,vp.right,vp.bottom);RECT rootRect=rootDisplayRect();auto root=g.doc.pages[g.pageIndex].root;fillBackground(hdc,rootRect,root->background);
    std::vector<std::shared_ptr<Element>> stamps,other;for(auto&e:root->children)(e->type==ElementType::Stamp?stamps:other).push_back(e);std::sort(stamps.begin(),stamps.end(),[](auto&a,auto&b){return a->rect.zIndex<b->rect.zIndex;});std::sort(other.begin(),other.end(),[](auto&a,auto&b){return a->rect.zIndex<b->rect.zIndex;});for(auto&e:stamps)drawElement(hdc,e,rootRect);if(g.mode!=EditMode::Background)for(auto&e:other)drawElement(hdc,e,rootRect);RestoreDC(hdc,saved);HPEN pagePen=CreatePen(PS_SOLID,1,RGB(150,151,160));oldp=SelectObject(hdc,pagePen);ob=SelectObject(hdc,GetStockObject(HOLLOW_BRUSH));RoundRect(hdc,vp.left,vp.top,vp.right,vp.bottom,12,12);SelectObject(hdc,ob);SelectObject(hdc,oldp);DeleteObject(pagePen);
    auto sel=selected();if(sel&&sel!=root){bool allowed=(g.mode==EditMode::Background&&sel->type==ElementType::Stamp)||(g.mode==EditMode::Boxes&&findElementRec(root,sel->id)&&sel->type!=ElementType::Stamp)||(g.mode==EditMode::Foreground&&sel->id!=g.focusedBoxId);if(allowed){NRect nr=globalRectFor(sel->id);RECT r=screenRectFromN(nr);g.selectedScreenRect=r;HPEN pen=CreatePen(PS_DASH,2,RGB(48,88,210));oldp=SelectObject(hdc,pen);ob=SelectObject(hdc,GetStockObject(HOLLOW_BRUSH));Rectangle(hdc,r.left-2,r.top-2,r.right+2,r.bottom+2);RECT hh{r.right-7,r.bottom-7,r.right+5,r.bottom+5};HBRUSH sb=CreateSolidBrush(RGB(48,88,210));FillRect(hdc,&hh,sb);DeleteObject(sb);SelectObject(hdc,ob);SelectObject(hdc,oldp);DeleteObject(pen);}}

    // Zoom status pill
    wchar_t zb[256]{};auto zfmt=tr(IDS_ZOOM_HINT);swprintf_s(zb,zfmt.c_str(),(int)std::lround(g.workspaceZoom*100));HFONT zf=CreateFontW(-11,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI");oldf=SelectObject(hdc,zf);SIZE zs{};GetTextExtentPoint32W(hdc,zb,(int)wcslen(zb),&zs);int zw=std::min(430,(int)zs.cx+24);int zx=std::max(8,((int)c.right-zw)/2);gZoomPillRect={zx,HEADER_H+10,zx+zw,HEADER_H+35};HBRUSH zbr=CreateSolidBrush(RGB(255,255,255));HPEN zp=CreatePen(PS_SOLID,1,RGB(222,223,232));ob=SelectObject(hdc,zbr);oldp=SelectObject(hdc,zp);RoundRect(hdc,gZoomPillRect.left,gZoomPillRect.top,gZoomPillRect.right,gZoomPillRect.bottom,18,18);SelectObject(hdc,oldp);SelectObject(hdc,ob);DeleteObject(zp);DeleteObject(zbr);SetTextColor(hdc,RGB(90,91,104));DrawTextW(hdc,zb,-1,&gZoomPillRect,DT_CENTER|DT_VCENTER|DT_SINGLELINE|DT_END_ELLIPSIS);SelectObject(hdc,oldf);DeleteObject(zf);

    paintToolbar(hdc,c);
    if(!g.panelCollapsed){RECT panel=inspectorRect();HBRUSH pb=CreateSolidBrush(RGB(255,255,255));FillRect(hdc,&panel,pb);DeleteObject(pb);HPEN pp=CreatePen(PS_SOLID,1,RGB(222,223,232));oldp=SelectObject(hdc,pp);MoveToEx(hdc,panel.left,panel.top,nullptr);LineTo(hdc,panel.left,panel.bottom);SelectObject(hdc,oldp);DeleteObject(pp);HFONT pf=CreateFontW(-16,0,0,0,FW_BOLD,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI");oldf=SelectObject(hdc,pf);SetTextColor(hdc,RGB(40,41,50));RECT ir{panel.left+14,panel.top+10,panel.right-52,panel.top+32};{auto ins=tr(IDS_INSPECTOR);DrawTextW(hdc,ins.c_str(),-1,&ir,DT_LEFT|DT_SINGLELINE);}SelectObject(hdc,oldf);DeleteObject(pf);auto se=selected();if(se){HFONT sf=CreateFontW(-12,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI");oldf=SelectObject(hdc,sf);SetTextColor(hdc,RGB(110,111,124));RECT sr{panel.left+14,panel.top+32,panel.right-52,panel.top+50};auto nm=w(se->name);DrawTextW(hdc,nm.c_str(),-1,&sr,DT_LEFT|DT_SINGLELINE|DT_END_ELLIPSIS);SelectObject(hdc,oldf);DeleteObject(sf);}paintPropertyLabels(hdc);}
}



static bool renderFrameD3D(){
    if(!g_hwnd) return false;
    RECT rc{}; GetClientRect(g_hwnd,&rc);
    const int width=std::max(1,static_cast<int>(rc.right-rc.left));
    const int height=std::max(1,static_cast<int>(rc.bottom-rc.top));

    if(!g_d3dDevice){
        if(!initD3D(g_hwnd)) return false;
    }

    HRESULT coop=g_d3dDevice->TestCooperativeLevel();
    if(coop==D3DERR_DEVICELOST) return false;
    if(coop==D3DERR_DEVICENOTRESET){
        if(!resetD3D(width,height)) return false;
    }else if(width!=g_d3dWidth || height!=g_d3dHeight){
        if(!resetD3D(width,height)) return false;
    }

    HDC frameDc=nullptr;
    HRESULT hr=g_d3dSystemSurface->GetDC(&frameDc);
    if(FAILED(hr) || !frameDc) return false;
    paint(frameDc);
    g_d3dSystemSurface->ReleaseDC(frameDc);

    hr=g_d3dDevice->UpdateSurface(g_d3dSystemSurface,nullptr,g_d3dGpuSurface,nullptr);
    if(FAILED(hr)) return false;

    IDirect3DSurface9* back=nullptr;
    hr=g_d3dDevice->GetBackBuffer(0,0,D3DBACKBUFFER_TYPE_MONO,&back);
    if(FAILED(hr) || !back) return false;
    hr=g_d3dDevice->StretchRect(g_d3dGpuSurface,nullptr,back,nullptr,D3DTEXF_NONE);
    back->Release();
    if(FAILED(hr)) return false;

    hr=g_d3dDevice->Present(nullptr,nullptr,nullptr,nullptr);
    return SUCCEEDED(hr);
}

static void paintBufferedGDI(HDC target){
    RECT rc{}; GetClientRect(g_hwnd,&rc);
    const int width=std::max(1,static_cast<int>(rc.right-rc.left));
    const int height=std::max(1,static_cast<int>(rc.bottom-rc.top));
    HDC mem=CreateCompatibleDC(target);
    if(!mem){ paint(target); return; }
    HBITMAP bmp=CreateCompatibleBitmap(target,width,height);
    if(!bmp){ DeleteDC(mem); paint(target); return; }
    HGDIOBJ old=SelectObject(mem,bmp);
    paint(mem);
    BitBlt(target,0,0,width,height,mem,0,0,SRCCOPY);
    SelectObject(mem,old);
    DeleteObject(bmp);
    DeleteDC(mem);
}

static std::shared_ptr<Element> targetParent(){auto root=g.doc.pages[g.pageIndex].root;if(g.mode==EditMode::Background||g.mode==EditMode::Boxes)return root;if(g.mode==EditMode::Foreground){auto f=findElementRec(root,g.focusedBoxId);if(!f)return root;auto e=selected();if(e&&e->type==ElementType::Block&&e->id!=root->id)return e;return f;}return root;}
static void addElement(ElementType type){
    if((g.mode==EditMode::Background&&type!=ElementType::Stamp)||(g.mode==EditMode::Boxes&&type!=ElementType::Block))return;
    pushUndo();auto p=targetParent();auto e=std::make_shared<Element>();e->type=type;e->id=makeId("item");e->name=u8(tr(IDS_NEW_ITEM));int nextZ=1;for(auto&c:p->children)nextZ=std::max(nextZ,c->rect.zIndex+1);e->rect={0.12f,0.12f,0.35f,0.14f,nextZ,true};
    if(type==ElementType::Text){e->name=u8(tr(IDS_NEW_TEXT_NAME));e->text=u8(tr(IDS_NEW_TEXT_CONTENT));e->fontSize=18;}else if(type==ElementType::Block){e->name=u8(tr(IDS_NEW_BOX));e->rect.width=.52f;e->rect.height=.30f;e->background.solidArgb=0xDDFFFFFF;e->border.builtinName="Thin1";}else if(type==ElementType::Link){e->name=u8(tr(IDS_NEW_LINK));e->label=u8(tr(IDS_NEW_PAGE_LINK));e->targetType=LinkTargetType::Page;e->target=g.doc.pages[g.pageIndex].id;}else if(type==ElementType::Button){e->name=u8(tr(IDS_NEW_BUTTON));e->label=u8(tr(IDS_NEW_BUTTON));e->buttonDecoration.builtinName="Thin1";}else if(type==ElementType::Stamp){e->name=u8(tr(IDS_NEW_STAMP));e->rect.width=e->rect.height=.10f;e->stampDecoration.builtinName="Star1";e->stampDecoration.basedOnBuiltin="Star1";}else if(type==ElementType::Media){e->name=u8(tr(IDS_NEW_IMAGE));e->rect.width=.48f;e->rect.height=.28f;e->mediaKind=MediaKind::Image;e->mediaTitle=u8(tr(IDS_NEW_IMAGE));}else if(type==ElementType::Widget){e->name=u8(tr(IDS_NEW_WIDGET));e->rect.width=.48f;e->rect.height=.24f;e->widgetLabel=u8(tr(IDS_FUTURE_WIDGET));}
    p->children.push_back(e);g.selectedId=e->id;if(g.mode==EditMode::Boxes&&type==ElementType::Block)g.focusedBoxId=e->id;syncControls();
}
static void addPage(){pushUndo();Page p;p.id=makeId("page");p.name=u8(tr(IDS_NEW_PAGE));p.root=std::make_shared<Element>();p.root->type=ElementType::Block;p.root->id=makeId("root");p.root->name=p.name;p.root->rect={0,0,1,1,0,true};p.root->background.solidArgb=0xFFF7F7F7;g.doc.pages.push_back(p);g.pageIndex=g.doc.pages.size()-1;g.selectedId=p.root->id;g.focusedBoxId.clear();g.mode=EditMode::Boxes;setCameraTargetForMode();syncControls();}

static void msg(const std::wstring&s);
static void msg(const std::wstring&s,const wchar_t*title);
static void reIdTree(const std::shared_ptr<Element>&e){if(!e)return;e->id=makeId("item");for(auto&c:e->children)reIdTree(c);}
static void deleteSelected(){
    auto root=g.doc.pages[g.pageIndex].root;
    if(g.selectedId==root->id){if(g.doc.pages.size()<=1){msg(tr(IDS_MSG_KEEP_PAGE));return;}pushUndo();auto removed=g.doc.pages[g.pageIndex].id;g.doc.pages.erase(g.doc.pages.begin()+g.pageIndex);if(g.doc.defaultPageId==removed)g.doc.defaultPageId=g.doc.pages.front().id;g.pageIndex=std::min(g.pageIndex,g.doc.pages.size()-1);g.selectedId=g.doc.pages[g.pageIndex].root->id;syncControls();return;}
    std::shared_ptr<Element>parent;auto e=selected(&parent);if(!e||!parent)return;pushUndo();parent->children.erase(std::remove_if(parent->children.begin(),parent->children.end(),[&](auto&x){return x->id==e->id;}),parent->children.end());g.selectedId=parent->id;syncControls();
}
static void duplicateSelected(){
    auto root=g.doc.pages[g.pageIndex].root;
    if(g.selectedId==root->id){if(g.doc.pages.size()>=MAX_PAGES)return;pushUndo();Page p=g.doc.pages[g.pageIndex];p.id=makeId("page");p.name+=u8(tr(IDS_COPY_SUFFIX));p.root=cloneElement(p.root);reIdTree(p.root);p.root->name=p.name;g.doc.pages.push_back(p);g.pageIndex=g.doc.pages.size()-1;g.selectedId=p.root->id;syncControls();return;}
    std::shared_ptr<Element>parent;auto e=selected(&parent);if(!e||!parent)return;pushUndo();auto c=cloneElement(e);reIdTree(c);c->name+=u8(tr(IDS_COPY_SUFFIX));c->rect.x=std::min(1.0f-c->rect.width,c->rect.x+.03f);c->rect.y=std::min(1.0f-c->rect.height,c->rect.y+.03f);c->rect.zIndex=e->rect.zIndex+1;parent->children.push_back(c);g.selectedId=c->id;syncControls();
}
static void adjustLayer(int delta){auto e=selected();if(!e||e==g.doc.pages[g.pageIndex].root)return;pushUndo();e->rect.zIndex+=delta;syncControls();}

static fs::path chooseFile(bool save){wchar_t f[MAX_PATH]{};std::wstring filter=tr(IDS_FILE_FILTER_PROFILE);filter.push_back(L'\0');filter+=L"*.txt";filter.push_back(L'\0');filter+=tr(IDS_ALL_FILES);filter.push_back(L'\0');filter+=L"*.*";filter.push_back(L'\0');filter.push_back(L'\0');OPENFILENAMEW of{};of.lStructSize=sizeof(of);of.hwndOwner=g_hwnd;of.lpstrFilter=filter.c_str();of.lpstrFile=f;of.nMaxFile=MAX_PATH;std::wstring dir=g.profileDir.wstring();of.lpstrInitialDir=dir.c_str();of.Flags=OFN_EXPLORER|OFN_PATHMUSTEXIST|(save?OFN_OVERWRITEPROMPT:OFN_FILEMUSTEXIST);of.lpstrDefExt=L"txt";BOOL ok=save?GetSaveFileNameW(&of):GetOpenFileNameW(&of);return ok?fs::path(f):fs::path{};}
static void msg(const std::wstring&s,const wchar_t*title){MessageBoxW(g_hwnd,s.c_str(),title,MB_OK|MB_ICONINFORMATION);}
static void msg(const std::wstring&s){auto t=tr(IDS_APP_TITLE);msg(s,t.c_str());}
static void saveTo(const fs::path&p){try{auto vr=validateProfile(g.doc);if(!vr.ok){msg(tr(IDS_CANNOT_SAVE)+L": "+w(vr.message));return;}saveProfileTextFile(g.doc,pathUtf8(p));g.currentFile=p;g.savedSnapshot=encodeProfileText(g.doc);msg(tr(IDS_MSG_SAVED)+L":\n"+p.wstring());}catch(const std::exception&e){msg(tr(IDS_MSG_SAVE_FAIL)+L": "+w(e.what()));}}
static void doSave(bool as){if(as||g.currentFile.empty()){auto p=chooseFile(true);if(!p.empty())saveTo(p);}else saveTo(g.currentFile);}
static void doOpen(){auto p=chooseFile(false);if(p.empty())return;try{g.doc=loadProfileTextFile(pathUtf8(p));g.currentFile=p;g.savedSnapshot=encodeProfileText(g.doc);g.undo.clear();g.redo.clear();g.pageIndex=0;g.selectedId=g.doc.pages[0].root->id;g.focusedBoxId=firstBoxId();g.mode=EditMode::Boxes;g.camera={0,0,1,1};g.cameraTarget={0,0,1,1};syncControls();}catch(const std::exception&e){msg(tr(IDS_MSG_OPEN_FAIL)+L": "+w(e.what()));}}
static void publishLocal(){try{fs::path p=g.profileDir/L"live_profile.txt";saveProfileTextFile(g.doc,pathUtf8(p));msg(tr(IDS_MSG_PUBLISHED)+L"\n"+p.wstring());}catch(const std::exception&e){msg(tr(IDS_MSG_PUBLISH_FAIL)+L": "+w(e.what()));}}

static void applyControl(int id,bool commit){auto e=selected();if(!e||g.applyingControls)return;if(commit)pushUndo();switch(id){case ID_NAME:e->name=u8(getText(cName));if(e==g.doc.pages[g.pageIndex].root)g.doc.pages[g.pageIndex].name=e->name;break;case ID_BG_KIND:if(e->type==ElementType::Block)e->background.kind=(BackgroundKind)SendMessageW(cBgKind,CB_GETCURSEL,0,0);break;case ID_SOLID_COLOR:e->background.solidArgb=parseColor(getText(cSolidColor),e->background.solidArgb);break;case ID_STOP_COLOR:if(!e->background.stops.empty())e->background.stops[g.selectedGradientStop].argb=parseColor(getText(cStopColor),e->background.stops[g.selectedGradientStop].argb);break;case ID_TEXT:if(e->type==ElementType::Text)e->text=u8(getText(cText));break;case ID_TARGET_TYPE:e->targetType=(LinkTargetType)SendMessageW(cTargetType,CB_GETCURSEL,0,0);break;case ID_TARGET:e->target=u8(getText(cTarget));break;case ID_BORDER:{int k=(int)SendMessageW(cBorder,CB_GETCURSEL,0,0);DecorationRef*d=e->type==ElementType::Button?&e->buttonDecoration:&e->border;if(k==0){d->kind=DecorationKind::Builtin;d->builtinName="None";}else if(k==4){d->kind=DecorationKind::DecorationPack;d->builtinName="";d->packRecordKey="VLD0:future-decoration-pack";d->itemId=1;d->contentHash="future-hash";d->basedOnBuiltin="Thin1";}else{d->kind=DecorationKind::Builtin;d->builtinName=k==1?"Thin1":k==2?"Thick1":"Dotted1";d->basedOnBuiltin=d->builtinName;}break;}case ID_STAMP:{int k=(int)SendMessageW(cStamp,CB_GETCURSEL,0,0);if(k==4){e->stampDecoration.kind=DecorationKind::DecorationPack;e->stampDecoration.packRecordKey="VLD0:future-decoration-pack";e->stampDecoration.itemId=2;e->stampDecoration.contentHash="future-hash";e->stampDecoration.basedOnBuiltin="Star1";}else{e->stampDecoration.kind=DecorationKind::Builtin;e->stampDecoration.builtinName=k==0?"Star1":k==1?"Star2":k==2?"Moon1":"Dot1";e->stampDecoration.basedOnBuiltin=e->stampDecoration.builtinName;}break;}case ID_MEDIA_KIND:e->mediaKind=(MediaKind)SendMessageW(cMediaKind,CB_GETCURSEL,0,0);break;case ID_MEDIA_TITLE:e->mediaTitle=u8(getText(cMediaTitle));break;case ID_MEDIA_DESC:e->mediaDescription=u8(getText(cMediaDesc));break;case ID_WIDGET_LABEL:e->widgetLabel=u8(getText(cWidgetLabel));break;}syncControls();}

static void onSlider(HWND h,UINT code){
    auto e=selected();if(!e||g.applyingControls)return;
    if(!g.sliderEditing||g.sliderEditingHandle!=h){pushUndo();g.sliderEditing=true;g.sliderEditingHandle=h;}
    int v=(int)SendMessageW(h,TBM_GETPOS,0,0);
    if(h==cX)e->rect.x=std::max(0.0f,std::min(1.0f-e->rect.width,v/100.0f));
    else if(h==cY)e->rect.y=std::max(0.0f,std::min(1.0f-e->rect.height,v/100.0f));
    else if(h==cW)e->rect.width=std::max(.02f,std::min(1.0f-e->rect.x,v/100.0f));
    else if(h==cH)e->rect.height=std::max(.02f,std::min(1.0f-e->rect.y,v/100.0f));
    else if(h==cPageAspect)g.doc.pages[g.pageIndex].aspectRatio=std::max(.20f,std::min(1.20f,v/100.0f));
    else if(h==cGSX)e->background.startX=v/100.0f;else if(h==cGSY)e->background.startY=v/100.0f;else if(h==cGEX)e->background.endX=v/100.0f;else if(h==cGEY)e->background.endY=v/100.0f;
    else if(h==cStopPos&&!e->background.stops.empty()){auto originalArgb=e->background.stops[g.selectedGradientStop].argb;float newPos=v/100.0f;e->background.stops[g.selectedGradientStop].position=newPos;std::sort(e->background.stops.begin(),e->background.stops.end(),[](auto&a,auto&b){return a.position<b.position;});for(int i=0;i<(int)e->background.stops.size();++i)if(e->background.stops[i].argb==originalArgb&&std::fabs(e->background.stops[i].position-newPos)<.0001f){g.selectedGradientStop=i;break;}}
    else if(h==cRotation)e->rotationDegrees=(float)v;else if(h==cOpacity)e->opacity=v/100.0f;
    InvalidateRect(g_hwnd,nullptr,FALSE);
    if(code==TB_ENDTRACK){g.sliderEditing=false;g.sliderEditingHandle=nullptr;syncControls();}
}


struct NumberPromptState{bool done=false,ok=false;double value=0,lo=0,hi=100;HWND edit=nullptr;};
static LRESULT CALLBACK NumberPromptProc(HWND h,UINT m,WPARAM wParam,LPARAM lParam){auto st=(NumberPromptState*)GetWindowLongPtrW(h,GWLP_USERDATA);if(m==WM_CREATE){auto cs=(CREATESTRUCTW*)lParam;st=(NumberPromptState*)cs->lpCreateParams;SetWindowLongPtrW(h,GWLP_USERDATA,(LONG_PTR)st);CreateWindowW(L"STATIC",tr(IDS_VALUE).c_str(),WS_CHILD|WS_VISIBLE,14,14,240,20,h,nullptr,GetModuleHandleW(nullptr),nullptr);wchar_t b[64];swprintf_s(b,L"%.3f",st->value);st->edit=CreateWindowExW(WS_EX_CLIENTEDGE,L"EDIT",b,WS_CHILD|WS_VISIBLE|ES_AUTOHSCROLL,14,36,240,26,h,(HMENU)1,GetModuleHandleW(nullptr),nullptr);CreateWindowW(L"BUTTON",tr(IDS_APPLY).c_str(),WS_CHILD|WS_VISIBLE|BS_DEFPUSHBUTTON,96,72,76,28,h,(HMENU)IDOK,GetModuleHandleW(nullptr),nullptr);CreateWindowW(L"BUTTON",tr(IDS_CANCEL).c_str(),WS_CHILD|WS_VISIBLE,178,72,76,28,h,(HMENU)IDCANCEL,GetModuleHandleW(nullptr),nullptr);return 0;}if(!st)return DefWindowProcW(h,m,wParam,lParam);if(m==WM_COMMAND){if(LOWORD(wParam)==IDOK){wchar_t b[64]{};GetWindowTextW(st->edit,b,64);wchar_t*e=nullptr;double v=wcstod(b,&e);if(e&&*e==0){st->value=std::max(st->lo,std::min(st->hi,v));st->ok=true;st->done=true;DestroyWindow(h);}return 0;}if(LOWORD(wParam)==IDCANCEL){st->done=true;DestroyWindow(h);return 0;}}if(m==WM_CLOSE){st->done=true;DestroyWindow(h);return 0;}return DefWindowProcW(h,m,wParam,lParam);}
static bool promptNumber(double current,double lo,double hi,double&out){static bool reg=false;if(!reg){WNDCLASSEXW wc{sizeof(wc)};wc.lpfnWndProc=NumberPromptProc;wc.hInstance=GetModuleHandleW(nullptr);wc.hCursor=LoadCursor(nullptr,IDC_ARROW);wc.hbrBackground=(HBRUSH)(COLOR_WINDOW+1);wc.lpszClassName=L"VeilySocialNumberPrompt";RegisterClassExW(&wc);reg=true;}RECT pr{};GetWindowRect(g_hwnd,&pr);int w=280,h=145,x=pr.left+(pr.right-pr.left-w)/2,y=pr.top+(pr.bottom-pr.top-h)/2;NumberPromptState st;st.value=current;st.lo=lo;st.hi=hi;HWND d=CreateWindowExW(WS_EX_DLGMODALFRAME,L"VeilySocialNumberPrompt",tr(IDS_ENTER_VALUE).c_str(),WS_POPUP|WS_CAPTION|WS_SYSMENU,x,y,w,h,g_hwnd,nullptr,GetModuleHandleW(nullptr),&st);if(!d)return false;EnableWindow(g_hwnd,FALSE);ShowWindow(d,SW_SHOW);UpdateWindow(d);MSG msg;while(!st.done&&GetMessageW(&msg,nullptr,0,0)>0){if(!IsDialogMessageW(d,&msg)){TranslateMessage(&msg);DispatchMessageW(&msg);}}EnableWindow(g_hwnd,TRUE);SetForegroundWindow(g_hwnd);if(st.ok){out=st.value;return true;}return false;}
static bool isSlider(HWND h){for(HWND s:{cX,cY,cW,cH,cPageAspect,cGSX,cGSY,cGEX,cGEY,cStopPos,cRotation,cOpacity})if(h==s)return true;return false;}
static void manualSlider(HWND h){int lo=(int)SendMessageW(h,TBM_GETRANGEMIN,0,0),hi=(int)SendMessageW(h,TBM_GETRANGEMAX,0,0),pos=(int)SendMessageW(h,TBM_GETPOS,0,0);double v=pos;if(h!=cRotation)v=pos/100.0;double dlo=(h!=cRotation)?lo/100.0:lo,dhi=(h!=cRotation)?hi/100.0:hi,out=v;if(promptNumber(v,dlo,dhi,out)){int nv=(h!=cRotation)?(int)std::lround(out*100.0):(int)std::lround(out);SendMessageW(h,TBM_SETPOS,TRUE,nv);onSlider(h,TB_ENDTRACK);}}
static LRESULT customTrackbarDraw(NMCUSTOMDRAW*cd){if(cd->dwDrawStage==CDDS_PREPAINT)return CDRF_NOTIFYITEMDRAW;if(cd->dwDrawStage==CDDS_ITEMPREPAINT){RECT r=cd->rc;HDC dc=cd->hdc;if(cd->dwItemSpec==TBCD_CHANNEL){HBRUSH b=CreateSolidBrush(RGB(223,226,238));HPEN p=CreatePen(PS_SOLID,1,RGB(205,208,220));auto ob=SelectObject(dc,b),op=SelectObject(dc,p);int cy=(r.top+r.bottom)/2;RoundRect(dc,r.left,cy-3,r.right,cy+4,7,7);SelectObject(dc,op);SelectObject(dc,ob);DeleteObject(p);DeleteObject(b);return CDRF_SKIPDEFAULT;}if(cd->dwItemSpec==TBCD_THUMB){HBRUSH b=CreateSolidBrush(RGB(104,82,180));HPEN p=CreatePen(PS_SOLID,1,RGB(88,68,160));auto ob=SelectObject(dc,b),op=SelectObject(dc,p);RoundRect(dc,r.left,r.top,r.right,r.bottom,10,10);SelectObject(dc,op);SelectObject(dc,ob);DeleteObject(p);DeleteObject(b);return CDRF_SKIPDEFAULT;}if(cd->dwItemSpec==TBCD_TICS)return CDRF_SKIPDEFAULT;}return CDRF_DODEFAULT;}
static void drawColourWheelButton(const DRAWITEMSTRUCT*di,uint32_t color){
    HDC dc=di->hDC;RECT r=di->rcItem;HBRUSH bg=CreateSolidBrush(RGB(245,245,250));HPEN edge=CreatePen(PS_SOLID,1,RGB(215,216,226));auto ob=SelectObject(dc,bg),op=SelectObject(dc,edge);RoundRect(dc,r.left,r.top,r.right,r.bottom,12,12);SelectObject(dc,op);SelectObject(dc,ob);DeleteObject(edge);DeleteObject(bg);
    int cx=(int)(r.left+r.right)/2,cy=(int)(r.top+r.bottom)/2,rad=std::max(6,std::min((int)(r.right-r.left),(int)(r.bottom-r.top))/2-5);for(int i=0;i<48;i++){double a0=(i/48.0)*2.0*3.14159265358979323846,a1=((i+1)/48.0)*2.0*3.14159265358979323846;uint32_t hc=fromHsv(i*360.0/48.0,1,1);HPEN p=CreatePen(PS_SOLID,3,rgb(hc));auto old=SelectObject(dc,p);MoveToEx(dc,cx+(int)std::lround(std::cos(a0)*rad),cy+(int)std::lround(std::sin(a0)*rad),nullptr);LineTo(dc,cx+(int)std::lround(std::cos(a1)*rad),cy+(int)std::lround(std::sin(a1)*rad));SelectObject(dc,old);DeleteObject(p);}HBRUSH sw=CreateSolidBrush(rgb(color));HPEN sp=CreatePen(PS_SOLID,1,RGB(80,80,90));ob=SelectObject(dc,sw);op=SelectObject(dc,sp);Ellipse(dc,cx-rad+5,cy-rad+5,cx+rad-5,cy+rad-5);SelectObject(dc,op);SelectObject(dc,ob);DeleteObject(sp);DeleteObject(sw);
}
static void drawModeButton(const DRAWITEMSTRUCT*di,int id){
    bool active=(id==ID_MODE_BG&&g.mode==EditMode::Background)||(id==ID_MODE_BOXES&&g.mode==EditMode::Boxes)||(id==ID_MODE_FG&&g.mode==EditMode::Foreground);HDC dc=di->hDC;RECT r=di->rcItem;HBRUSH b=CreateSolidBrush(active?RGB(233,229,255):RGB(240,241,247));HPEN p=CreatePen(PS_SOLID,1,active?RGB(190,182,238):RGB(225,226,235));auto ob=SelectObject(dc,b),op=SelectObject(dc,p);RoundRect(dc,r.left+1,r.top+1,r.right-1,r.bottom-1,14,14);SelectObject(dc,op);SelectObject(dc,ob);DeleteObject(p);DeleteObject(b);SetBkMode(dc,TRANSPARENT);SetTextColor(dc,active?RGB(65,50,125):RGB(70,72,86));const wchar_t* glyph=id==ID_MODE_BG?L"◩":id==ID_MODE_BOXES?L"▭":L"⌗";HFONT gf=CreateFontW(-17,0,0,0,FW_BOLD,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI Symbol");auto of=SelectObject(dc,gf);RECT gr{r.left,r.top+4,r.right,r.top+27};DrawTextW(dc,glyph,-1,&gr,DT_CENTER|DT_VCENTER|DT_SINGLELINE);SelectObject(dc,of);DeleteObject(gf);HFONT lf=CreateFontW(-11,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI");of=SelectObject(dc,lf);std::wstring txt=id==ID_MODE_BG?tr(IDS_MODE_BG):id==ID_MODE_BOXES?tr(IDS_MODE_BOXES):tr(IDS_MODE_FOREGROUND);RECT lr{r.left+2,r.top+29,r.right-2,r.bottom-3};DrawTextW(dc,txt.c_str(),-1,&lr,DT_CENTER|DT_VCENTER|DT_SINGLELINE|DT_END_ELLIPSIS);SelectObject(dc,of);DeleteObject(lf);
}

static WNDPROC gOldDepthProc=nullptr;
static LRESULT CALLBACK DepthListProc(HWND h,UINT m,WPARAM wParam,LPARAM lParam){
    if(m==WM_LBUTTONDBLCLK){DWORD hit=(DWORD)SendMessageW(h,LB_ITEMFROMPOINT,0,lParam);int idx=LOWORD(hit);auto v=depthScope();if(idx>=0&&idx<(int)v.size()){g.depthArmedId=v[idx]->id;g.selectedId=v[idx]->id;g.depthInvalidDrop=false;g.depthDragging=false;g.depthDragSnapshot=false;rebuildDepthList();SendMessageW(h,LB_SETCURSEL,idx,0);InvalidateRect(g_hwnd,nullptr,FALSE);}return 0;}
    if(m==WM_LBUTTONDOWN&&!g.depthArmedId.empty()){DWORD hit=(DWORD)SendMessageW(h,LB_ITEMFROMPOINT,0,lParam);int idx=LOWORD(hit);auto v=depthScope();if(idx>=0&&idx<(int)v.size()&&v[idx]->id==g.depthArmedId){g.depthDragging=true;g.depthDragSnapshot=false;SetCapture(h);SetCursor(LoadCursor(nullptr,IDC_SIZEALL));return 0;}g.depthArmedId.clear();g.depthInvalidDrop=false;g.depthDragging=false;}
    if(m==WM_MOUSEMOVE&&g.depthDragging&&(wParam&MK_LBUTTON)){POINT p{GET_X_LPARAM(lParam),GET_Y_LPARAM(lParam)};RECT r{};GetClientRect(h,&r);g.depthInvalidDrop=p.x<0||p.x>r.right;if(g.depthInvalidDrop){SetCursor(LoadCursor(nullptr,IDC_NO));InvalidateRect(g_hwnd,nullptr,FALSE);return 0;}SetCursor(LoadCursor(nullptr,IDC_SIZEALL));if(p.y<14)SendMessageW(h,WM_VSCROLL,MAKEWPARAM(SB_LINEUP,0),0);else if(p.y>r.bottom-14)SendMessageW(h,WM_VSCROLL,MAKEWPARAM(SB_LINEDOWN,0),0);DWORD hit=(DWORD)SendMessageW(h,LB_ITEMFROMPOINT,0,MAKELPARAM(std::max<LONG>(0L,p.x),std::max<LONG>(0L,std::min<LONG>(p.y,r.bottom-1))));int idx=LOWORD(hit);auto v=depthScope();int cur=-1;for(int i=0;i<(int)v.size();++i)if(v[i]->id==g.depthArmedId){cur=i;break;}if(idx>=0&&idx<(int)v.size()&&idx!=cur){if(!g.depthDragSnapshot){pushUndo();g.depthDragSnapshot=true;}moveDepthItem(g.depthArmedId,idx);rebuildDepthList();SendMessageW(h,LB_SETCURSEL,idx,0);InvalidateRect(g_hwnd,nullptr,FALSE);}return 0;}
    if(m==WM_LBUTTONUP&&g.depthDragging){ReleaseCapture();g.depthDragging=false;g.depthDragSnapshot=false;g.depthArmedId.clear();g.depthInvalidDrop=false;syncControls();return 0;}
    return CallWindowProcW(gOldDepthProc,h,m,wParam,lParam);
}



static std::string socialOwnProfileText(){
    if(g.viewingRemote && !g.ownEditSnapshot.empty()) return g.ownEditSnapshot;
    try{return encodeProfileText(g.doc);}catch(...){return {};}
}
static std::string socialOwnProfileName(){
    if(g.viewingRemote && !g.ownProfileName.empty()) return g.ownProfileName;
    return g.doc.profileName;
}
static void socialOpenRemote(const std::string& mainDht,const std::string& name,const std::string& profileText){
    try{
        if(!g.viewingRemote){g.ownEditSnapshot=encodeProfileText(g.doc);g.ownProfileName=g.doc.profileName;g.ownPanelCollapsed=g.panelCollapsed;}
        g.doc=decodeProfileText(profileText);g.viewingRemote=true;g.remoteMainDht=mainDht;g.panelCollapsed=true;g.pageIndex=0;
        g.selectedId=g.doc.pages.empty()?std::string{}:g.doc.pages[0].root->id;g.focusedBoxId.clear();g.camera={0,0,1,1};g.cameraTarget=g.camera;g.workspaceZoom=1.0f;
        syncControls();
        std::wstring title=L"Viewing network profile: "+w(name);SetWindowTextW(g_hwnd,title.c_str());
    }catch(const std::exception&e){msg(L"Could not open network profile: "+w(e.what()));}
}
static void returnToOwnProfile(){
    if(!g.viewingRemote)return;
    try{g.doc=decodeProfileText(g.ownEditSnapshot);}catch(...){g.doc=makeDefaultProfile();}
    g.viewingRemote=false;g.remoteMainDht.clear();g.panelCollapsed=g.ownPanelCollapsed;g.pageIndex=0;g.selectedId=g.doc.pages[0].root->id;g.focusedBoxId=firstBoxId();g.mode=EditMode::Boxes;g.camera={0,0,1,1};g.cameraTarget=g.camera;g.workspaceZoom=1.0f;
    auto title=tr(IDS_APP_TITLE);SetWindowTextW(g_hwnd,title.c_str());syncControls();
}

static LRESULT CALLBACK WndProc(HWND hwnd,UINT msgId,WPARAM wp,LPARAM lp){
    switch(msgId){
    case WM_CREATE:{
        g_hwnd=hwnd;InitCommonControls();localizeDefaultDocument(g.doc);g.profileDir=localProfileDir();createControls();gOldDepthProc=(WNDPROC)SetWindowLongPtrW(cDepthList,GWLP_WNDPROC,(LONG_PTR)DepthListProc);
        g.selectedId=g.doc.pages[0].root->id;g.focusedBoxId=firstBoxId();g.savedSnapshot=encodeProfileText(g.doc);rebuildHierarchy();syncControls();initD3D(hwnd);
        vs_social::initialize(hwnd,{socialOwnProfileText,socialOwnProfileName,socialOpenRemote});
        InvalidateRect(hwnd,nullptr,FALSE);return 0;
    }
    case WM_SIZE:{layoutControls(LOWORD(lp),HIWORD(lp));if(g_d3dDevice&&LOWORD(lp)>0&&HIWORD(lp)>0)resetD3D(LOWORD(lp),HIWORD(lp));InvalidateRect(hwnd,nullptr,FALSE);return 0;}
    case WM_GETMINMAXINFO:{auto m=(MINMAXINFO*)lp;RECT work{};SystemParametersInfoW(SPI_GETWORKAREA,0,&work,0);m->ptMinTrackSize.x=std::min<LONG>(620,work.right-work.left);m->ptMinTrackSize.y=std::min<LONG>(460,work.bottom-work.top);return 0;}
    case WM_TIMER:{if(wp==77){auto step=[](float a,float b){return a+(b-a)*.22f;};g.camera.x=step(g.camera.x,g.cameraTarget.x);g.camera.y=step(g.camera.y,g.cameraTarget.y);g.camera.w=step(g.camera.w,g.cameraTarget.w);g.camera.h=step(g.camera.h,g.cameraTarget.h);float d=std::fabs(g.camera.x-g.cameraTarget.x)+std::fabs(g.camera.y-g.cameraTarget.y)+std::fabs(g.camera.w-g.cameraTarget.w)+std::fabs(g.camera.h-g.cameraTarget.h);if(d<.0015f){g.camera=g.cameraTarget;KillTimer(hwnd,77);}InvalidateRect(hwnd,nullptr,FALSE);return 0;}break;}
    case WM_PAINT:{PAINTSTRUCT ps;HDC h=BeginPaint(hwnd,&ps);if(!renderFrameD3D())paintBufferedGDI(h);EndPaint(hwnd,&ps);return 0;}
    case WM_ERASEBKGND:return 1;
    case WM_NOTIFY:{auto hdr=(NMHDR*)lp;if(hdr&&isSlider(hdr->hwndFrom)){if(hdr->code==NM_CUSTOMDRAW)return customTrackbarDraw((NMCUSTOMDRAW*)lp);if(hdr->code==NM_DBLCLK){manualSlider(hdr->hwndFrom);return 0;}}break;}
    case WM_DRAWITEM:{auto di=(DRAWITEMSTRUCT*)lp;if(!di)break;int id=(int)di->CtlID;if(id==ID_MODE_BG||id==ID_MODE_BOXES||id==ID_MODE_FG){drawModeButton(di,id);return TRUE;}if(id==ID_SOLID_PICK||id==ID_STOP_PICK){uint32_t c=0xFFFFFFFFu;auto e=selected();if(e){if(id==ID_SOLID_PICK)c=e->background.solidArgb;else if(!e->background.stops.empty())c=e->background.stops[std::min(g.selectedGradientStop,(int)e->background.stops.size()-1)].argb;}drawColourWheelButton(di,c);return TRUE;}break;}
    case WM_HSCROLL:onSlider((HWND)lp,LOWORD(wp));return 0;
    case WM_VSCROLL:{if((HWND)lp==cPanelScroll){SCROLLINFO si{sizeof(si),SIF_ALL};GetScrollInfo(cPanelScroll,SB_CTL,&si);int pos=g.panelScroll;switch(LOWORD(wp)){case SB_LINEUP:pos-=45;break;case SB_LINEDOWN:pos+=45;break;case SB_PAGEUP:pos-=(int)si.nPage;break;case SB_PAGEDOWN:pos+=(int)si.nPage;break;case SB_THUMBTRACK:case SB_THUMBPOSITION:pos=HIWORD(wp);break;}g.panelScroll=std::max(0,std::min(pos,std::max(0,g.panelContentHeight-(int)si.nPage)));RECT rc;GetClientRect(hwnd,&rc);layoutControls(rc.right,rc.bottom);InvalidateRect(hwnd,nullptr,FALSE);return 0;}break;}
    case WM_MOUSEWHEEL:{POINT p{GET_X_LPARAM(lp),GET_Y_LPARAM(lp)};ScreenToClient(hwnd,&p);int delta=GET_WHEEL_DELTA_WPARAM(wp);RECT ir=inspectorRect();if(!g.panelCollapsed&&PtInRect(&ir,p)){g.panelScroll=std::max(0,g.panelScroll-(delta/WHEEL_DELTA)*60);RECT rc{};GetClientRect(hwnd,&rc);int panelH=std::max(100,(int)(rc.bottom-HEADER_H-TOOLBAR_H));g.panelScroll=std::min(g.panelScroll,std::max(0,g.panelContentHeight-panelH));layoutControls(rc.right,rc.bottom);InvalidateRect(hwnd,nullptr,FALSE);return 0;}RECT ws=workspaceRect();if(PtInRect(&ws,p)){double steps=delta/(double)WHEEL_DELTA;g.workspaceZoom=(float)std::max(.45,std::min(4.0,g.workspaceZoom*std::pow(1.12,steps)));InvalidateRect(hwnd,nullptr,FALSE);return 0;}break;}
    case WM_COMMAND:{
        int id=LOWORD(wp),code=HIWORD(wp);
        if(id==ID_SOCIAL&&code==BN_CLICKED){vs_social::show();return 0;}
        if(id==ID_RETURN_OWN&&code==BN_CLICKED){returnToOwnProfile();return 0;}
        if(g.viewingRemote && id!=ID_HIERARCHY && id!=ID_COLLAPSE) return 0;
        if(id==ID_COLLAPSE){g.panelCollapsed=!g.panelCollapsed;if(!g.panelCollapsed)syncControls();else{RECT r;GetClientRect(hwnd,&r);layoutControls(r.right,r.bottom);InvalidateRect(hwnd,nullptr,FALSE);}return 0;}
        if(id==ID_MODE_BG){g.toolbarOffset=0;setMode(EditMode::Background);syncControls();return 0;}if(id==ID_MODE_BOXES){g.toolbarOffset=0;setMode(EditMode::Boxes);syncControls();return 0;}if(id==ID_MODE_FG){g.toolbarOffset=0;setMode(EditMode::Foreground);syncControls();return 0;}if(id==ID_EXIT_BOX){exitBox();syncControls();return 0;}if(id==ID_EDIT_CONTENTS){auto e=selected();if(isRootBox(e)){enterBox(e->id);syncControls();}return 0;}
        if(id==ID_SEC_DEPTH){g.secDepth=!g.secDepth;syncControls();return 0;}if(id==ID_SEC_MOVE){g.secMove=!g.secMove;syncControls();return 0;}if(id==ID_SEC_APPEARANCE){g.secAppearance=!g.secAppearance;syncControls();return 0;}if(id==ID_SEC_CONTENT){g.secContent=!g.secContent;syncControls();return 0;}if(id==ID_SEC_FILE){g.secFile=!g.secFile;syncControls();return 0;}
        if(id==ID_DEPTH_LIST&&code==LBN_SELCHANGE){int idx=(int)SendMessageW(cDepthList,LB_GETCURSEL,0,0);auto v=depthScope();if(idx>=0&&idx<(int)v.size()){g.selectedId=v[idx]->id;syncControls();}return 0;}
        if(id==ID_HIERARCHY&&code==CBN_SELCHANGE){
            int i=(int)SendMessageW(cHierarchy,CB_GETCURSEL,0,0);
            if(i>=0&&i<(int)g.hierarchy.size()){
                g.pageIndex=g.hierarchy[i].pageIndex;g.selectedGradientStop=0;auto root=g.doc.pages[g.pageIndex].root;g.selectedId=g.hierarchy[i].elementId;auto e=selected();
                if(!e||e==root){g.mode=EditMode::Background;g.selectedId=root->id;g.focusedBoxId=firstBoxId();}
                else{
                    std::shared_ptr<Element>parent;findElementRec(root,e->id,&parent);
                    if(parent==root&&e->type==ElementType::Stamp){g.mode=EditMode::Background;g.selectedId=e->id;}
                    else if(parent==root){g.mode=EditMode::Boxes;g.selectedId=e->id;if(e->type==ElementType::Block)g.focusedBoxId=e->id;}
                    else{auto top=e;auto p=parent;while(p&&p!=root){top=p;findElementRec(root,top->id,&p);}if(top->type==ElementType::Block){g.focusedBoxId=top->id;g.mode=EditMode::Foreground;g.selectedId=e->id;}else{g.mode=EditMode::Boxes;g.selectedId=e->id;}}
                }
                g.depthArmedId.clear();setCameraTargetForMode();syncControls();
            }return 0;
        }
        if(id==ID_STOP_SELECT&&code==CBN_SELCHANGE){g.selectedGradientStop=(int)SendMessageW(cStopSelect,CB_GETCURSEL,0,0);syncControls();return 0;}
        if(id==ID_ADD_STOP&&code==BN_CLICKED){auto e=selected();if(e&&e->type==ElementType::Block&&e->background.stops.size()<MAX_GRADIENT_STOPS){pushUndo();float p=.5f;if(!e->background.stops.empty())p=(e->background.stops.front().position+e->background.stops.back().position)/2;e->background.stops.push_back({p,0xFFFFFFFF});std::sort(e->background.stops.begin(),e->background.stops.end(),[](auto&a,auto&b){return a.position<b.position;});g.selectedGradientStop=(int)e->background.stops.size()/2;syncControls();}return 0;}
        if(id==ID_REMOVE_STOP&&code==BN_CLICKED){auto e=selected();if(e&&e->background.stops.size()>2){pushUndo();e->background.stops.erase(e->background.stops.begin()+std::min(g.selectedGradientStop,(int)e->background.stops.size()-1));g.selectedGradientStop=0;syncControls();}return 0;}
        if((id==ID_SOLID_PICK||id==ID_STOP_PICK)&&code==BN_CLICKED){auto e=selected();if(!e)return 0;uint32_t current=id==ID_SOLID_PICK?e->background.solidArgb:(!e->background.stops.empty()?e->background.stops[std::min(g.selectedGradientStop,(int)e->background.stops.size()-1)].argb:0xFFFFFFFFu);uint32_t picked=current;if(chooseColour(current,picked)){pushUndo();if(id==ID_SOLID_PICK)e->background.solidArgb=picked;else if(!e->background.stops.empty())e->background.stops[std::min(g.selectedGradientStop,(int)e->background.stops.size()-1)].argb=picked;syncControls();}return 0;}
        if(id==ID_DELETE&&code==BN_CLICKED){deleteSelected();return 0;}if(id==ID_DUPLICATE&&code==BN_CLICKED){duplicateSelected();return 0;}if(id==ID_BACKWARD&&code==BN_CLICKED){adjustLayer(-1);return 0;}if(id==ID_FORWARD&&code==BN_CLICKED){adjustLayer(1);return 0;}
        if(id==ID_OPEN&&code==BN_CLICKED){doOpen();return 0;}if(id==ID_SAVE&&code==BN_CLICKED){doSave(false);return 0;}if(id==ID_SAVE_AS&&code==BN_CLICKED){doSave(true);return 0;}if(id==ID_PUBLISH&&code==BN_CLICKED){publishLocal();return 0;}
        if(id==ID_REVERT&&code==BN_CLICKED){if(!g.savedSnapshot.empty()){pushUndo();restoreSnapshot(g.savedSnapshot);g.focusedBoxId=firstBoxId();setMode(EditMode::Boxes);syncControls();}return 0;}if(id==ID_UNDO&&code==BN_CLICKED){if(!g.undo.empty()){g.redo.push_back(encodeProfileText(g.doc));auto ss=g.undo.back();g.undo.pop_back();restoreSnapshot(ss);g.focusedBoxId=firstBoxId();setMode(EditMode::Boxes);syncControls();}return 0;}if(id==ID_REDO&&code==BN_CLICKED){if(!g.redo.empty()){g.undo.push_back(encodeProfileText(g.doc));auto ss=g.redo.back();g.redo.pop_back();restoreSnapshot(ss);g.focusedBoxId=firstBoxId();setMode(EditMode::Boxes);syncControls();}return 0;}
        if((code==CBN_SELCHANGE)&&(id==ID_BG_KIND||id==ID_BORDER||id==ID_TARGET_TYPE||id==ID_STAMP||id==ID_MEDIA_KIND)){applyControl(id,true);return 0;}if((code==EN_KILLFOCUS)&&(id==ID_NAME||id==ID_SOLID_COLOR||id==ID_STOP_COLOR||id==ID_TEXT||id==ID_TARGET||id==ID_MEDIA_TITLE||id==ID_MEDIA_DESC||id==ID_WIDGET_LABEL)){applyControl(id,true);return 0;}
        break;
    }
    case WM_LBUTTONDBLCLK:{POINT p{GET_X_LPARAM(lp),GET_Y_LPARAM(lp)};if(!g.panelCollapsed){RECT ir=inspectorRect();if(PtInRect(&ir,p))break;}for(auto&sv:sliderValueRects)if(PtInRect(&sv.second,p)){manualSlider(sv.first);return 0;}RECT vp=viewportRect();if(g.mode==EditMode::Boxes&&PtInRect(&vp,p)){auto h=hitAt(p);if(isRootBox(h.element)){enterBox(h.element->id);syncControls();return 0;}}break;}
    case WM_LBUTTONDOWN:{
        POINT p{GET_X_LPARAM(lp),GET_Y_LPARAM(lp)};if(PtInRect(&gZoomPillRect,p)){g.workspaceZoom=1.0f;InvalidateRect(hwnd,nullptr,FALSE);return 0;}if(g.viewingRemote)return 0;if((wp&MK_RBUTTON)!=0){RECT ws=workspaceRect();if(PtInRect(&ws,p)){g.zoomGesture=true;g.zoomStartY=p.y;g.zoomStartValue=g.workspaceZoom;g.dragging=g.resizing=false;SetCapture(hwnd);return 0;}}if(!g.panelCollapsed){RECT ir=inspectorRect();if(PtInRect(&ir,p))return 0;}int th=toolbarHit(p);if(th==-1){g.toolbarOffset=std::max(0,g.toolbarOffset-1);InvalidateRect(hwnd,nullptr,FALSE);return 0;}if(th==-2){g.toolbarOffset=std::min(8,g.toolbarOffset+1);InvalidateRect(hwnd,nullptr,FALSE);return 0;}if(th>=1000){g.tool=(Tool)(th-1000);if(g.tool==Tool::Text)addElement(ElementType::Text);else if(g.tool==Tool::Block)addElement(ElementType::Block);else if(g.tool==Tool::Link)addElement(ElementType::Link);else if(g.tool==Tool::Button)addElement(ElementType::Button);else if(g.tool==Tool::Stamp)addElement(ElementType::Stamp);else if(g.tool==Tool::Image)addElement(ElementType::Media);else if(g.tool==Tool::Widget)addElement(ElementType::Widget);else if(g.tool==Tool::Page)addPage();g.tool=Tool::Move;InvalidateRect(hwnd,nullptr,FALSE);return 0;}
        RECT activeVp=viewportRect();if(g.tool==Tool::Move&&PtInRect(&activeVp,p)){auto hit=hitAt(p);g.pageIndex=hit.pageIndex;g.selectedId=hit.element->id;if(g.mode==EditMode::Boxes&&isRootBox(hit.element))g.focusedBoxId=hit.element->id;syncControls();auto e=selected();bool movable=e&&e!=g.doc.pages[g.pageIndex].root&&!(g.mode==EditMode::Foreground&&e->id==g.focusedBoxId);if(movable){NRect nr=globalRectFor(e->id);g.selectedScreenRect=screenRectFromN(nr);RECT handle{g.selectedScreenRect.right-12,g.selectedScreenRect.bottom-12,g.selectedScreenRect.right+8,g.selectedScreenRect.bottom+8};g.resizing=PtInRect(&handle,p);g.dragging=!g.resizing;g.dragStart=p;g.dragOriginal=e->rect;pushUndo();SetCapture(hwnd);}return 0;}break;
    }
    case WM_MOUSEMOVE:{if(g.zoomGesture&&GetCapture()==hwnd){int y=GET_Y_LPARAM(lp);double factor=std::exp((g.zoomStartY-y)/180.0);g.workspaceZoom=(float)std::max(.45,std::min(4.0,g.zoomStartValue*factor));InvalidateRect(hwnd,nullptr,FALSE);return 0;}if((g.dragging||g.resizing)&&GetCapture()==hwnd){auto e=selected();std::shared_ptr<Element>parent;selected(&parent);if(e&&parent){NRect pn=globalRectFor(parent->id);RECT pr=screenRectFromN(pn);float dx=(GET_X_LPARAM(lp)-g.dragStart.x)/(float)std::max(1,(int)(pr.right-pr.left)),dy=(GET_Y_LPARAM(lp)-g.dragStart.y)/(float)std::max(1,(int)(pr.bottom-pr.top));if(g.resizing){e->rect.width=std::max(.02f,std::min(1.0f-g.dragOriginal.x,g.dragOriginal.width+dx));e->rect.height=std::max(.02f,std::min(1.0f-g.dragOriginal.y,g.dragOriginal.height+dy));}else{e->rect.x=std::max(0.0f,std::min(1.0f-e->rect.width,g.dragOriginal.x+dx));e->rect.y=std::max(0.0f,std::min(1.0f-e->rect.height,g.dragOriginal.y+dy));}InvalidateRect(hwnd,nullptr,FALSE);}return 0;}break;}
    case WM_RBUTTONDOWN:{POINT p{GET_X_LPARAM(lp),GET_Y_LPARAM(lp)};if((GetKeyState(VK_LBUTTON)&0x8000)!=0){RECT ws=workspaceRect();if(PtInRect(&ws,p)){if(g.dragging||g.resizing){auto e=selected();if(e)e->rect=g.dragOriginal;g.dragging=g.resizing=false;}g.zoomGesture=true;g.zoomStartY=p.y;g.zoomStartValue=g.workspaceZoom;SetCapture(hwnd);InvalidateRect(hwnd,nullptr,FALSE);return 0;}}break;}
    case WM_LBUTTONUP:if(g.zoomGesture){g.zoomGesture=false;if(GetCapture()==hwnd)ReleaseCapture();return 0;}if(g.dragging||g.resizing){g.dragging=g.resizing=false;ReleaseCapture();syncControls();return 0;}break;
    case WM_RBUTTONUP:if(g.zoomGesture){g.zoomGesture=false;if(GetCapture()==hwnd)ReleaseCapture();return 0;}break;
    case WM_DESTROY:vs_social::shutdown();shutdownD3D();PostQuitMessage(0);return 0;
    }
    return DefWindowProcW(hwnd,msgId,wp,lp);
}

int WINAPI wWinMain(HINSTANCE hi,HINSTANCE,LPWSTR,int show){
    if(auto f=(BOOL(WINAPI*)(DPI_AWARENESS_CONTEXT))GetProcAddress(GetModuleHandleW(L"user32.dll"),"SetProcessDpiAwarenessContext"))f(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    INITCOMMONCONTROLSEX ic{sizeof(ic),ICC_STANDARD_CLASSES|ICC_BAR_CLASSES|ICC_WIN95_CLASSES};InitCommonControlsEx(&ic);
    WNDCLASSEXW wc{sizeof(wc)};wc.style=CS_DBLCLKS;wc.lpfnWndProc=WndProc;wc.hInstance=hi;wc.hCursor=LoadCursor(nullptr,IDC_ARROW);wc.hbrBackground=nullptr;wc.lpszClassName=APP_CLASS;wc.hIcon=LoadIcon(nullptr,IDI_APPLICATION);RegisterClassExW(&wc);
    RECT work{};SystemParametersInfoW(SPI_GETWORKAREA,0,&work,0);int workW=(int)(work.right-work.left),workH=(int)(work.bottom-work.top);int ww=std::min(1180,std::max(620,(int)(workW*.90)));int wh=std::min(900,std::max(460,(int)(workH*.88)));ww=std::min(ww,std::max(320,workW-16));wh=std::min(wh,std::max(320,workH-16));int x=work.left+(workW-ww)/2,y=work.top+(workH-wh)/2;auto title=tr(IDS_APP_TITLE);
    HWND hwnd=CreateWindowExW(0,APP_CLASS,title.c_str(),WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN,x,y,ww,wh,nullptr,nullptr,hi,nullptr);if(!hwnd)return 1;ShowWindow(hwnd,show);UpdateWindow(hwnd);MSG m;while(GetMessageW(&m,nullptr,0,0)>0){TranslateMessage(&m);DispatchMessageW(&m);}return (int)m.wParam;
}
