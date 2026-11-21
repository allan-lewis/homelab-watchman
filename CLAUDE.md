# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A dependency-update bot for
[allans-home-lab](https://github.com/allan-lewis/allans-home-lab), built
to run *alongside* Renovate (not replace it), covering what Renovate
can't: container image refs and app versions embedded directly in Nix
modules, and Terraform provider version floors. Single Gradle module,
Java 25, Spring Boot 4 / Spring AI 2, no local git checkout anywhere in
the write path — see [docs/architecture.md](docs/architecture.md).

**Read these before changing anything**, in this order:
1. [docs/usage.md](docs/usage.md) — the CLI, every flag, exact output.
2. [docs/pr-lifecycle.md](docs/pr-lifecycle.md) — every PR state and
   cleanup rule, several of which exist *because of a specific real bug*
   that's documented right next to the rule that fixes it. Also covers
   exactly how and why this tool and Renovate disagree sometimes.
3. [docs/architecture.md](docs/architecture.md) — package-by-package internals.

Those three docs are the actual source of truth for behavior; this file
is just the entry point and the things that don't fit cleanly into any
of them.

## Commands

No Justfile here (unlike allans-home-lab) — plain Gradle, run via Doppler
for secrets:

```bash
./gradlew compileJava test                 # compile + unit tests, no secrets needed
doppler run -- ./gradlew bootRun --args="scan --repo <path>"                        # dry run
doppler run -- ./gradlew bootRun --args="scan --repo <path> --apply"                # the real thing
doppler run -- ./gradlew bootRun --args="scan --repo <path> --apply --only <id>"     # scoped - use this for anything new
./gradlew bootBuildImage                   # build the container image (needs Docker Hub secrets too)
```

Java 25 and Gradle 9 come from `allans-home-lab`'s `devops` NixOS profile
(`nixos/modules/java`) on bellatrix — there is **no per-project dev shell
or flake here**. One existed early on and was deliberately removed once
system packages covered it; don't re-add one without a real reason.

## Status as of this writing

**Fully built and live-verified against the real `allans-home-lab`
repo**: all four extractors, all three resolver ecosystems (OCI
registries, GitHub releases, Terraform registry), persistence, metrics,
AI drafting, and the full vcs PR lifecycle including both cleanup sweeps.
Every piece in this list has at some point been run for real against
`allan-lewis/allans-home-lab` and had its output checked directly against
the GitHub/registry APIs, not just trusted — that pattern (verify against
live state, not just "it compiled") is why most of the real bugs
described in `pr-lifecycle.md` got caught at all, several of them only
because the user asked "are you sure?" at the right moment. Keep doing
that; several "looks right" results in this project's history turned out
not to be.

**Not started / explicitly deferred**:
- Running as anything other than a manual CLI (a scheduled container on
  a NixOS host was the original plan post-MVP; hasn't been picked up).
- Secrets for a *deployed* run would move from Doppler to sops-nix,
  mirroring `authentik.env`/`immich.env` in allans-home-lab — see
  architecture.md's Secrets section. Still Doppler-only today.
- Any GUI/dashboard. `persist` was deliberately shaped (full
  `DependencyState` coverage, not just deltas) to make this easier later,
  but nothing reads that data yet beyond this tool itself.
- `.terraform.lock.hcl` hash regeneration — intentionally out of scope,
  not just "not done yet". See architecture.md's "Terraform providers"
  section for why, and don't try to add it without re-reading that
  reasoning first (the platform list needed to do it correctly isn't
  recoverable from the lock file at all).

## Things that aren't obvious from the code

- **Everything in `vcs` is GitHub-API-only, on purpose.** No JGit, no
  local git operations anywhere in the write path. If you're tempted to
  add a local checkout/clone step for anything in `vcs`, stop — the
  entire reason this is API-only is to stay immune to the real local
  clone's state (in-progress branches, staleness), which `scan` is *not*
  immune to (`GitCheckoutGuard` exists only because of that asymmetry).
- **A digest, naively read, is the multi-arch index's digest, not the
  image's.** This bit twice: once for proposing phantom digest refreshes
  on unchanged images, and once more severely when the fix for that only
  normalized one side of a comparison. If you touch anything in
  `resolve.OciRegistryClient`, re-read
  [pr-lifecycle.md's digest section](docs/pr-lifecycle.md#why-we-sometimes-disagree-with-renovate-on-digest-but-never-on-version)
  first.
- **This homelab is x86-only.** The amd64-child-manifest extraction is
  hardcoded to `linux`/`amd64`. If allans-home-lab ever gets an arm host,
  this needs to become configurable, not just extended.
- **`--only <identifier>` is the default way to test anything new**, not
  a last resort. Every resolver and every vcs change in this project's
  history was verified scoped to one dependency, checked directly
  against the GitHub/registry API, *then* run broadly. Skipping that step
  is how the worst bug in this project's history (10 false-positive PRs
  in one run) made it as far as it did.
- **Doppler secret names deliberately don't name the AI provider**
  (`HOMELAB_WATCHMAN_AI_API_KEY`, not `..._ANTHROPIC_...`) — the provider
  is meant to stay swappable via Spring AI config alone.
