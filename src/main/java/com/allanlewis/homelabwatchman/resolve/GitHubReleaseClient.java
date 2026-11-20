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

    /** notes/url come straight from the release body/html_url, not fabricated. */
    record Release(String tag, String notes, String url) {
    }
}
