# VeilySocial Profiles merged app — major pieces

## Reusable discovery/network layer
- `Shared/veilysocial_backbone/` — MinHash, clustering, positive/negative similarity, name search, gossip summaries, recent-profile cache, and profile-page records.

## Windows
- `Windows/network_bridge/` — Rust DLL bridging the designer to the VeilKnit daemon and reusable backbone.
- `Windows/src/social_network.cpp`
- `Windows/src/social_network.h`
- `Windows/src/main.cpp` — designer/viewer integration and read-only remote-profile mode.

## Android
- `Android/app/src/main/java/com/veilysocial/profiledesigner/backbone/SocialBackbone.kt`
- `Android/app/src/main/java/com/veilysocial/profiledesigner/SocialNetworkController.kt`
- `Android/app/src/main/java/com/veilysocial/profiledesigner/SocialNetworkScreen.kt`
- `Android/app/src/main/java/com/veilysocial/profiledesigner/DaemonClient.kt`
- `Android/app/src/main/java/com/veilysocial/profiledesigner/ProfileEditorApp.kt`

## Profile publication
A decorated VSPF document is stored through the daemon blob store. The user's app-root DHT contains a compact `ProfilePageRecord` containing the discovery metadata, profile generation, blob root/id, byte length, and SHA-256. Gossip carries hints only; a remote page is rendered only after DHT + blob + hash + VSPF checks.

## Delegatable service requests
The bundled daemon contains the short-lived public/delegatable `ServiceRequest` mailbox primitive added in the previous daemon revision. The Android host client exposes publish/subscribe/reply/withdraw operations. Widgets themselves still do not receive raw daemon networking access; that should be brokered by the widget host/permission system.
