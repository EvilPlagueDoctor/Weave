# Weave comment-system repair — 2026-09-07

This source-only update is based on `Weave-Android-Profile-Updates-Compile-Fix-v2.zip`.
It has not been compiler-verified in this environment because Gradle 9.4.1 is not cached and the sandbox cannot reach services.gradle.org.

## Behaviour changes

- `Anyone can comment` now means open publication. The page owner no longer runs hidden duplicate/repetition moderation before publishing an open comment.
- A sender no longer treats a DHT write as proof that the owner was notified. The comment is shown as `Sent, awaiting confirmation` until an authenticated acknowledgement returns.
- If owner notification fails after the DHT comment was stored, the comment becomes `Not sent` and exposes a Retry action. Retry reuses the existing DHT pointer instead of duplicating the comment body.
- Owner acknowledgements distinguish `Published`, `WaitingApproval`, and `Rejected`.
- Comment notices are saved in the daemon-encrypted private Weave vault before dereferencing their DHT pointer. If the notification outruns DHT propagation, the notice survives and is retried on later inbox sweeps.
- Mailbox notices are not deleted until Weave has either consumed them or deliberately rejected them as malformed/invalid. A valid notice whose DHT body is not readable yet stays retryable.
- The receiver performs short immediate DHT retries (0s, +2s, +5s) before deferring to the durable retry queue.
- `I approve comments first` places received comments in Activity and sends the author a waiting-for-approval acknowledgement.
- `No comments` explicitly rejects incoming notices and acknowledges the rejection.
- Activity now shows incoming comment history, including automatically published comments, rather than only the moderation queue.
- Keep/Drop decisions send acknowledgements back to the author.
- A profile being viewed reconciles its published comment index about once per minute. This is intentionally modest polling; comments are not treated as chat.
- The comment policy used for your own published page comes from the last published profile record when available, so an unpublished Settings draft does not silently change live behaviour.
- New index entries bind the kept pointer to a SHA-256 digest of the exact WireComment. Readers reject a later rewrite at that pointer when a digest is present. Older digest-less index entries remain readable.
- Comment-chain appends and local comment-vault operations are serialized to reduce races between simultaneous network/UI comment events.
- New comment-status/activity strings were added to the app-language translation table.

## Delivery timing

The daemon may still take many minutes to deliver a mailbox message depending on routing and mailbox retrieval. This update does not try to turn comments into chat. Its goal is to make delayed delivery recoverable and make the UI accurately describe what is known.
