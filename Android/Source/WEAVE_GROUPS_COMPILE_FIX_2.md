# Weave Groups Compile Fix 2

Fixed the JVM signature clash in `WeaveNavState`.

`var mode` automatically generates a JVM setter named `setMode(BrowseMode)`. The class also
declared `fun setMode(BrowseMode)`, which produced the same JVM signature.

The explicit navigation method is now named `selectMode(...)`, and all call sites were updated.
