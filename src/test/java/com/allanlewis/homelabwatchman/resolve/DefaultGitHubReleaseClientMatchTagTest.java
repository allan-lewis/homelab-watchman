package com.allanlewis.homelabwatchman.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure logic for matchTag(), tested against real tag samples pulled live
 * from each repo while building the OciImageRepositories mapping - every
 * case here is a confirmed real format, not a guess at one.
 */
class DefaultGitHubReleaseClientMatchTagTest {

    @Test
    void matchesExactTag() {
        assertThat(DefaultGitHubReleaseClient.matchTag(List.of("1.37.5", "1.37.4"), s -> s, "1.37.5"))
                .contains("1.37.5");
    }

    @Test
    void stripsLeadingVFromTagOnly() {
        // jellyfin pins the bare version; GitHub tags it with a "v".
        assertThat(DefaultGitHubReleaseClient.matchTag(List.of("v12.4", "v12.3"), s -> s, "12.4"))
                .contains("v12.4");
    }

    @Test
    void stripsLeadingVFromBothSidesWhenPinnedValueAlreadyHasOne() {
        // homepage/seerr/tautulli/trilium pin their own "v" and the tag matches it exactly.
        assertThat(DefaultGitHubReleaseClient.matchTag(List.of("v2.8.0", "v2.7.0"), s -> s, "v2.8.0"))
                .contains("v2.8.0");
    }

    @Test
    void stripsReleaseDashPrefix() {
        assertThat(DefaultGitHubReleaseClient.matchTag(
                        List.of("release-1.31.10", "release-1.31.9"), s -> s, "1.31.10"))
                .contains("release-1.31.10");
    }

    @Test
    void acceptsTrailingSegmentTheirTagHasButOursOmits() {
        // traefik/whoami pins "v1.12"; the real tag is "v1.12.0".
        assertThat(DefaultGitHubReleaseClient.matchTag(List.of("v1.12.0", "v1.11.0"), s -> s, "v1.12"))
                .contains("v1.12.0");
    }

    @Test
    void matchesLinuxserverDottedBuildSuffix() {
        assertThat(DefaultGitHubReleaseClient.matchTag(
                        List.of("3.1.0.5799-ls170", "3.0.2.5700-ls150"), s -> s, "3.1.0"))
                .contains("3.1.0.5799-ls170");
    }

    @Test
    void matchesLinuxserverVPrefixedDashBuildSuffix() {
        assertThat(DefaultGitHubReleaseClient.matchTag(
                        List.of("v1.6.2-ls374", "v1.6.1-ls370"), s -> s, "1.6.2"))
                .contains("v1.6.2-ls374");
    }

    @Test
    void matchesLinuxserverRZeroInfixBuildSuffix() {
        assertThat(DefaultGitHubReleaseClient.matchTag(
                        List.of("4.1.4-r0-ls371", "4.1.3-r0-ls366"), s -> s, "4.1.4"))
                .contains("4.1.4-r0-ls371");
    }

    @Test
    void rejectsNightlyAndDevelopmentChannelNoise() {
        assertThat(DefaultGitHubReleaseClient.matchTag(
                        List.of("nightly-3.1.0.5797-ls32", "development-v1.6.3-beta.40-ls39"), s -> s, "3.1.0"))
                .isEmpty();
    }

    @Test
    void doesNotMatchAnUnrelatedVersion() {
        assertThat(DefaultGitHubReleaseClient.matchTag(List.of("4.0.19", "4.0.18"), s -> s, "4.0.20"))
                .isEmpty();
    }

    @Test
    void prefersFirstMatchingTagWhenSeveralBuildRevisionsShareAVersion() {
        // GitHub returns releases newest-first; the match must not skip past the first hit.
        assertThat(DefaultGitHubReleaseClient.matchTag(
                        List.of("4.0.20.3014-ls6", "4.0.20.3014-ls5", "4.0.20.3014-ls4"), s -> s, "4.0.20"))
                .contains("4.0.20.3014-ls6");
    }
}
