# VeilySocial Profiles — Profile Designer + Discovery Backbone

This app combines the Profile Designer/Widget Lab with the VeilySocial discovery experiment.
It is intentionally a test bed for the reusable social backbone rather than a finished social-network UI.

## What is combined

- The existing VSPF Profile Designer and Widget Lab remain the page authoring/rendering layer.
- The Business Card experiment's MinHash, clustering, positive/negative similarity, common-word/stuffing penalties, progressive search, gossip, and Recent-50 cache are now used to discover **Profile Pages** instead of business cards.
- The bundled VeilKnit daemon contains the handshake-free gossip transport, blob store, app-root DHT support, peer-candidate feedback, and delegatable short-lived Service Requests.
- Ordinary mail is still private. Service Requests are the deliberately public/delegatable mailbox primitive intended for future widget rendezvous/pseudo-server flows.

## Profile publication model

The full decorated profile is not stored directly in the app-root DHT.

```
VeilySocial app root DHT
  subkey 0 -> ProfilePageRecord
                 |
                 +-- main_dht
                 +-- generation / updated_at
                 +-- search/display name (excluded from MinHash)
                 +-- discovery description
                 +-- skills/interests/tags
                 +-- profile_blob_root
                 +-- profile_sha256_hex
                 +-- profile_bytes
                 +-- VSPF version

profile_blob_root -> exact UTF-8 VSPF profile document in daemon blob store
```

A gossip hint is never enough to render somebody's page. Before opening a discovered profile the client checks:

1. the user's VeilySocial app-root DHT;
2. the ProfilePageRecord binding to that user/root;
3. the blob descriptor and size;
4. SHA-256 of the exact downloaded VSPF text;
5. VSPF decode + validation.

Only then is the page cached as openable.

## Discovery behavior

- Name search is exact/prefix/substring and does **not** enter the MinHash.
- Description + skills/interests/tags feed a 64-slot compact MinHash.
- Up to roughly 10 rotating similarity clusters summarize local knowledge.
- `More like` adds a positive signature.
- `Avoid like` adds a negative signature with adjustable strength.
- Common terms and broad/repetitive stuffing are softly penalized.
- Novelty/exploration can favor results that are relevant but not duplicates of already-selected results.
- Gossip is the fast hint layer; DHT/blob reads are confirmation.
- Peers learned through the social layer are submitted back to VeilKnit as candidates via `recommend_nodes`; the social app cannot bypass daemon verification.

## Recent 50

The Social / Network window/tab shows at most 50 other profiles. The most recently encountered/updated profile is first. As new profiles arrive, old entries fall off the visible Recent list; the bounded discovery cache may retain additional profiles for search/clustering.

## Windows build

Requirements: Rust/Cargo, Visual Studio 2022 Desktop C++ workload, CMake.

1. Build/install the bundled VeilKnit daemon normally.
2. From `Apps\VeilySocialProfile\Windows`, run:

```
build_project.bat
```

That builds:

- `network_bridge` (Rust): VeilKnit SDK + social discovery engine;
- the C++/Direct3D9 profile designer/viewer;
- and copies `veilysocial_profile_network_bridge.dll` beside the EXE.

Output:

```
Windows\build\Release\VeilySocialProfileDesigner.exe
Windows\build\Release\veilysocial_profile_network_bridge.dll
```

The first run uses app id `veilknit.veilysocial.profile.v1`, so approve its newest request in the daemon Applications tab.

## Android build

Requirements: JDK 17+, Android SDK 36, Android Studio/Gradle.

From `Apps\VeilySocialProfile\Android` run `build_project.bat` (Windows) or `./build_project.sh` (Linux/macOS), or open the project in Android Studio.

The Android app binds to the VeilKnit daemon through its AIDL local API and uses the same app id and ProfilePageRecord/gossip wire format as Windows.

## Widget networking / Service Requests

The bundled daemon exposes Service Requests to applications, and `Android/DaemonClient.kt` includes wrappers for publishing, withdrawing, subscribing, and replying to them. The widget bytecode VM itself is still sandboxed and does **not** receive arbitrary network/mailbox access in this build.

The intended next layer is a host-brokered widget capability:

```
widget manifest says it supports service X
        -> host asks/uses page policy
        -> host derives opaque service id from app/protocol/source hash
        -> daemon publishes/subscribes short-lived ServiceRequest
        -> host confirms matching widget/source/instance
        -> normal authenticated direct session (or spectator stream)
```

That preserves the widget sandbox while enabling the chess/lobby/pseudo-server design.

## Reusable backbone

The Rust reusable discovery crate is under:

```
Shared/veilysocial_backbone
```

The Android/Kotlin protocol/backbone implementation is under:

```
Android/app/src/main/java/com/veilysocial/profiledesigner/backbone/SocialBackbone.kt
```

The old Business Card application is retained as an example under `Examples/VeilyBusinessCard` in the full package.
