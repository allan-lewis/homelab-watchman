package com.allanlewis.homelabwatchman.vcs;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper over the GitHub REST API operations vcs needs. Deliberately
 * API-only, no local git checkout: avoids ever touching the real local
 * clone (which has its own in-progress branch/commits), and PUTting file
 * contents via the Contents API creates the commit server-side in one
 * call, with no local git state at all.
 */
@Component
public class GitHubApi {

    private final RestClient restClient = RestClient.create();
    private final String token;
    private final String owner;
    private final String repo;

    public GitHubApi(
            @Value("${homelab.watchman.github.token}") String token,
            @Value("${homelab.watchman.github.repository}") String repository) {
        this.token = token;
        String[] parts = repository.split("/", 2);
        this.owner = parts[0];
        this.repo = parts[1];
    }

    public String getBranchSha(String branch) {
        return restClient.get()
                .uri(api("/git/ref/heads/" + branch))
                .headers(this::authHeaders)
                .retrieve()
                .body(RefResponse.class)
                .object()
                .sha();
    }

    public void createOrResetBranch(String branch, String sha) {
        boolean exists = restClient.get()
                .uri(api("/git/ref/heads/" + branch))
                .headers(this::authHeaders)
                .exchange((req, resp) -> resp.getStatusCode().is2xxSuccessful());

        if (exists) {
            restClient.patch()
                    .uri(api("/git/refs/heads/" + branch))
                    .headers(this::authHeaders)
                    .body(new UpdateRefRequest(sha, true))
                    .retrieve()
                    .toBodilessEntity();
        } else {
            restClient.post()
                    .uri(api("/git/refs"))
                    .headers(this::authHeaders)
                    .body(new CreateRefRequest("refs/heads/" + branch, sha))
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    public Optional<FileContent> getFileContent(String path, String branch) {
        return restClient.get()
                .uri(api("/contents/" + path + "?ref=" + branch))
                .headers(this::authHeaders)
                .exchange((req, resp) -> {
                    if (!resp.getStatusCode().is2xxSuccessful()) {
                        return Optional.empty();
                    }
                    ContentResponse content = resp.bodyTo(ContentResponse.class);
                    return Optional.of(new FileContent(decode(content.content()), content.sha()));
                });
    }

    public void putFileContent(String path, String branch, String newContent, String existingBlobSha, String commitMessage) {
        restClient.put()
                .uri(api("/contents/" + path))
                .headers(this::authHeaders)
                .body(new PutContentRequest(commitMessage, encode(newContent), existingBlobSha, branch))
                .retrieve()
                .toBodilessEntity();
    }

    /** At most one PR should ever be open for a given head branch. */
    public Optional<PullRequest> findOpenPrByBranch(String branch) {
        List<PullRequest> prs = findPrsByBranch(branch, "open");
        return prs.isEmpty() ? Optional.empty() : Optional.of(prs.get(0));
    }

    /**
     * Closed PRs can accumulate for the same branch name over time (we
     * reuse branch names across runs), so callers that need "was any past
     * PR on this branch about this exact value" should scan the full list.
     */
    public List<PullRequest> findPrsByBranch(String branch, String state) {
        List<PullRequest> prs = restClient.get()
                .uri(api("/pulls?head=" + owner + ":" + branch + "&state=" + state))
                .headers(this::authHeaders)
                .retrieve()
                .body(new ParameterizedTypeReference<List<PullRequest>>() {
                });
        return prs == null ? List.of() : prs;
    }

    public PullRequest createPr(String branch, String base, String title, String body) {
        return restClient.post()
                .uri(api("/pulls"))
                .headers(this::authHeaders)
                .body(new CreatePrRequest(title, body, branch, base))
                .retrieve()
                .body(PullRequest.class);
    }

    public void updatePr(long number, String title, String body) {
        restClient.patch()
                .uri(api("/pulls/" + number))
                .headers(this::authHeaders)
                .body(new UpdatePrRequest(title, body))
                .retrieve()
                .toBodilessEntity();
    }

    public void closePr(long number) {
        setPrState(number, "closed");
    }

    /**
     * Force-updating a branch ref via this low-level Git Data API (as
     * createOrResetBranch does) does NOT trigger GitHub's normal PR
     * "synchronize" event the way a real `git push --force` does - an
     * already-open PR's cached head/diff/changed_files silently goes
     * stale and stays that way indefinitely (confirmed live: still stale
     * after 25+ seconds). Closing and reopening the PR is the standard
     * workaround; it forces GitHub to recompute against the real current
     * branch tip. Call after resetting a branch under an open PR.
     */
    public void resyncPr(long number) {
        setPrState(number, "closed");
        setPrState(number, "open");
    }

    private void setPrState(long number, String state) {
        restClient.patch()
                .uri(api("/pulls/" + number))
                .headers(this::authHeaders)
                .body(new SetPrStateRequest(state))
                .retrieve()
                .toBodilessEntity();
    }

    public void ensureLabelExists(String name) {
        boolean exists = restClient.get()
                .uri(api("/labels/" + name))
                .headers(this::authHeaders)
                .exchange((req, resp) -> resp.getStatusCode().is2xxSuccessful());

        if (!exists) {
            restClient.post()
                    .uri(api("/labels"))
                    .headers(this::authHeaders)
                    .body(new CreateLabelRequest(name, "5319e7", "Opened by homelab-watchman"))
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    public void addLabel(long issueNumber, String label) {
        restClient.post()
                .uri(api("/issues/" + issueNumber + "/labels"))
                .headers(this::authHeaders)
                .body(new AddLabelsRequest(List.of(label)))
                .retrieve()
                .toBodilessEntity();
    }

    public void addComment(long issueNumber, String comment) {
        restClient.post()
                .uri(api("/issues/" + issueNumber + "/comments"))
                .headers(this::authHeaders)
                .body(new AddCommentRequest(comment))
                .retrieve()
                .toBodilessEntity();
    }

    /** Merged PRs we created, found via the given label - independent of whatever's currently pending. */
    public List<Long> findMergedPrNumbers(String label) {
        String query = "repo:" + owner + "/" + repo + " is:pull-request is:merged label:" + label;

        // Not uri(String): that re-parses a hand-encoded string through
        // UriComponentsBuilder, which treats "+" (URLEncoder's space
        // encoding) as a literal character rather than space - GitHub then
        // sees one run-on token instead of separate qualifiers. The
        // structured builder encodes the query param value correctly.
        SearchResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https").host("api.github.com").path("/search/issues")
                        .queryParam("q", query)
                        .build())
                .headers(this::authHeaders)
                .retrieve()
                .body(SearchResponse.class);
        return response == null || response.items() == null
                ? List.of()
                : response.items().stream().map(SearchItem::number).toList();
    }

    public PullRequest getPr(long number) {
        return restClient.get()
                .uri(api("/pulls/" + number))
                .headers(this::authHeaders)
                .retrieve()
                .body(PullRequest.class);
    }

    /** @return true if the branch existed and was deleted, false if it was already gone. */
    public boolean deleteBranchIfExists(String branch) {
        boolean exists = restClient.get()
                .uri(api("/git/ref/heads/" + branch))
                .headers(this::authHeaders)
                .exchange((req, resp) -> resp.getStatusCode().is2xxSuccessful());

        if (exists) {
            restClient.delete()
                    .uri(api("/git/refs/heads/" + branch))
                    .headers(this::authHeaders)
                    .retrieve()
                    .toBodilessEntity();
        }

        return exists;
    }

    private String api(String path) {
        return "https://api.github.com/repos/" + owner + "/" + repo + path;
    }

    private void authHeaders(HttpHeaders headers) {
        headers.setBearerAuth(token);
        headers.set("Accept", "application/vnd.github+json");
    }

    private static String decode(String base64) {
        return new String(Base64.getMimeDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private static String encode(String content) {
        return Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    public record FileContent(String content, String sha) {
    }

    public record PullRequest(
            long number,
            String state,
            String title,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("merged_at") String mergedAt,
            Head head) {

        public boolean isMerged() {
            return mergedAt != null;
        }

        public record Head(String ref) {
        }
    }

    private record SearchResponse(List<SearchItem> items) {
    }

    private record SearchItem(long number) {
    }

    private record RefResponse(RefObject object) {
    }

    private record RefObject(String sha) {
    }

    private record CreateRefRequest(String ref, String sha) {
    }

    private record UpdateRefRequest(String sha, boolean force) {
    }

    private record ContentResponse(String content, String sha) {
    }

    private record PutContentRequest(String message, String content, String sha, String branch) {
    }

    private record CreatePrRequest(String title, String body, String head, String base) {
    }

    private record UpdatePrRequest(String title, String body) {
    }

    private record SetPrStateRequest(String state) {
    }

    private record AddLabelsRequest(List<String> labels) {
    }

    private record CreateLabelRequest(String name, String color, String description) {
    }

    private record AddCommentRequest(String body) {
    }
}
