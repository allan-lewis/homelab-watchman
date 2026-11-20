# homelab-watchman

A dependency-update bot for [allans-home-lab](https://github.com/allan-lewis/allans-home-lab),
replacing Mend/Renovate for the parts of that repo Renovate doesn't cleanly
cover: container image refs and app versions embedded directly in Nix
modules. Finds out-of-date dependencies, drafts the change, and opens a PR
with an AI-written description. See [docs/architecture.md](docs/architecture.md)
for the full design.

## MVP scope

Three dependency kinds, tracked in `allans-home-lab`:

1. Container image refs embedded in `.nix` files (`image = "repo:tag@sha256:digest"`)
2. Immich version (`nixos/profiles/immich/default.nix`)
3. Authentik version (`nixos/profiles/authentik/default.nix`)

Each proposed change is classified as `DIGEST_REFRESH` (same tag, upstream
rebuilt the image) or `VERSION_BUMP` (newer tag/release available), and the
two are always opened as separate PRs.

## Requirements

- Java 25, Gradle — provided by `flake.nix` (`nix develop`)
- Four secrets in Doppler (`homelab` project, `prd` config):
  - `HOMELAB_WATCHMAN_AI_API_KEY` — Anthropic API key (provider is pluggable;
    see architecture doc)
  - `HOMELAB_WATCHMAN_GITHUB_TOKEN` — fine-grained PAT scoped to
    `allan-lewis/allans-home-lab`, Contents + Pull requests read/write
  - `HOMELAB_WATCHMAN_DOCKERHUB_USERNAME` / `HOMELAB_WATCHMAN_DOCKERHUB_TOKEN` —
    for publishing the built image

## Running (MVP: manual CLI)

```
nix develop
doppler run --project homelab --config prd -- ./gradlew bootRun --args="scan --repo /path/to/allans-home-lab"
```

Each run persists state to a local H2 file DB (`./data/`) and writes
Prometheus metrics in textfile-collector format to `./metrics/homelab-watchman.prom`.

## Building the container image

```
doppler run --project homelab --config prd -- ./gradlew bootBuildImage
docker push allanelewis/homelab-watchman:<version>
```
