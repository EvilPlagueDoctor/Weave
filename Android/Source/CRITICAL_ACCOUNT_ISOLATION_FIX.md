# Weave 0.12.0-dev9 — Critical account isolation security fix (experimental)

**Security severity: high.** Do not ship without two-account tests and a native release build.

Failure paths corrected:
1. The foreground service no longer overwrites the expected username before the Rust daemon accepts a new start request. It rejects a second start while a native daemon is running or starting.
2. Android compares the expected username to the authenticated Rust LocalApiContext username before accepting `[GUI] ready` or opening an app session. Fail closed on mismatch.
3. Credentials are indexed by Rust-authenticated username *and* network-profile ID; two logins both called `default` no longer share a bootstrap credential slot. Existing legacy credential slots remain untouched; first login may recover/re-register bundled app credential.
4. PrivateVault UI account identity is the authenticated main DHT plus network-profile ID, not just the profile ID `default`. This prevents the editor's same-account optimization from reusing another login's in-memory profile. This string is ONLY a comparison token; actual daemon storage encryption is unchanged.
5. The editor wipes its previous decrypted working document before reading a different account (including error paths).
6. Social UI never renders while Weave is disconnected, and old identity and profile metadata are cleared at disconnect/reconnect.

Version: 0.12.0-dev9-security-account-isolation-fix1 (versionCode 55).

No existing account, backup, vault, or DHT record is deleted. No changes to mailbox wire format.

**Important:** the supplied logcat was dominated by Android OS logs and did not prove the exact failing call path. This source patch addresses independently verified vulnerabilities matching the symptom.

Manual security regression checks (same physical Android phone):
- Unlock account A and confirm its main DHT and profile. Stop Safely, wait for complete shutdown. Create a genuinely new account B: verify different main DHT, no A profile/name/layout/group/credential.
- Switch B -> A -> B with restarts and background/foreground. Confirm no cross-account UI flash.
- While native A is still running/shutting down, issue another startup attempt for B: must show 'Account switch blocked', NEVER show A as B.
- Kill app during daemon shutdown then reopen; no stale account should appear while disconnected.
- Same-account daemon reconnect should keep correct in-memory working profile.
- Verify both accounts' original encrypted backups still work.

Android/Rust build has NOT been run in this environment. Build and test without wiping application data; stop distribution of affected builds pending verification.
