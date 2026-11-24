package com.allanlewis.homelabwatchman.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure logic, no registry involved - covers findNewerSameShapeVersion()'s
 * shape/carry rules directly, since the real anomaly and carry cases this
 * was tuned against don't reliably exist on the live registry at any given
 * time (e.g. nginx only ever has one post-boundary tag live at once, never
 * several at once the way a lagging scan would see them).
 */
class UpdatePlannerTest {

    private final UpdatePlanner planner = new UpdatePlanner(null, null, null);

    @Test
    void picksTheRealLatestEvenAfterSeveralReleasesCrossTheBoundary() {
        // Simulates not having scanned in a while: 1.31.9 is still pinned,
        // but 1.31.10, .11, and .12 have all since been released. The fix
        // must not stop at the first post-boundary release (1.31.10) just
        // because that's the one that's an exact +1 carry from 1.31.9.
        List<String> candidates = List.of("1.31.9", "1.31.10", "1.31.11", "1.31.12");
        assertThat(planner.findNewerSameShapeVersion(candidates, "1.31.9")).contains("1.31.12");
    }

    @Test
    void acceptsTheOrdinaryBoundaryCrossingOnItsOwn() {
        assertThat(planner.findNewerSameShapeVersion(List.of("1.31.9", "1.31.10"), "1.31.9"))
                .contains("1.31.10");
    }

    @Test
    void rejectsLidarrsAbandonedBuildNumberScheme() {
        assertThat(planner.findNewerSameShapeVersion(List.of("3.1.6", "8.1.2135"), "3.1.6")).isEmpty();
    }

    @Test
    void rejectsTransmissionsLeftoverDateStampedTag() {
        assertThat(planner.findNewerSameShapeVersion(List.of("4.1.4", "2021.11.18"), "4.1.4")).isEmpty();
    }

    @Test
    void acceptsProxmoxsMiddleSegmentGrowingADigit() {
        assertThat(planner.findNewerSameShapeVersion(List.of("0.97.0", "0.123.1"), "0.97.0")).contains("0.123.1");
    }

    @Test
    void boundaryCrossingRequiresCurrentToActuallyBeAtTheTopOfItsDigitRange() {
        // Same digit-length delta as the nginx case (1 -> 2 digits), but 4
        // isn't 9 - not a real boundary crossing, just a value that happens
        // to need an extra digit. Must still be rejected on its own, same
        // shape of problem as the transmission case but smaller numbers.
        assertThat(planner.findNewerSameShapeVersion(List.of("1.31.4", "1.31.10"), "1.31.4")).isEmpty();
    }
}
