package com.allanlewis.homelabwatchman.vcs;

import com.allanlewis.homelabwatchman.ai.PrText;
import com.allanlewis.homelabwatchman.plan.ProposedUpdate;
import java.nio.file.Path;

/**
 * Idempotent: safe to call every run for every pending update. One stable
 * branch per (identifier, kind) means a newer value supersedes an older
 * one in place (same PR, force-pushed branch) rather than piling up a new
 * PR each time - e.g. an open v1->v2 PR becomes v1->v3 in place once v3 is
 * released, instead of leaving a stale v1->v2 PR next to a new one.
 *
 * Split into two calls so callers only draft AI text (a real cost) when
 * something will actually change: checkStatus() is a cheap, read-only
 * GitHub lookup; apply() does the actual write and is only meant to be
 * called when checkStatus() returned CREATED or UPDATED.
 */
public interface PullRequestClient {

    PrOutcome checkStatus(Path repoRoot, ProposedUpdate update);

    PrOutcome apply(Path repoRoot, ProposedUpdate update, PrText text);

    record PrOutcome(Status status, String prUrl) {

        public enum Status {
            /** No PR existed for this (identifier, kind); checkStatus() says one would be opened. */
            CREATED,
            /** An open PR existed but targeted an older value; would be force-pushed + retitled in place. */
            UPDATED,
            /** An open PR already targets exactly what's being proposed; nothing to do. */
            UNCHANGED,
            /** A closed (not merged) PR already proposed this exact value - respecting that rejection. */
            SKIPPED_REJECTED
        }
    }
}
