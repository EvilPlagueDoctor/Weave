# Weave Phase 6.11.7 — account backup + group continuity

This pass adds two requested quality-of-life changes and repairs a Groups-v2 authority-discovery race exposed by the September 25 poster/claimer test.

## 1. Account backup from regular Weave Settings

`Settings -> This identity` now includes **Setup account backup**.

The button opens the embedded VeilKnit management UI directly on its existing **Backup** tab, with advanced view enabled for that route. This reuses the daemon's account/identity backup implementation rather than creating a second Weave-only backup format.

The new Settings text is routed through Weave's shared language table so it follows the language shared by Weave and the embedded VeilKnit UI.

## 2. Loading state when opening an unfetched group

When Weave has a group/search-directory hint but has not yet fetched the selected branch header or Pulse, the Group screen now shows a centered progress spinner and an explicit "Fetching group information…" message.

The screen immediately requests the selected branch (or the original branch as a fallback) and waits for the first header/Pulse. The loading state has a bounded ~15 second wait; if the remote branch is unavailable, Weave falls back to the cached group information rather than leaving an infinite spinner.

## 3. Where the missing group post went

The test post was not discarded. The author's device successfully:

- wrote the post payload,
- created and signed a valid Groups-v2 `PostSubmitted` event,
- recorded it as Pending for the Original branch,
- selected custody peers,
- and received a verified custody receipt from the device that owned the new Claim branch.

The claimer/device also validated and retained that exact event in its custody store. However, it did not insert it into its normal Groups-v2 Event Store / moderation path. This left the claimer with `custody=1` but `stored events=0`.

There was a timing race as well: when the post was authored, the posting device knew the dead Original branch but had not yet DHT-verified the new Claim. It learned the Claim roughly two minutes later. Existing code did not revisit already-Pending local submissions when a new Claim became known.

The author's profile was still unpublished when the post was created; it was published later. Profile publication therefore was not the cause of this loss-of-moderation-delivery test. The canonical group event is authenticated with the VeilKnit/main-DHT identity and the post payload has its own DHT reference.

## 4. Groups-v2 continuity repairs

Two complementary repairs cover both sides of the race.

### Custodian is also an authority

When a valid custody request arrives and this node owns a moderation branch for that same group, the canonical event is now promoted into the ordinary authenticated Groups-v2 event pipeline after it is retained in custody.

Diagnostic marker:

```
[groups-v2] CUSTODY_AUTHORITY_PROMOTE ...
```

This means a claimer selected as a custodian can place the submission into its own moderation/event state even if the author's device did not yet know that claim existed.

### Sender learns a Claim later

When a previously unknown, verified Claim branch is first loaded, Weave now finds recent unexpired valid local submissions for that group which do not yet have state for that branch. It marks the Claim branch Pending and sends one bounded canonical copy to the newly discovered claimer.

Diagnostic marker:

```
[groups-v2] NEW_CLAIM_REDELIVERY ...
```

Branch-state persistence makes this idempotent for that Claim, and the pass is capped at 16 recent events per newly discovered claim to avoid an unbounded replay burst.

Member-only groups remain excluded from this automatic new-claim redelivery path until membership-addressable authority delivery can be done without weakening the private-group model.

## Version

- `versionCode = 35`
- `versionName = 0.10.4-backup-group-continuity`
