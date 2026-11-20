package com.allanlewis.homelabwatchman.resolve;

import java.util.Optional;

/**
 * Looks up the latest release/tag for a GitHub repository. Used for
 * dependencies that aren't OCI images (immich-app/immich,
 * goauthentik/authentik).
 */
public interface GitHubReleaseClient {

    Optional<String> latestReleaseTag(String owner, String repo);
}
