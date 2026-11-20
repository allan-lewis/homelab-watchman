package com.allanlewis.homelabwatchman.plan;

import com.allanlewis.homelabwatchman.scan.CandidateDependency;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Matches each CandidateDependency to its resolver (OciRegistryClient or
 * GitHubReleaseClient, chosen by identifier shape) and diffs current vs.
 * latest to produce zero or one ProposedUpdate per candidate.
 *
 * TODO: wire in the resolvers once they have real implementations.
 */
@Service
public class UpdatePlanner {

    public List<ProposedUpdate> plan(List<CandidateDependency> candidates) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
