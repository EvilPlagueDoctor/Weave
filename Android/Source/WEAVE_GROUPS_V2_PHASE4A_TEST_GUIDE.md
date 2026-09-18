# Weave Groups v2 Phase 4A Test Guide

## 1. Cleanup check

Install versionCode 23. Open Settings and confirm the temporary custody-test toggle/target/status fields from v21/v22 are gone.

## 2. Normal custody/recovery regression

Use the same three-phone public group test that passed Phase 3A:

- A creates a post normally.
- C may accept custody and return a receipt.
- B can recover after being offline.

Expected: no regression in Direct/Mailbox/PublicWitness/custody behavior and recovered events still end in one branch decision.

## 3. Reputation positive evidence

After a valid custody receipt/recovery cycle, copy diagnostics.

Expected under `Groups v2 Reputation + Authority Continuity`:

- custodian peer has positive evidence;
- `RecoveryCanonicalVerified` / `RecoveryContentVerified` log evidence can appear;
- if the same account that holds the receipt later recovers from that custodian, `CustodyReceiptHonored` can appear.

Repeated delivery of the same event should not repeatedly increase score because evidence keys are deduplicated.

## 4. Failure is not misconduct

Turn a custodian off or make a route unavailable, then let recovery try.

Expected:

- send/route failures may be logged;
- no negative reputation evidence is created merely for no response/offline/timeout.

## 5. Authority presence

Open a group while its selected Original/Claim authority app is actively running and exchanging group gossip.

Expected: no continuity warning.

Then leave that authority app offline. The production thresholds are 24h/48h, so the normal build intentionally does not provide a short debug threshold. After ~24h tracked absence, a quiet note should appear. It must explicitly remain non-accusatory and must not change reputation.

## 6. Claim recommendation

After ~48h tracked authority absence, create/retain pending or recent group activity.

Expected:

- `Moderation continuity` card;
- `Create independent Claim` action;
- no automatic Claim.

Tap it and confirm the dialog says the Claim does not replace/revoke Original. After creation the new Claim becomes selected locally and Original remains available in the branch selector.

## 7. Delegated moderator continuity

For a branch with a delegated moderator, leave the branch owner offline but keep the delegated moderator active and producing live group-protocol traffic.

Expected: the branch should not escalate to the continuity Claim prompt merely because the owner is absent.

## 8. Member-only groups

Expected: no Claim action for MembersOnly groups.

## 9. Diagnostics to capture if anything looks wrong

Copy the Weave diagnostic report from the affected phone. The useful sections are:

- `Groups v2 Event Store`
- `Groups v2 Custody + Branch Decisions`
- `Groups v2 Reputation + Authority Continuity`
- the final log tail
