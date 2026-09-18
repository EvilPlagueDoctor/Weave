# Weave v12 — Group gossip, moderation intake and diagnostics

## What changed

### Group branch selection no longer falls back to Original during refresh
Weave now distinguishes an automatic/default branch choice from an explicit person-selected choice. Original remains the sensible automatic default when it becomes known, but once the person deliberately selects a Claim or Original (or creates a Claim and switches to it), background refresh/discovery is not allowed to overwrite that selection. The explicit selection is stored in the encrypted GroupStore state and survives refreshes/restarts.

### Claim/original authority names
The group branch selector now displays the authority's published/profile name when Weave already knows it. The shortened VLD0 key remains the fallback when the profile name has not yet been discovered/verified.

A moderation claim is still advertised through the claimer's published profile directory. A local claim that has not yet been published therefore may not be discoverable by other people until that profile is published.

### Group gossip fast path
Weave now gossips compact branch hints every 8 seconds to a small deterministic sample of known Weave peers. A hint contains the group/branch pointer plus the Pulse generation/time the sender has seen. Locally owned Original/Claim branches are not gossiped until that account's profile is published, preserving the existing profile-first discovery rule. Once a peer has legitimately learned and DHT-verified a branch, it may relay that hint.

Gossip is **not authoritative**. On receiving a useful hint, Weave force-reads and validates the branch header and Pulse from the DHT before updating local state. Unknown Claim branches are not accepted unless the underlying group is already known and the claim points back to that group's creator root. An unknown Original can introduce a group only when its branch root is also its creator root and the DHT header validates.

Routine gossip logging is throttled to roughly one heartbeat per minute so the diagnostic history remains readable. Actual verified changes and verification failures are logged immediately.

### Automatic moderation intake
Creators and claim owners do not need to leave the group screen open. While Weave is connected/running, its global group ServiceRequest/message subscriptions process incoming submissions for every locally owned moderation branch:

- `Everyone`: posts are accepted automatically into each locally owned branch.
- `Approval`: posts become `QuarantinedPost` moderation tasks.
- `Established`: currently also becomes a moderation task until the planned trust/tenure hook exists.

This is an application-level background path while Weave is alive, not a permanent Android service that executes Weave code after the app is completely stopped. The daemon can retain/deliver intake while the owner is away, subject to the transport's retention window.

### Temporary unreviewed intake retention
Public/unlisted spectator-readable group intake now asks for a 60-minute TTL rather than 15 minutes. The current VeilKnit daemon clamps ServiceRequest TTLs to a maximum of 60 minutes, so this uses the maximum currently available catch-up window.

When a person claims a group, Weave immediately checks still-live pending items already observed on that device and attaches eligible post submissions to the new claim branch. A new claim also starts with the Original branch's currently visible Pulse/index entries rather than appearing as an empty group.

### Post upload feedback
Creating a group post now shows a visible upload/submission card. It distinguishes:

- media/post upload in progress;
- successfully submitted but potentially awaiting branch review;
- visible on the selected branch;
- upload failure (draft retained).

### Claim confirmation
Claiming a group from Settings now gives a `Group claimed` confirmation dialog. If the profile is not currently published, the message explicitly says the claim exists locally and that publishing the profile is required for other people to discover its moderation branch.

### Profile media-link compatibility
Both profile editors now accept the copied `weave://media?...` link form in addition to raw DHT record keys:

- Quick Edit `+ DHT image` parses the media link and downloads/verifies the image using its embedded SHA-256.
- Advanced Edit's media record field extracts the record key and content hash from a compatible copied image/audio link instead of storing the whole `weave://` URI as the DHT key.

### Quick Edit insertion toolbar
`+ Heading`, `+ Text`, `+ Image`, `+ Audio`, `+ Camera`, `+ DHT image`, and `+ Saved` are now one horizontal swipeable row on phones rather than wrapping into a vertical-looking second line.

## Diagnostics

Weave now keeps a rolling timestamped diagnostic history in app-private storage across daemon reconnects. A copied diagnostic report begins with the current account/network state and a `group responsibilities` section identifying locally known created/claimed/moderated groups, selected branches and pending counts.

Important group log events include:

- active Weave account/main DHT;
- startup moderation-responsibility snapshot;
- group branch selection;
- branch Pulse generation changes;
- claim creation and directory advertisement state;
- group gossip heartbeat, useful received hints and DHT verification result;
- post upload/media upload/submission;
- private/spectator group intake;
- automatic acceptance vs queued-for-review decisions;
- manual moderation decisions;
- group intake stream closure and network reconnects.

The copied report uses a larger tail of the rolling file than the small in-app display buffer. The log intentionally does not write daemon session tokens or authentication proofs.

## Notes

- DHT branch state remains authoritative; gossip only accelerates awareness that a refresh is worthwhile.
- Members-only groups are excluded from public group gossip hints.
- Published profile names are cosmetic trust cues only; branch ownership is still verified using the corresponding main-DHT/branch keys.
- Content-filter ONNX model handling is unchanged from v11. Run the existing model download script before building if the models are not already present.
