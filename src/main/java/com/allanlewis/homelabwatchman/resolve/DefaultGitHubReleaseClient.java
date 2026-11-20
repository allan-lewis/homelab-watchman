package com.allanlewis.homelabwatchman.resolve;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Latest release/tag for a GitHub repository, via /releases/latest (which
 * already excludes drafts and prereleases).
 *
 * Note: not every project's release tag matches the bare version string
 * stored in allans-home-lab verbatim - e.g. goauthentik/authentik tags
 * releases as "version/2026.8.6", not "2026.8.6". Stripping everything up
 * to the last "/" handles that case and is a no-op for tags with no "/"
 * (e.g. immich-app/immich's "v3.4.2").
 */
@Component
public class DefaultGitHubReleaseClient implements GitHubReleaseClient {

    private final RestClient restClient = RestClient.create();
    private final String token;

    public DefaultGitHubReleaseClient(@Value("${homelab.watchman.github.token:}") String token) {
        this.token = token;
    }

    @Override
    public Optional<String> latestReleaseTag(String owner, String repo) {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest";

        ReleaseResponse response = restClient.get()
                .uri(url)
                .headers(h -> {
                    h.set("Accept", "application/vnd.github+json");
                    if (!token.isBlank()) {
                        h.setBearerAuth(token);
                    }
                })
                .exchange((request, resp) -> resp.getStatusCode().is2xxSuccessful()
                        ? resp.bodyTo(ReleaseResponse.class)
                        : null);

        if (response == null || response.tagName() == null) {
            return Optional.empty();
        }

        String tag = response.tagName();
        int lastSlash = tag.lastIndexOf('/');
        return Optional.of(lastSlash == -1 ? tag : tag.substring(lastSlash + 1));
    }

    private record ReleaseResponse(@JsonProperty("tag_name") String tagName) {
    }
}
