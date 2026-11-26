package com.allanlewis.homelabwatchman.resolve;

import java.util.Optional;

/**
 * Looks up the latest release for a GitHub repository. Used for
 * dependencies that aren't OCI images (immich-app/immich,
 * goauthentik/authentik) - the only ones with a reliably fetchable
 * changelog in this MVP.
 */
public interface GitHubReleaseClient {

    Optional<Release> latestRelease(String owner, String repo);

    /**
     * Finds the release (or, failing that, the plain git tag) matching a
     * specific already-known version - used for OCI images via
     * {@link OciImageRepositories}, where the pinned value was resolved
     * from the registry, not from GitHub, so "latest release" isn't
     * necessarily the same thing. notes is null when a tag matched but no
     * release body exists for it (e.g. Checkmk) - url is still real and
     * resolvable in that case, just not a substitute for actual notes.
     */
    Optional<Release> findMatchingRelease(String owner, String repo, String version);

    /** notes/url come straight from the release body/html_url, not fabricated. */
    record Release(String tag, String notes, String url) {
    }
}
