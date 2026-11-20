package com.allanlewis.homelabwatchman.vcs;

import com.allanlewis.homelabwatchman.ai.PrText;
import com.allanlewis.homelabwatchman.plan.ProposedUpdate;
import com.allanlewis.homelabwatchman.plan.UpdateKind;
import com.allanlewis.homelabwatchman.vcs.PullRequestClient.PrOutcome.Status;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GitHubPullRequestClient implements PullRequestClient {

    private static final String LABEL = "watchman";

    private final GitHubApi api;
    private final String baseBranch;

    public GitHubPullRequestClient(GitHubApi api, @Value("${homelab.watchman.github.base-branch:main}") String baseBranch) {
        this.api = api;
        this.baseBranch = baseBranch;
    }

    @Override
    public PrOutcome checkStatus(Path repoRoot, ProposedUpdate update) {
        String branch = branchName(update.candidate().identifier(), update.kind());
        String relativePath = repoRoot.relativize(update.file()).toString();

        Optional<GitHubApi.PullRequest> openPr = api.findOpenPrByBranch(branch);
        if (openPr.isPresent()) {
            boolean alreadyCurrent = api.getFileContent(relativePath, branch)
                    .map(f -> f.content().contains(update.newText()))
                    .orElse(false);
            return alreadyCurrent
                    ? new PrOutcome(Status.UNCHANGED, openPr.get().htmlUrl())
                    : new PrOutcome(Status.UPDATED, openPr.get().htmlUrl());
        }

        if (wasPreviouslyRejected(branch, update)) {
            return new PrOutcome(Status.SKIPPED_REJECTED, null);
        }

        return new PrOutcome(Status.CREATED, null);
    }

    /** Only call when checkStatus() returned CREATED or UPDATED. */
    @Override
    public PrOutcome apply(Path repoRoot, ProposedUpdate update, PrText text) {
        String branch = branchName(update.candidate().identifier(), update.kind());
        String relativePath = repoRoot.relativize(update.file()).toString();

        Optional<GitHubApi.PullRequest> openPr = api.findOpenPrByBranch(branch);
        resetBranchToBaseAndApplyEdit(branch, relativePath, update, text);

        PrOutcome outcome;
        if (openPr.isPresent()) {
            api.updatePr(openPr.get().number(), text.title(), text.body());
            outcome = new PrOutcome(Status.UPDATED, openPr.get().htmlUrl());
        } else {
            GitHubApi.PullRequest pr = api.createPr(branch, baseBranch, text.title(), text.body());
            api.ensureLabelExists(LABEL);
            api.addLabel(pr.number(), LABEL);
            outcome = new PrOutcome(Status.CREATED, pr.htmlUrl());
        }

        if (update.kind() == UpdateKind.VERSION_BUMP) {
            supersedeDigestRefresh(update);
        }

        return outcome;
    }

    private void resetBranchToBaseAndApplyEdit(String branch, String relativePath, ProposedUpdate update, PrText text) {
        String baseSha = api.getBranchSha(baseBranch);
        api.createOrResetBranch(branch, baseSha);

        GitHubApi.FileContent file = api.getFileContent(relativePath, branch)
                .orElseThrow(() -> new IllegalStateException("File not found on " + baseBranch + ": " + relativePath));

        if (!file.content().contains(update.oldText())) {
            throw new IllegalStateException(
                    "Expected text not found in " + relativePath + " on " + baseBranch + ": " + update.oldText());
        }

        String newContent = file.content().replace(update.oldText(), update.newText());
        api.putFileContent(relativePath, branch, newContent, file.sha(), text.title());
    }

    /**
     * A version bump makes any still-open digest-refresh PR for the same
     * identifier moot: merging it would pin a tag that's about to be
     * replaced anyway, and once the version-bump PR merges, the
     * digest-refresh diff no longer applies. Close it with a trail.
     */
    private void supersedeDigestRefresh(ProposedUpdate versionBump) {
        String digestBranch = branchName(versionBump.candidate().identifier(), UpdateKind.DIGEST_REFRESH);
        api.findOpenPrByBranch(digestBranch).ifPresent(pr -> {
            api.addComment(pr.number(), "Superseded by a version bump to " + versionBump.newBareVersion() + ".");
            api.closePr(pr.number());
        });
    }

    /**
     * GitHub's "closed" state covers both merged and rejected PRs, but
     * that ambiguity doesn't matter here: if this exact target had
     * actually been merged, the file would already show it and
     * UpdatePlanner wouldn't be proposing it again at all. So a closed PR
     * whose title names our exact target, while we're still proposing
     * that target, can only mean it was closed without merging - a human
     * rejection to respect, not resurrect. A later, different target
     * (newer version) is unaffected, since its title won't match.
     */
    private boolean wasPreviouslyRejected(String branch, ProposedUpdate update) {
        List<GitHubApi.PullRequest> closed = api.findPrsByBranch(branch, "closed");
        return closed.stream().anyMatch(pr -> pr.title() != null && pr.title().contains(update.newBareVersion()));
    }

    private String branchName(String identifier, UpdateKind kind) {
        String slug = identifier.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        String kindSegment = kind == UpdateKind.VERSION_BUMP ? "version-bump" : "digest-refresh";
        return "watchman/" + kindSegment + "/" + slug;
    }
}
