package com.allanlewis.homelabwatchman.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure logic for classifyVersionDelta() - no ChatClient involved. */
class ChatClientPrTextGeneratorTest {

    @Test
    void classifiesAMajorBump() {
        assertThat(ChatClientPrTextGenerator.classifyVersionDelta("6.4.4", "7.0.3")).contains("MAJOR");
    }

    @Test
    void classifiesAMinorBump() {
        assertThat(ChatClientPrTextGenerator.classifyVersionDelta("3.0.2", "3.1.0")).contains("MINOR");
    }

    @Test
    void classifiesAPatchBump() {
        assertThat(ChatClientPrTextGenerator.classifyVersionDelta("1.31.9", "1.31.10")).contains("PATCH");
    }

    @Test
    void classifiesExtraTrailingSegmentAsPatch() {
        // e.g. jellyfin's "12.4" -> "12.4.1"
        assertThat(ChatClientPrTextGenerator.classifyVersionDelta("12.4", "12.4.1")).contains("PATCH");
    }

    @Test
    void returnsEmptyWhenVersionsAreIdentical() {
        assertThat(ChatClientPrTextGenerator.classifyVersionDelta("1.37.5", "1.37.5")).isEmpty();
    }

    @Test
    void returnsEmptyWhenEitherSideHasNoNumbers() {
        assertThat(ChatClientPrTextGenerator.classifyVersionDelta("latest", "1.0.0")).isEmpty();
    }
}
