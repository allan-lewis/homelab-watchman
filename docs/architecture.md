# Architecture

Single Gradle module, package-organized by pipeline stage. Data flows
`scan -> plan -> ai -> vcs`, with `resolve` feeding `plan` and `persist`/`metrics`
recording state at the end. See [usage.md](usage.md) for the CLI and
[pr-lifecycle.md](pr-lifecycle.md) for exactly how `vcs` decides what to do.

## scan

`DependencyExtractor` is the shared interface; each implementation owns one
extraction pattern and knows nothing about the others:

- `NixImageRefExtractor` — regex over every `.nix` file for
  `image = "registry/repo:tag@sha256:digest"`.
- `ImmichVersionExtractor` — the `immichVersion` let-binding in
  `nixos/profiles/immich/default.nix`.
- `AuthentikVersionExtractor` — the `authentikVersion` let-binding in
  `nixos/profiles/authentik/default.nix`.
- `TerraformProviderExtractor` — `version = ">= X.Y.Z"` floor constraints
  inside any `required_providers` block in any `.tf` file. Deliberately
  never touches `.terraform.lock.hcl` — see its own javadoc and
  "Terraform providers" below for why.

Each produces `CandidateDependency(identifier, file, currentValue, currentDigest, ecosystem)`.
`ecosystem` is an explicit `Ecosystem` enum (`OCI_IMAGE`, `GITHUB_RELEASE`,
`TERRAFORM_PROVIDER`) set by the extractor — this used to be inferred from
`currentDigest() != null`, which stopped being reliable once a third kind
with no digest existed.

