# PR lifecycle

Everything in this doc describes `--apply`'s behavior. A dry run never touches any of it — no branch, no PR, no comment, nothing on GitHub gets written.

## The core idea: one branch per (dependency, kind)

Every dependency gets at most two possible branches, never more:

```
watchman/version-bump/<slug>
watchman/digest-refresh/<slug>
```

`<slug>` is the dependency's identifier, lowercased with everything non-alphanumeric collapsed to `-` (e.g. `ghcr.io/linuxserver/sonarr` → `ghcr-io-linuxserver-sonarr`). The branch name is stable across runs *regardless of which specific value is currently being proposed* — that's what makes "amend in place" possible instead of accumulating a new PR every time a newer version shows up. An open `v1→v2` PR becomes `v1→v3` in place (same PR, same number, branch reset + retitled) once `v3` is released, rather than sitting stale next to a brand new PR.

## The four outcomes for a pending update

Every dependency with an actual pending update this run (anything that isn't `no-op`) gets checked against GitHub's real current state and lands in exactly one of these:

| Outcome | Condition | What happens |
|---|---|---|
| `CREATED` | No PR exists yet for this (dependency, kind) | Branch created from the real current base branch, edit applied, PR opened, labeled `watchman`. |
| `UPDATED` | A PR is open, but targets an older value than what's now proposed | Branch force-reset to the current base branch + edit re-applied, PR retitled/re-described, **then explicitly resynced** (see below) — same PR number and URL throughout. |
| `UNCHANGED` | A PR is open and already targets exactly what's being proposed | Nothing happens. No AI call is made — drafting PR text is a real cost, and there's nothing new to draft. |
| `SKIPPED_REJECTED` | No PR is open, but a *closed-without-merge* PR for this branch already proposed this exact value | Respected as a rejection, not resurrected. |

Real examples from `allans-home-lab`:

```
goauthentik/authentik: UNCHANGED https://github.com/allan-lewis/allans-home-lab/pull/585

ghcr.io/linuxserver/radarr: UPDATED https://github.com/allan-lewis/allans-home-lab/pull/614
  title: chore(deps): bump ghcr.io/linuxserver/radarr from 6.4.4 to 7.0.3
  body:  Updates the radarr container image from version 6.4.4 to 7.0.3. ...
```

### Why `UPDATED` needs an explicit resync step

Force-updating a branch ref via the GitHub Git Data API — which is how `vcs` always writes, since it never touches a local checkout (see [architecture.md](architecture.md)) — does **not** trigger GitHub's PR "synchronize" event the way a real `git push --force` does. Confirmed live: after force-pushing a branch under an already-open PR, that PR's cached diff, head SHA, and changed-file count went stale and **stayed stale indefinitely** — still wrong 25+ seconds later — even though the branch's real content was correct the entire time (verified directly via the Contents API and a `main...branch` compare, independent of the PR object's own cache). The fix, now automatic in `apply()`: close and reopen the PR immediately after the force-push. That's the standard GitHub workaround, and it reliably forces a recompute against the real current branch tip.

If you ever see a PR whose diff looks stale right after an `UPDATED` outcome, something regressed here — it should never happen now.

## Cross-kind supersession: a version bump closes its sibling digest-refresh PR

Whenever a `VERSION_BUMP` is created or updated for an identifier, any **open** `DIGEST_REFRESH` PR for that *same* identifier gets closed automatically, with a comment: `"Superseded by a version bump to <new version>."`

Why: merging the digest-refresh PR would just pin a tag that's about to be replaced anyway, and once the version-bump PR merges, the digest-refresh branch's diff no longer even applies to the file. This only runs in that one direction — a digest refresh never looks for a sibling version-bump PR to touch, since there's nothing to supersede the other way.

## Two cleanup sweeps that don't depend on what's currently pending

These run on every `--apply`, before the per-update reconcile loop, and are the reason a dependency doesn't need an active `ProposedUpdate` this run for its PR state to get fixed up.

### Merged-branch cleanup

Finds every PR this tool opened (via the `watchman` label) that's merged, and deletes its head branch if it still exists.

```
Deleted 6 merged PR branch(es).
```

This exists because `allans-home-lab`'s "automatically delete head branches" repo setting is off (checked directly via the API) — and Renovate, observed merging its own PRs with no leftover branch, must be doing exactly the same self-cleanup rather than relying on that setting either.

### Stale-PR cleanup for no-op candidates

For every dependency that's `no-op` this run — meaning the planner looked and found nothing to propose — any still-open PR on *either* of its branches is stale by definition, no matter how it got that way. The branch is deleted immediately after the PR is closed, not left for `cleanupMergedBranches()` to find later — that sweep only ever looks at *merged* PRs, so a closed-without-merge branch from here would otherwise sit on the remote forever.

```
twingate/connector: closed 1 stale PR(s) - already up to date.
```

**This is the exact gap a real case fell into once.** `twingate/connector` had an open PR proposing a version bump that `main` had *already* picked up through a completely different path (a separate merge happened to include the same change). The scan correctly reported `no-op` for it — there was genuinely nothing left to propose — but nothing else would have gone back and closed the now-redundant open PR, because the normal reconcile loop only ever walks *pending* updates, and this dependency no longer had one. This sweep exists specifically to close that gap: it walks every candidate, not just the ones with something pending, and closes anything stale it finds.

