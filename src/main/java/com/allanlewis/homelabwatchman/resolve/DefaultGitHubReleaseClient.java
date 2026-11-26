package com.allanlewis.homelabwatchman.resolve;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Latest release for a GitHub repository, via /releases/latest (which
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

    // Pre-release/nightly channel noise seen live across linuxserver repos
    // (bazarr: "development-v1.6.3-beta..."; others: "nightly-...") - never
    // a real match for a tag that's actually pinned in a file.
    private static final List<String> NOISE_PREFIXES = List.of("nightly", "develop");

    private static final Pattern LEADING_V_OR_RELEASE = Pattern.compile("^(v|release-)", Pattern.CASE_INSENSITIVE);

    private final RestClient restClient = RestClient.create();
    private final String token;

    public DefaultGitHubReleaseClient(@Value("${homelab.watchman.github.token:}") String token) {
        this.token = token;
    }

    @Override
    public Optional<Release> latestRelease(String owner, String repo) {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest";

        ReleaseResponse response = restClient.get()
                .uri(url)
                .headers(this::authenticate)
                .exchange((request, resp) -> resp.getStatusCode().is2xxSuccessful()
                        ? resp.bodyTo(ReleaseResponse.class)
                        : null);

        if (response == null || response.tagName() == null) {
            return Optional.empty();
        }

        int lastSlash = response.tagName().lastIndexOf('/');
        String tag = lastSlash == -1 ? response.tagName() : response.tagName().substring(lastSlash + 1);

        return Optional.of(new Release(tag, response.body(), response.htmlUrl()));
    }

    /**
     * Tries formal releases first (real notes, if any), then falls back to
     * plain git tags (e.g. Checkmk, which tags every version but never
     * publishes a GitHub Release for it) - a tag-only match still gets a
     * real, resolving link, just no notes body. One page of 100 each,
     * newest first per GitHub's default ordering - enough for any version
     * that was actually just resolved as "latest" by the registry; not
     * meant to find an old, long-superseded version.
     */
    @Override
    public Optional<Release> findMatchingRelease(String owner, String repo, String version) {
        ReleaseResponse[] releases = restClient.get()
                .uri("https://api.github.com/repos/" + owner + "/" + repo + "/releases?per_page=100")
                .headers(this::authenticate)
                .exchange((request, resp) -> resp.getStatusCode().is2xxSuccessful()
                        ? resp.bodyTo(ReleaseResponse[].class)
                        : null);

        if (releases != null) {
            Optional<ReleaseResponse> match = matchTag(List.of(releases), r -> r.tagName(), version);
            if (match.isPresent()) {
                return Optional.of(new Release(match.get().tagName(), match.get().body(), match.get().htmlUrl()));
            }
        }

        TagResponse[] tags = restClient.get()
                .uri("https://api.github.com/repos/" + owner + "/" + repo + "/tags?per_page=100")
                .headers(this::authenticate)
                .exchange((request, resp) -> resp.getStatusCode().is2xxSuccessful()
                        ? resp.bodyTo(TagResponse[].class)
                        : null);

        if (tags != null) {
            Optional<TagResponse> match = matchTag(List.of(tags), TagResponse::name, version);
            if (match.isPresent()) {
                String tagName = match.get().name();
                String url = "https://github.com/" + owner + "/" + repo + "/releases/tag/" + tagName;
                return Optional.of(new Release(tagName, null, url));
            }
        }

        return Optional.empty();
    }

    /**
     * True general-purpose match, not per-repo special-casing: strips a
     * leading "v" or "release-" (nginx's own convention) from both the tag
     * AND the pinned version before comparing - some images pin their own
     * "v" already (homepage's "v2.8.0", seerr's "v3.5.2", tautulli's
     * "v2.19.1", trilium's "v0.107.0"), others don't (jellyfin's "12.4"
     * against GitHub tag "v12.4"), so only stripping one side left the
     * "v"-pinned half of that list unmatched against its own exact tag.
     * Once normalized, accepts an exact match or a prefix match followed
     * by a non-digit - covering every real format confirmed live: plain
     * exact matches (vaultwarden's "1.37.5"), a trailing ".0" the pinned
     * value omits (whoami's "v1.12" matching tag "v1.12.0"), and
     * linuxserver's build-number suffixes regardless of which punctuation
     * each of their repos happens to use before it ("3.1.0.5799-ls170",
     * "v1.6.2-ls374", "4.1.4-r0-ls371" all match pinned
     * "3.1.0"/"1.6.2"/"4.1.4" respectively). Noise channels
     * (nightly/develop/development) are filtered first since they'd
     * otherwise out-rank the real release by sheer tag volume.
     */
    static <T> Optional<T> matchTag(List<T> items, Function<T, String> tagNameOf, String version) {
        String normalizedVersion = LEADING_V_OR_RELEASE.matcher(version).replaceFirst("");

        for (T item : items) {
            String tagName = tagNameOf.apply(item);
            if (tagName == null) {
                continue;
            }
            String lower = tagName.toLowerCase();
            if (NOISE_PREFIXES.stream().anyMatch(lower::startsWith)) {
                continue;
            }
            String normalizedTag = LEADING_V_OR_RELEASE.matcher(tagName).replaceFirst("");
            if (normalizedTag.equals(normalizedVersion)) {
                return Optional.of(item);
            }
            if (normalizedTag.startsWith(normalizedVersion)
                    && normalizedTag.length() > normalizedVersion.length()
                    && !Character.isDigit(normalizedTag.charAt(normalizedVersion.length()))) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    private void authenticate(HttpHeaders headers) {
        headers.set("Accept", "application/vnd.github+json");
        if (!token.isBlank()) {
            headers.setBearerAuth(token);
        }
    }

    private record ReleaseResponse(
            @JsonProperty("tag_name") String tagName,
            String body,
            @JsonProperty("html_url") String htmlUrl) {
    }

    private record TagResponse(String name) {
    }
}