`GitCheckoutGuard` isn't an extractor, but runs before any of them: it
shells out to `git` (read-only — `rev-parse`/`ls-remote`, never a fetch) to
confirm the `--repo` checkout is exactly on the configured base branch and
exactly in sync with `origin/<base>`. `vcs` is immune to checkout
staleness (every write branches from the real remote base branch via the
API), but `scan` reads whatever's on disk - a stale checkout makes the
planner re-propose already-merged changes. Hard-aborts the whole run on
mismatch; see [usage.md](usage.md#--repo-path--required) for the exact
error text.

## resolve

- `OciRegistryClient` — speaks the standard OCI Distribution HTTP API.
  One implementation covers docker.io, ghcr.io, and lscr.io (lscr.io is a
  pure proxy in front of ghcr.io - confirmed live, same auth realm).
  Three operations:
  - `listTags` — paginated (`?n=1000`, following `Link: rel="next"`) tag
    enumeration. Needed because registry listing order isn't chronological
    and isn't even sorted; some repos (the Servarr apps, via
    architecture-suffixed and nightly tags) have tens of thousands of tags,
    so a naive single-page fetch can miss recent plain-version tags
    entirely.
  - `resolveDigest` — the *new* digest for a given tag, normalized to the
    `linux/amd64` child manifest if the tag resolves to a multi-arch index.
  - `normalizeDigest` — the same normalization, but starting from an
    already-pinned digest instead of a tag (the distribution API accepts
    either as `{reference}` interchangeably). Needed because an *existing*
    pin might itself be an un-normalized index digest; comparing it raw
    against a freshly-resolved amd64 digest looks like a change even when
    the image is identical. See [pr-lifecycle.md](pr-lifecycle.md#1-multi-arch-index-digest-vs-platform-specific-digest--a-bug-now-fixed).
- `GitHubReleaseClient` — latest release for immich-app/immich and
  goauthentik/authentik, via `/releases/latest` (already excludes drafts/
  prereleases). Strips everything up to the last `/` in the returned tag,
  since not every project's release tag matches the bare version string
  verbatim (authentik tags releases as `version/2026.8.6`, not `2026.8.6`).
- `TerraformRegistryClient` — all available versions for a provider, via
  the public, unauthenticated Terraform Registry API.

## plan

`UpdatePlanner` dispatches by `Ecosystem` (not digest presence) and emits
zero or one `ProposedUpdate` per candidate, tagged with `UpdateKind`:

- `DIGEST_REFRESH` — same tag, digest changed upstream (OCI images only;
  Terraform providers and GitHub-release deps have no digest concept here).
- `VERSION_BUMP` — newer tag/release/provider version exists.

These are never bundled into the same PR - see
[pr-lifecycle.md](pr-lifecycle.md) for how an open digest-refresh PR gets
closed once a version bump supersedes it.

Two pieces of logic are shared across OCI images and Terraform provider
versions (both are plain dotted-version strings; GitHub releases are
compared as opaque tag strings instead):

- `findNewerSameShapeVersion` — only considers candidates whose non-digit
  "shape" matches the current value exactly, via `skeleton()`.
- `skeleton()` — collapses every digit run to a single `#`, **except the
  last one**, which keeps its exact length. This two-tier rule exists
  because a single "collapse everything" or "preserve everything" rule
  each failed differently in practice:
  - Collapsing every digit run to one `#` let a wrong-epoch tag (lidarr's
    abandoned `8.1.2135` scheme, transmission's leftover 2021 date-stamped
    tags) outrank the real update, since both looked like the same shape
    as a normal `#.#.#` semver tag.
  - Preserving every digit run's exact length then over-corrected: it
    rejected a *real* minor-version bump that crosses a digit-count
    boundary in a middle segment (`bpg/proxmox` going `0.97.0` → `0.123.1`,
    97 → 123 is 2 digits → 3 digits, nothing to do with versioning scheme).
  - Only the trailing/patch segment turning out a wildly different length
    is the actual anomaly signal in every real case seen so far.

`VERSION_BUMP` for an OCI image also resolves and includes a digest for
the *new* tag — not just the bare tag — matching both Renovate's own
convention and every other image ref in this repo (always `tag@digest`).
Skipping this once caused a real bug: a version bump that wrote a bare
tag left nothing for the next scan to compare a digest against, which
then looked identical to "the digest changed" on every subsequent run.

## ai

`PrTextGenerator` writes PR title/body only — it does not decide whether
an update is safe; that's already decided deterministically by `plan`.
The only class touching Spring AI is `ChatClientPrTextGenerator`, and even
that only depends on the provider-agnostic `ChatClient`. Provider/model
are plain Spring AI config (`spring.ai.anthropic.*`), currently Anthropic
Claude Haiku on a pay-as-you-go API key — swapping providers later means
swapping the Spring AI starter dependency and that config, not touching
this package's code.

Uses Spring AI's structured output (`.call().entity(PrText.class)`)
rather than hand-parsing a response. The system prompt is deliberately
written to avoid two specific, observed failure modes: inventing specific
changes when no release notes are available, and mischaracterizing a
digest refresh as a version change (`"bump X from 4.0.20 to 4.0.20"` is
technically true but misleading — it reads as "update to the same
version", not "same version, new build" — so the prompt requires
`"refresh X image digest"` phrasing instead for that kind).

## vcs

Deliberately API-only, no local git checkout anywhere in this path -
every write goes through `GitHubApi`, a thin wrapper over the GitHub REST
API (branch refs via the Git Data API, file edits via the Contents API,
PRs/labels/comments via the Pulls/Issues APIs). This was a conscious
design choice, not just a convenience: it means `vcs` never touches the
*actual* local clone of `allans-home-lab` (which has its own in-progress
branch and commits at any given time) - every operation branches from
whatever the real remote base branch currently is, so it's immune to the
local checkout ever being wrong or stale. The same isn't true of `scan`,
which is why `GitCheckoutGuard` exists (see "scan" above).

`GitHubPullRequestClient` implements the idempotent lifecycle in
[pr-lifecycle.md](pr-lifecycle.md): one stable branch per (identifier,
kind), `checkStatus()`/`apply()` split so AI drafting only happens when
something will actually change, cross-kind supersession, and two cleanup
sweeps (merged-branch deletion, stale-PR closure for no-op candidates)
that run independently of whatever's currently pending.

One non-obvious operational detail: force-updating a branch ref via the
Git Data API does **not** trigger GitHub's PR "synchronize" event the way
a real `git push --force` does - an already-open PR's cached diff goes
stale and stays that way indefinitely otherwise. `apply()` closes and
reopens the PR immediately after any force-push to work around this;
see [pr-lifecycle.md](pr-lifecycle.md#why-updated-needs-an-explicit-resync-step).

## Terraform providers

Tracks only the floor constraint in `providers.tf`
(`version = ">= X.Y.Z"`), never `.terraform.lock.hcl`. Regenerating that
lock file's hashes correctly needs a real `terraform providers lock` run
with an explicit per-platform list that isn't recoverable from the file
itself (hashes aren't labeled by platform in the lock file format) - a
fundamentally different, much heavier mechanism than every other resolver
here, which is a plain text substitution.

Instead: bumping the floor constraint above whatever's currently locked
is itself a forcing function, with no lock-file regeneration needed from
this tool at all. `allans-home-lab`'s `provision.sh` runs
`terraform init -upgrade=false`, so once the floor exceeds the locked
version, `terraform init` hard-fails with an explicit message naming the
fix (`must use terraform init -upgrade`) until a human runs it - confirmed
live, including that `terraform init -upgrade` afterward correctly
re-resolves and re-locks.

## persist

`ScanRun` and `DependencyState` (Spring Data JPA, embedded H2 file DB)
record every run's results - per-dependency current/latest value,
`UpdateKind`, and PR link/status - for every candidate, not just the ones
with a pending update. This exists from the MVP specifically so a future
GUI has state to render without needing its own storage design.

## metrics

Micrometer `PrometheusMeterRegistry`, scraped in-process (no HTTP
endpoint, no actuator starter) and written as a textfile-collector
`.prom` file after each run - matches the pattern `allans-home-lab`'s
`node-exporter` already uses (`--collector.textfile.directory`), rather
than requiring a Pushgateway or a long-lived process to scrape.

## Secrets

MVP (manual CLI, local): Doppler, `homelab` project, `prd` config,
`HOMELAB_WATCHMAN_*` secrets. See [usage.md](usage.md#secrets) for exactly
which secrets and what each is for.

Deployed (post-MVP, once this runs as a scheduled container on a NixOS
host): a new `nixos/profiles/homelab-watchman/` profile in
`allans-home-lab` with `sops.secrets`/`sops.templates`, rendered to an env
file - the same shape as the existing `authentik.env`/`immich.env`
profiles.
