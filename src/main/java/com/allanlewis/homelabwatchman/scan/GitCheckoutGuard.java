package com.allanlewis.homelabwatchman.scan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * vcs always branches from the real remote base branch via the GitHub API,
 * so it's immune to local checkout staleness - but scan reads whatever's on
 * disk at --repo with no idea whether that's actually the base branch, or
 * up to date. A stale/wrong-branch checkout makes UpdatePlanner re-propose
 * already-merged changes (observed live: a checkout sitting on a feature
 * branch a couple of commits behind main caused exactly that). Hard-abort
 * the whole run rather than silently scanning untrustworthy state.
 */
@Component
public class GitCheckoutGuard {

    private final String baseBranch;

    public GitCheckoutGuard(@Value("${homelab.watchman.github.base-branch:main}") String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public void verify(Path repoRoot) {
        String currentBranch = git(repoRoot, "rev-parse", "--abbrev-ref", "HEAD");
        if (!currentBranch.equals(baseBranch)) {
            throw new IllegalStateException(repoRoot + " is on '" + currentBranch + "', not '" + baseBranch
                    + "'. Check out " + baseBranch + " before scanning.");
        }

        String localHead = git(repoRoot, "rev-parse", "HEAD");
        String remoteHead = remoteHeadSha(repoRoot);
        if (!localHead.equals(remoteHead)) {
            throw new IllegalStateException(repoRoot + "'s " + baseBranch + " (" + localHead
                    + ") doesn't match origin/" + baseBranch + " (" + remoteHead + "). Pull before scanning.");
        }
    }

    private String remoteHeadSha(Path repoRoot) {
        String output = git(repoRoot, "ls-remote", "origin", "refs/heads/" + baseBranch);
        int tab = output.indexOf('\t');
        if (tab == -1) {
            throw new IllegalStateException("Could not determine origin/" + baseBranch + " for " + repoRoot);
        }
        return output.substring(0, tab);
    }

    private String git(Path repoRoot, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));

        try {
            Process process = new ProcessBuilder(command)
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true)
                    .start();

            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git command interrupted", e);
        }
    }
}
