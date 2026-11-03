# Weave 0.12.0-dev5-fix1 (versionCode 50)

Fixes the nine unresolved Kotlin references reported in `GroupScreens.kt` in the dev5 source.

The `publicFallback` state/effect had accidentally been inserted into `GroupDetailScreen`. It uses
`conversationId`, `detail` and `controller`, which are available only in `GroupPostScreen`.
The state and effect now live immediately after the `detail` value in `GroupPostScreen`, where the
post body is rendered. Public fallback is cleared when the selected moderation branch or
auto-show policy changes; it is only loaded for public groups. Group-store revision changes can
re-attempt a missing lookup.

Only two source files change: GroupScreens.kt and app/build.gradle.kts.
No daemon, witness TTL, custody, gossip or persisted moderation schema changes.

The existing Rust warnings and Android extractNativeLibs warning are not build blockers and
are not modified in this patch.

Validation: exact pre/post source comparison, diff apply test, ZIP integrity. Full Android
compilation was unavailable locally. Please run the existing Android release build.