A second, separate real case found the matching gap on the branch side: a batch of PRs closed directly via the GitHub API (outside this sweep, outside `apply()` entirely) during the digest-normalization bug investigation left 10 orphaned `watchman/*` branches behind indefinitely, since neither cleanup sweep covers "PR closed, not merged, not through this code path." Those were deleted by hand; the fix above only guarantees *this* sweep's own closures clean up after themselves going forward, not closures from outside the tool.

Both sweeps wrap each dependency in its own try/catch — a transient failure in cleanup for one dependency doesn't stop the sweep for the rest, or block the reconcile loop that follows.

## What "rejection" actually means, precisely

`SKIPPED_REJECTED` only fires when: no PR is currently open for that branch, **and** a closed PR exists whose title already named the *exact* value now being proposed again, **and** that closed PR's `merged_at` is null (i.e. it was genuinely closed without merging, not merged-and-therefore-also-reported-as-closed).

That merged-check used to be inferred rather than read directly — the logic was "if this had actually merged, the file would already show the new value, so it couldn't still be getting proposed; therefore a closed PR naming our exact target must have been rejected, not merged." That inference breaks the moment the local checkout lags behind the remote base branch at all, which is not a rare case — it's exactly what happened once, live, scanning a checkout sitting on a feature branch a couple of commits behind `main`. The fix was to check GitHub's `merged_at` field directly instead of inferring it from local file content.

Practically: if you close a PR without merging it, re-running `--apply` won't resurrect that *same* proposal. If a newer version comes out afterward, that's a different target — its title won't match the old one, so it gets proposed fresh, as normal.

## Why we sometimes disagree with Renovate on digest, but never on version

Renovate and this tool both watch most of the same dependencies here, and generally agree on which tag is "latest" — but can differ on the exact *digest* pinned for that tag, for two unrelated reasons.

### 1. Multi-arch index digest vs. platform-specific digest — a bug, now fixed

A tag's digest, read naively, is the digest of the top-level multi-arch *index* — which can change independently of whether the actual `linux/amd64` image (the only platform this homelab's hosts run) changed at all. A provenance/SBOM attestation manifest for some *other* platform regenerating, or the index simply being re-serialized, rotates the index digest with zero real content change on amd64.

This tool always pins the `linux/amd64` **child** manifest's digest specifically, never the index's own digest. Renovate's choice here isn't consistent: on `linuxserver/sonarr`, for instance, its proposed digest matched the index's own digest, not the amd64 child — confirmed by fetching the full manifest list and finding Renovate's value didn't match any child entry (amd64, arm64, or either attestation manifest), only the index itself. So this is a case-by-case difference in convention, not a rule to rely on. (Earlier wording here claimed Renovate specifically pins the amd64 child; that was an overgeneralization from a smaller set of cases where the two happened to coincide.)

This tool originally compared index digests on both sides and saw phantom "changes" on nearly every dependency, repeatedly — confirmed directly by fetching a "changed" digest's manifest and finding its own amd64 child was identical to what was already pinned. Fixed in two steps that both turned out to be necessary:

1. Always resolve and propose the amd64-specific digest for a *new* tag (not the index digest).
2. **Also** normalize whatever digest is *currently pinned* through the same resolution before comparing — an existing pin might itself be an un-normalized index digest (set before this fix existed, or by Renovate in some other case), and comparing it raw against a freshly-extracted amd64 digest looks like a change even when the image is identical. Step 1 alone wasn't enough; both sides of the comparison have to go through the same normalization or the comparison is comparing two different kinds of hash.

### 2. Renovate doesn't force-push an already-open PR just because the digest rotates — a real design difference, not a bug

Renovate's Dependency Dashboard issue exposes this directly: every open PR gets its own unchecked `- [ ] rebase-branch=...` checkbox, plus a `- [ ] rebase-all-open-prs` one. A routine scan does **not** automatically tick that box. Renovate only refreshes an open PR's content when the base branch conflicts with it, or a human explicitly requests a rebase (via one of those checkboxes). Once Renovate has proposed a valid tag with *some* resolvable digest, it considers that PR done and won't keep re-resolving the digest for it on every subsequent run — even if the upstream image gets rebuilt again afterward.

This tool has no such "already proposed, leave it alone" state — every run recomputes everything from scratch and force-pushes if anything changed, including a same-tag digest rotation. Neither behavior is wrong; they're different tradeoffs between staying maximally current and minimizing PR churn/review noise.

**Confirmed live, repeatedly**: whenever Renovate's PR and ours differed only on digest (same tag), querying the registry directly for the current `linux/amd64` digest matched *ours* every time — Renovate's was a snapshot from an earlier point that it simply hadn't been asked to refresh. If you want Renovate to catch up on a specific PR, check its rebase box (or "rebase all open PRs").

### Digest-refresh titles match Renovate's convention on purpose

A `DIGEST_REFRESH` title is built deterministically in code
(`ChatClientPrTextGenerator`), not left to the model, specifically so the
only difference from Renovate's own title for the same change is the
digest itself: `chore(deps): update <name>:<tag> docker digest to
<short digest>`. Confirmed byte-for-byte against Renovate's live sonarr
PR — identical string except for the 7-char digest. The model still
writes the body; only the title is fixed, because getting it byte-exact
isn't something worth leaving to chance across model calls.
