@echo off
setlocal
cd /d "%~dp0"
echo [VeilySocial Profiles - Windows]
echo Requires: Rust/Cargo, Visual Studio 2022 C++ Desktop workload, and CMake.
echo The Rust bridge supplies VeilKnit/MinHash/gossip networking; the C++ app supplies the Direct3D9 profile editor/viewer.
where cargo >nul 2>nul || (echo ERROR: cargo was not found in PATH. Install Rust from https://rustup.rs/& exit /b 1)
where cmake >nul 2>nul || (echo ERROR: cmake was not found in PATH.& exit /b 1)

echo.
echo [1/3] Building VeilySocial network bridge...
pushd network_bridge
cargo build --release || (popd & exit /b 1)
popd

echo.
echo [2/3] Building C++ profile editor/viewer...
if not exist build mkdir build
cmake -S . -B build -A x64 || exit /b 1
cmake --build build --config Release || exit /b 1

echo.
echo [3/3] Copying network bridge beside executable...
copy /Y "network_bridge\target\release\veilysocial_profile_network_bridge.dll" "build\Release\veilysocial_profile_network_bridge.dll" >nul || exit /b 1

echo.
echo Built: build\Release\VeilySocialProfileDesigner.exe
echo Bridge: build\Release\veilysocial_profile_network_bridge.dll
endlocal
