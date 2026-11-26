package com.allanlewis.homelabwatchman.resolve;

import java.util.Map;
import java.util.Optional;

/**
 * Curated, verified identifier -> GitHub "owner/repo" map for OCI images
 * whose upstream changelog isn't otherwise discoverable (unlike
 * GITHUB_RELEASE candidates, whose identifier already *is* the GitHub
 * repo). An OCI image's registry path is not reliably its source repo -
 * confirmed live per entry below, not guessed:
 *
 * - Some ghcr.io images are a direct 1:1 match (gethomepage/homepage,
 *   blakeblackshear/frigate, tautulli/tautulli, seerr-team/seerr).
 * - linuxserver's images live at "linuxserver/docker-<app>", not
 *   "linuxserver/<app>" - and their release tag format is inconsistent
 *   per app (confirmed live: prowlarr/lidarr/radarr/sonarr/plex use
 *   "<version>.<build>-ls<N>", bazarr uses "v<version>-ls<N>",
 *   transmission inserts an "-r0" infix) - see
 *   DefaultGitHubReleaseClient's matching logic for how that's handled
 *   generically rather than per-repo.
 * - Docker Hub org names frequently don't match the GitHub org at all
 *   (vaultwarden/server -> dani-garcia/vaultwarden; docker.io/library/nginx
 *   -> nginx/nginx, which was itself a surprise - it does publish real
 *   release notes, tagged "release-<version>", contrary to this file's
 *   original assumption that no OCI image had a usable changelog).
 *
 * Deliberately excludes twingate/connector: it isn't open source, so no
 * GitHub repo exists to map to at all - stays "no release notes
 * available", same as before this file existed.
 *
 * allanelewis/homelab-dashboard and docker.io/allanelewis/homelab-metrics
 * map to real repos but may still resolve to "no release notes available"
 * day to day - the former has no tags/releases at all yet, and the
 * latter's tags lag behind whatever's currently pinned in the image. That
 * is a real, honest state (matches() has nothing to match), not a mapping
 * error.
 */
public final class OciImageRepositories {

    private static final Map<String, String> GITHUB_REPO = Map.ofEntries(
            Map.entry("allanelewis/homelab-dashboard", "allan-lewis/homelab-dashboard"),
            Map.entry("docker.io/allanelewis/homelab-metrics", "allan-lewis/homelab-metrics"),
            Map.entry("docker.io/checkmk/check-mk-community", "Checkmk/checkmk"),
            Map.entry("docker.io/corentinth/it-tools", "CorentinTh/it-tools"),
            Map.entry("docker.io/jellyfin/jellyfin", "jellyfin/jellyfin"),
            Map.entry("docker.io/library/nginx", "nginx/nginx"),
            Map.entry("docker.io/traefik/whoami", "traefik/whoami"),
            Map.entry("docker.io/triliumnext/trilium", "TriliumNext/Trilium"),
            Map.entry("ghcr.io/blakeblackshear/frigate", "blakeblackshear/frigate"),
            Map.entry("ghcr.io/gethomepage/homepage", "gethomepage/homepage"),
            Map.entry("ghcr.io/linuxserver/bazarr", "linuxserver/docker-bazarr"),
            Map.entry("ghcr.io/linuxserver/lidarr", "linuxserver/docker-lidarr"),
            Map.entry("ghcr.io/linuxserver/prowlarr", "linuxserver/docker-prowlarr"),
            Map.entry("ghcr.io/linuxserver/radarr", "linuxserver/docker-radarr"),
            Map.entry("ghcr.io/linuxserver/sonarr", "linuxserver/docker-sonarr"),
            Map.entry("ghcr.io/linuxserver/transmission", "linuxserver/docker-transmission"),
            Map.entry("ghcr.io/seerr-team/seerr", "seerr-team/seerr"),
            Map.entry("ghcr.io/tautulli/tautulli", "Tautulli/Tautulli"),
            Map.entry("lscr.io/linuxserver/plex", "linuxserver/docker-plex"),
            Map.entry("vaultwarden/server", "dani-garcia/vaultwarden"));

    /**
     * Supplementary, non-version-specific reference links for dependencies
     * with no per-version changelog at all (Checkmk publishes plain git
     * tags with no release body - see DefaultGitHubReleaseClient). Not a
     * substitute for real release notes; surfaced to the AI prompt
     * alongside whatever tag link is found, never instead of being honest
     * that no notes exist.
     */
    private static final Map<String, String> SUPPLEMENTAL_DOCS =
            Map.of("docker.io/checkmk/check-mk-community", "https://docs.checkmk.com/latest/en/cmk_versions.html");

    private OciImageRepositories() {
    }

    public static Optional<String> githubRepoFor(String identifier) {
        return Optional.ofNullable(GITHUB_REPO.get(identifier));
    }

    public static Optional<String> supplementalDocsFor(String identifier) {
        return Optional.ofNullable(SUPPLEMENTAL_DOCS.get(identifier));
    }
}
