# Windows C++ build

Native Win32 editor with a Direct3D 9 presentation/back-buffer layer. Native Win32 controls remain in the right-side property panel; the canvas/frame is composed off-screen and presented as a complete frame to avoid flicker during drag/slider updates.

No third-party GUI or graphics runtime is required. `d3d9.h` / `d3d9.lib` come with the Windows SDK installed by the Visual Studio C++ Desktop workload.

Build from a Visual Studio Developer Command Prompt or PowerShell where CMake can find Visual Studio:

```
build_project.bat
```

Output:

```
build\Release\VeilySocialProfileDesigner.exe
```

Clean:

```
clean_project.bat
```

The codec-only target is `vspf_codec_test`.

If Direct3D 9 cannot initialize or is temporarily device-lost, the application uses a double-buffered GDI fallback rather than direct screen painting.

## Hotfix 4 editor notes

The Windows editor now uses Background / Boxes / Foreground workspaces, animated box entry/exit, collapsible property groups, depth drag-reordering and precise numeric entry. The initial window is sized from the desktop work area and trackbars are custom-drawn. User-visible editor text comes from the native `STRINGTABLE` in `src/resources.rc` with IDs in `src/resource.h`, preparing the UI for later localized resource tables.
