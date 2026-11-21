# Usage

Every example below runs via Gradle, with secrets supplied by Doppler:

```
doppler run -- ./gradlew bootRun --args="<arguments go here>"
```

The same arguments work unchanged once this is packaged as a jar (`java -jar homelab-watchman.jar <arguments>`) or run as a container (`docker run allanelewis/homelab-watchman <arguments>`).

## The command

There's exactly one subcommand: `scan`. If the first argument isn't `scan`, or no arguments are given at all, the usage line prints and nothing else happens:

```
Usage: scan --repo <path> [--apply] [-v|--verbose] [--only <identifier>]
```

## Flags

### `--repo <path>` — required

Path to a local checkout of the target repo (`allans-home-lab`). This checkout must be:

- On the configured base branch exactly (`main` by default — `homelab.watchman.github.base-branch`), not a feature branch.
- Exactly in sync with `origin/<base-branch>` — the same commit SHA, not just "no conflicts."

Every run checks both of these *before* doing anything else, via `git rev-parse --abbrev-ref HEAD` and `git ls-remote origin refs/heads/<base>` (the latter never fetches, so this can't mutate your checkout). If either check fails, the whole run aborts immediately:

```
$ doppler run -- ./gradlew bootRun --args="scan --repo /home/lab/src/allans-home-lab"
ERROR ... IllegalStateException: /home/lab/src/allans-home-lab is on 'feature/dec-2026', not 'main'. Check out main before scanning.
```

```
ERROR ... IllegalStateException: /home/lab/src/allans-home-lab's main (83a7e31b...) doesn't match origin/main (c3dfa0d2...). Pull before scanning.
```

**Why this exists**: `vcs` always branches from the real remote base branch via the GitHub API, so it's immune to checkout staleness — but `scan` reads whatever's actually on disk. A stale or wrong-branch checkout makes the planner re-propose changes that are already merged. This isn't a hypothetical: it happened live once (a checkout sitting two commits behind `main`), silently produced a wrong result, and this guard was added specifically because of it. See [pr-lifecycle.md](pr-lifecycle.md#what-rejection-actually-means-precisely) for the knock-on bug it caused.

**Fix**: `cd <repo> && git checkout main && git pull`.

### (no flag) — dry run, the default

Without `--apply`, nothing gets written to GitHub and no AI calls happen. A run:

1. Scans every dependency.
2. Resolves each one's latest known value.
3. Prints one line per dependency.
4. Prints a one-line summary.
5. Still persists this run's results locally (H2 + a Prometheus textfile) — that part isn't gated by `--apply`, since it's purely local bookkeeping with no external effect.

```
$ doppler run -- ./gradlew bootRun --args="scan --repo /home/lab/src/allans-home-lab"
25 dependencies in scope
goauthentik/authentik          2026.8.5 -> 2026.8.6
immich-app/immich              v3.3.5 -> v3.4.2
ghcr.io/gethomepage/homepage   no-op
allanelewis/homelab-dashboard  no-op
twingate/connector             no-op
vaultwarden/server             1.37.4 -> 1.37.5
...
bpg/proxmox                    no-op
hashicorp/local                no-op

7 version bump(s), 0 digest refresh(es), 18 up to date
Pass --apply to draft PR text and open/update PRs for these updates.
```

### `--apply`

Turns the dry run into the real thing. In order:

1. Everything the dry run does (scan, resolve, print, persist).
2. **Merged-branch cleanup** — deletes the head branch for any of our merged PRs whose branch still exists.
3. **Stale-PR cleanup** — for every dependency that's `no-op` this run, closes any still-open PR for it. (A dependency can go `no-op` through a path this tool never saw — you merged it by hand, or a different merge happened to include the same change — and nothing else would otherwise notice.)
4. **Reconciliation** — for every dependency with a pending update, checks GitHub's actual current state for its branch, and only if something will actually change, drafts PR text via Claude and opens/updates the PR.

Full detail on steps 2–4, including every outcome and a couple of real bugs this exact sequence caught, is in [pr-lifecycle.md](pr-lifecycle.md).

One dependency failing (a transient GitHub/registry/AI error) doesn't abort the run for the rest — it prints `<identifier>: FAILED - <message>` and moves on:

```
ghcr.io/linuxserver/sonarr: FAILED - 503 Service Unavailable: ...
```

### `-v` / `--verbose`

Changes only the per-dependency report line — nothing about what gets written. Default is terse (bare version, or a label):

```
ghcr.io/linuxserver/sonarr      no-op
ghcr.io/linuxserver/radarr      6.4.4 -> 7.0.3
ghcr.io/linuxserver/prowlarr    digest refresh
```

Verbose shows the full registry/tag/digest on both sides, plus the update kind:

```
ghcr.io/linuxserver/sonarr      no-op (ghcr.io/linuxserver/sonarr:4.0.20@sha256:765ab7a05af218eb3c1adfb7cc5b900f608cb4e2754d157ce980bc5a769ccb06)
ghcr.io/linuxserver/radarr      VERSION_BUMP  ghcr.io/linuxserver/radarr:6.4.4@sha256:7a20f20... -> ghcr.io/linuxserver/radarr:7.0.3@sha256:ff1b1f21...
```

Combine freely with `--apply`; it only affects what's printed, not what's done.

### `--only <identifier>`

Restricts the *entire* run — scan, plan, and (if `--apply` is also given) every reconcile/cleanup step — to one dependency, matched by its exact identifier string. Identifiers are the first column of every report line, so copy from there: `ghcr.io/linuxserver/sonarr`, `twingate/connector`, `bpg/proxmox`, `immich-app/immich`, etc.

This is the main tool for safely validating a change before trusting it against everything else:

```
# See what would happen for one dependency, no side effects
doppler run -- ./gradlew bootRun --args="scan --repo <path> --only ghcr.io/linuxserver/sonarr"

# Actually do it, scoped to just that one dependency
doppler run -- ./gradlew bootRun --args="scan --repo <path> --apply --only ghcr.io/linuxserver/sonarr"
```

Every new resolver and every vcs change in this project's history was verified this way first — scoped to one low-stakes dependency, checked directly against the GitHub API, *then* run broadly. It's how several real bugs got caught before they spread to the other 20+ dependencies (see [pr-lifecycle.md](pr-lifecycle.md) for specifics).

## Secrets

All consumed via Doppler (`homelab` project, `prd` config):

| Secret | Used for |
|---|---|
| `HOMELAB_WATCHMAN_AI_API_KEY` | Claude Haiku calls (PR text drafting). Separate from a Claude Code subscription — this is pay-per-token Anthropic API usage. The provider is swappable (Spring AI starter + `spring.ai.*` config), so this name deliberately doesn't say "Anthropic". |
| `HOMELAB_WATCHMAN_GITHUB_TOKEN` | Every `vcs` operation — reading/writing branches, file contents, PRs, labels, and comments on `allans-home-lab`. Fine-grained PAT scoped to that one repo, with Contents and Pull requests read/write. |

`HOMELAB_WATCHMAN_DOCKERHUB_USERNAME` / `_TOKEN` exist too, but aren't needed to *run* `scan` — they're only for `./gradlew bootBuildImage` + `docker push`, i.e. publishing this tool itself.

## Local state

Every run, dry or `--apply`, writes to:

- `./data/homelab-watchman.mv.db` — H2 file DB (`ScanRun` + `DependencyState` tables). Gitignored.
- `./metrics/homelab-watchman.prom` — Prometheus textfile-collector format, matching `allans-home-lab`'s existing `node_exporter --collector.textfile.directory` pattern. Gitignored.

Both accumulate across runs. Neither is needed to understand a single run's console output, but they're there to give a future GUI or dashboard real history to render.
