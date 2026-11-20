package com.allanlewis.homelabwatchman.vcs;

import com.allanlewis.homelabwatchman.ai.PrText;
import com.allanlewis.homelabwatchman.plan.ProposedUpdate;
import java.nio.file.Path;

/**
 * Branches/commits via JGit against a local checkout, then opens the PR via
 * the GitHub REST API.
 */
public interface PullRequestClient {

    String openPullRequest(Path repoRoot, ProposedUpdate update, PrText text);
}
