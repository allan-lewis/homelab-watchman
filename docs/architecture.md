# Architecture

Single Gradle module, package-organized by pipeline stage. Data flows
scan -> resolve -> plan -> ai -> vcs, with persist recording state at the end.

## scan

`DependencyExtractor` is the shared interface; each implementation owns one
extraction pattern and knows nothing about the others:

- `NixImageRefExtractor` — regex over every `.nix` file for
  `image = "registry/repo:tag@sha256:digest"`.
- `ImmichVersionExtractor` — the `immichVersion` let-binding in
  `nixos/profiles/immich/default.nix`.
- `AuthentikVersionExtractor` — the `authentikVersion` let-binding in
  `nixos/profiles/authentik/default.nix`.

Each produces `CandidateDependency(identifier, file, currentValue, currentDigest)`.

## resolve

- `OciRegistryClient` — standard OCI Distribution HTTP API, covers
  docker.io/ghcr.io/lscr.io. Two operations: list tags (for `VERSION_BUMP`
  detection) and resolve the digest of the *currently pinned* tag (for
  `DIGEST_REFRESH` detection — these are different checks, not one).
- `GitHubReleaseClient` — latest release/tag for immich-app/immich and
  goauthentik/authentik.

## plan

`UpdatePlanner` matches each candidate to its resolver and emits zero or one
`ProposedUpdate` per candidate, tagged with `UpdateKind`:

- `DIGEST_REFRESH` — same tag, digest changed upstream.
- `VERSION_BUMP` — newer tag/release exists.

These are never bundled into the same PR.

## ai

`PrTextGenerator` writes PR title/body only — it does not decide whether an
update is safe; that's already decided deterministically by `plan`. The only
class touching Spring AI is `ChatClientPrTextGenerator`, and even that only
depends on the provider-agnostic `ChatClient`. Provider/model are config
(`homelab.watchman.ai.provider`, `homelab.watchman.ai.model`), currently
Anthropic Claude Haiku on a pay-as-you-go API key. Swapping providers later
means swapping the Spring AI starter dependency + those two properties, not
touching this package's code.

## vcs

`PullRequestClient` — JGit against a local checkout to branch/edit/commit,
then the GitHub REST API to open the PR.

## persist

`ScanRun` and `DependencyState` (Spring Data JPA, embedded H2 file DB) record
every run's results — per-dependency current/latest value, `UpdateKind`, and
PR link/status. This exists from the MVP specifically so a future GUI has
state to render without needing its own storage design.

## metrics

Micrometer `PrometheusMeterRegistry`, scraped in-process (no HTTP endpoint)
and written as a textfile-collector `.prom` file after each run — matches
the pattern allans-home-lab's `node-exporter` already uses
(`--collector.textfile.directory`), rather than requiring a Pushgateway or a
long-lived process to scrape.

## Secrets

MVP (manual CLI, local): Doppler, `homelab` project, `prd` config,
`HOMELAB_WATCHMAN_*` secrets.

Deployed (post-MVP, once this runs as a scheduled container on a NixOS
host): a new `nixos/profiles/homelab-watchman/` profile in allans-home-lab
with `sops.secrets`/`sops.templates`, rendered to an env file — the same
shape as the existing `authentik.env`/`immich.env` profiles.
