package com.allanlewis.homelabwatchman.vcs;

import com.allanlewis.homelabwatchman.ai.PrText;
import com.allanlewis.homelabwatchman.plan.ProposedUpdate;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * TODO: use JGit to create a branch named from update.candidate().identifier()
 * + update.kind(), apply oldText -> newText in update.file(), commit, push;
 * then POST to the GitHub REST API to open the PR, returning its URL.
 */
@Component
public class GitHubPullRequestClient implements PullRequestClient {

    @Override
    public String openPullRequest(Path repoRoot, ProposedUpdate update, PrText text) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
