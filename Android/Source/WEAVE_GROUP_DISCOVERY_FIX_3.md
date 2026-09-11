# Weave Group Discovery Fix 3

Fixed a cache-path bug that could make groups never appear on a user's profile.

The profile viewer can render an already-cached `RemoteProfile` without calling
`ensureProfileAvailable()`. Group ownership/claim advertisements live separately in profile-root
subkey 2, so this meant reopening a cached profile did not necessarily re-read the group directory.

Changes:
- opening any profile now explicitly refreshes that user's group-directory subkey 2;
- `ensureProfileAvailable()` also refreshes the group directory even when the profile document is
  already cached;
- group-directory reads log the advertised-entry count;
- successful group publication logs the creator's local advertised-entry count.

Expected diagnostic examples:
- creator: `published group ...; profile group directory=1`
- viewer: `group directory refresh ABCD...: 1 advertised`
