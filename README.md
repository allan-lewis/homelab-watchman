# homelab-watchman

A dependency-update bot for [allans-home-lab](https://github.com/allan-lewis/allans-home-lab),
alongside Renovate (not a replacement — see
[docs/pr-lifecycle.md](docs/pr-lifecycle.md#why-we-sometimes-disagree-with-renovate-on-digest-but-never-on-version)
for how the two compare) for the parts of that repo Renovate doesn't
cleanly cover: container image refs and app versions embedded directly
in Nix modules, and Terraform provider version floors. Finds out-of-date
dependencies, drafts the change, and opens a PR with an AI-written
description.

**Start here:**
- [docs/usage.md](docs/usage.md) — every CLI flag, with examples and exact output.
- [docs/pr-lifecycle.md](docs/pr-lifecycle.md) — every PR state, every cleanup rule, and how this tool compares to Renovate.
- [docs/architecture.md](docs/architecture.md) — internals, package by package.

## MVP scope

Four dependency kinds, tracked in `allans-home-lab`:

1. Container image refs embedded in `.nix` files (`image = "repo:tag@sha256:digest"`)
2. Immich version (`nixos/profiles/immich/default.nix`)
3. Authentik version (`nixos/profiles/authentik/default.nix`)
4. Terraform provider version floors (`required_providers` blocks in any `.tf` file) — tracks the floor constraint only, never `.terraform.lock.hcl`; see [docs/architecture.md](docs/architecture.md#terraform-providers) for why.

Each OCI image update is classified as `DIGEST_REFRESH` (same tag,
upstream rebuilt the image) or `VERSION_BUMP` (newer tag/release/provider
version available), and the two are always opened as separate PRs — see
[docs/pr-lifecycle.md](docs/pr-lifecycle.md) for the full lifecycle,
including how one supersedes the other.

## Requirements

- Java 25 and Gradle 9 — on bellatrix these come from the `devops` NixOS
  profile (`nixos/modules/java`) in allans-home-lab, same as Go/Rust. No
  per-project dev shell needed.
- Doppler secrets (`homelab` project, `prd` config) — see
  [docs/usage.md#secrets](docs/usage.md#secrets) for the full list and
  what each one is for.

## Quickstart

```
# See what would change, no side effects
doppler run -- ./gradlew bootRun --args="scan --repo /home/lab/src/allans-home-lab"

# Do it for real: draft PR text, open/update PRs, run cleanup sweeps
doppler run -- ./gradlew bootRun --args="scan --repo /home/lab/src/allans-home-lab --apply"

# Try a single dependency first - the recommended way to test anything new
doppler run -- ./gradlew bootRun --args="scan --repo /home/lab/src/allans-home-lab --apply --only ghcr.io/linuxserver/sonarr"
```

Full flag reference, including `-v`/`--verbose` and exactly what
`--apply` does step by step: [docs/usage.md](docs/usage.md).

Each run persists state to a local H2 file DB (`./data/`) and writes
Prometheus metrics in textfile-collector format to `./metrics/homelab-watchman.prom`.

## Building the container image

```
doppler run --project homelab --config prd -- ./gradlew bootBuildImage
docker push allanelewis/homelab-watchman:<version>
```
