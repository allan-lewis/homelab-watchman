package com.allanlewis.homelabwatchman.resolve;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Speaks the standard OCI Distribution HTTP API. One implementation covers
 * docker.io, ghcr.io, and lscr.io: all three (lscr.io is in fact a pure
 * proxy in front of ghcr.io - same auth realm) follow the same
 * WWW-Authenticate discovery -> token exchange -> API call flow, so no
 * per-registry special-casing is needed beyond resolving the host.
 *
 * URIs are built as plain strings, not RestClient's {host}/{repo} template
 * variables: repository paths legitimately contain literal "/" characters,
 * which template expansion would percent-encode and break.
 */
@Component
public class DefaultOciRegistryClient implements OciRegistryClient {

    private static final Pattern AUTH_HEADER = Pattern.compile("realm=\"([^\"]+)\",service=\"([^\"]+)\"");
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");

    // Registries like ghcr.io paginate tags/list at 100 per page, in
    // registration order (not sorted) - a repo's newest tags can easily be
    // on page 3+. Safety cap, not an expected ceiling.
    private static final int MAX_PAGES = 50;

    private static final List<MediaType> MANIFEST_ACCEPT = List.of(
            MediaType.valueOf("application/vnd.oci.image.index.v1+json"),
            MediaType.valueOf("application/vnd.docker.distribution.manifest.list.v2+json"),
            MediaType.valueOf("application/vnd.docker.distribution.manifest.v2+json"),
            MediaType.valueOf("application/vnd.oci.image.manifest.v1+json"));

    private final RestClient restClient = RestClient.create();

    @Override
    public List<String> listTags(String repository) {
        Registry registry = Registry.parse(repository);
        String token = fetchToken(registry);

        List<String> allTags = new ArrayList<>();
        String url = "https://" + registry.apiHost() + "/v2/" + registry.repositoryPath() + "/tags/list";

        for (int page = 0; url != null && page < MAX_PAGES; page++) {
            String pageUrl = url;
            Page result = restClient.get()
                    .uri(pageUrl)
                    .headers(h -> h.setBearerAuth(token))
                    .exchange((request, response) -> new Page(
                            response.bodyTo(TagsListResponse.class),
                            response.getHeaders().getFirst(HttpHeaders.LINK)));

            if (result.body() != null && result.body().tags() != null) {
                allTags.addAll(result.body().tags());
            }

            url = nextPageUrl(registry.apiHost(), result.linkHeader());
        }

        return allTags;
    }

    private String nextPageUrl(String apiHost, String linkHeader) {
        if (linkHeader == null) {
            return null;
        }
        Matcher matcher = NEXT_LINK.matcher(linkHeader);
        if (!matcher.find()) {
            return null;
        }
        String next = matcher.group(1);
        return next.startsWith("http") ? next : "https://" + apiHost + next;
    }

    private record Page(TagsListResponse body, String linkHeader) {
    }

    @Override
    public Optional<String> resolveDigest(String repository, String tag) {
        Registry registry = Registry.parse(repository);
        String token = fetchToken(registry);

        String url = "https://" + registry.apiHost() + "/v2/" + registry.repositoryPath() + "/manifests/" + tag;

        return restClient.get()
                .uri(url)
                .headers(h -> {
                    h.setBearerAuth(token);
                    h.setAccept(MANIFEST_ACCEPT);
                })
                .exchange((request, response) -> response.getStatusCode().is2xxSuccessful()
                        ? Optional.ofNullable(response.getHeaders().getFirst("Docker-Content-Digest"))
                        : Optional.empty());
    }

    private String fetchToken(Registry registry) {
        AuthChallenge challenge = discoverAuth(registry);

        String url = challenge.realm() + "?service=" + challenge.service()
                + "&scope=repository:" + registry.repositoryPath() + ":pull";

        TokenResponse response = restClient.get().uri(url).retrieve().body(TokenResponse.class);

        if (response == null || response.token() == null) {
            throw new IllegalStateException("No token in response from " + challenge.realm());
        }
        return response.token();
    }

    private AuthChallenge discoverAuth(Registry registry) {
        String url = "https://" + registry.apiHost() + "/v2/";

        return restClient.get().uri(url).exchange((request, response) -> {
            String header = response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
            if (header == null) {
                throw new IllegalStateException("No WWW-Authenticate header from " + registry.apiHost());
            }
            Matcher matcher = AUTH_HEADER.matcher(header);
            if (!matcher.find()) {
                throw new IllegalStateException("Unparseable WWW-Authenticate header: " + header);
            }
            return new AuthChallenge(matcher.group(1), matcher.group(2));
        });
    }

    private record AuthChallenge(String realm, String service) {
    }

    private record TokenResponse(String token) {
    }

    private record TagsListResponse(String name, List<String> tags) {
    }

    private record Registry(String apiHost, String repositoryPath) {

        static Registry parse(String repository) {
            int firstSlash = repository.indexOf('/');
            String firstSegment = firstSlash == -1 ? repository : repository.substring(0, firstSlash);
            boolean hasHost = firstSegment.contains(".") || firstSegment.contains(":") || firstSegment.equals("localhost");

            if (!hasHost) {
                String path = repository.contains("/") ? repository : "library/" + repository;
                return new Registry("registry-1.docker.io", path);
            }

            String host = firstSegment.equals("docker.io") ? "registry-1.docker.io" : firstSegment;
            return new Registry(host, repository.substring(firstSlash + 1));
        }
    }
}
