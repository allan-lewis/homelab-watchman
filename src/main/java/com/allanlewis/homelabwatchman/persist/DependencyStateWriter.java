package com.allanlewis.homelabwatchman.persist;

import com.allanlewis.homelabwatchman.plan.ProposedUpdate;
import com.allanlewis.homelabwatchman.plan.UpdatePlanner;
import com.allanlewis.homelabwatchman.scan.CandidateDependency;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Upserts DependencyState for every candidate from a run, not just the ones
 * with a pending update - so a future GUI has full coverage to render, not
 * just deltas.
 */
@Component
public class DependencyStateWriter {

    private final DependencyStateRepository repository;
    private final UpdatePlanner updatePlanner;

    public DependencyStateWriter(DependencyStateRepository repository, UpdatePlanner updatePlanner) {
        this.repository = repository;
        this.updatePlanner = updatePlanner;
    }

    public void save(List<CandidateDependency> candidates, Map<CandidateDependency, ProposedUpdate> byCandidate) {
        Instant now = Instant.now();

        for (CandidateDependency candidate : candidates) {
            String file = candidate.file().toString();
            DependencyState state = repository.findByIdentifierAndFile(candidate.identifier(), file)
                    .orElseGet(DependencyState::new);

            ProposedUpdate update = byCandidate.get(candidate);
            String current = updatePlanner.describeCurrent(candidate);

            state.setIdentifier(candidate.identifier());
            state.setFile(file);
            state.setCurrentValue(current);
            state.setLatestKnownValue(update != null ? update.newText() : current);
            state.setUpdateKind(update != null ? update.kind() : null);
            state.setLastCheckedAt(now);

            repository.save(state);
        }
    }
}
