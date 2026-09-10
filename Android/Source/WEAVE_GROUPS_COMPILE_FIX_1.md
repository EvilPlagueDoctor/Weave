# Weave Groups v2 - Compile Fix 1

Fixes the Kotlin compile error in `GroupStore.kt` where `preferredMode` used a delegated
`mutableStateOf` property together with a custom setter. Kotlin does not allow custom accessors
on delegated properties.

The property now uses an explicit `MutableState` backing value and a normal Kotlin property
getter/setter. The setter still persists the selected People/Groups mode to `PrivateVault`.

No group protocol behavior was intentionally changed in this revision.
