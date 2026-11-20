package com.allanlewis.homelabwatchman.plan;

import com.allanlewis.homelabwatchman.resolve.GitHubReleaseClient;
import com.allanlewis.homelabwatchman.resolve.NaturalVersionComparator;
import com.allanlewis.homelabwatchman.resolve.OciRegistryClient;
import com.allanlewis.homelabwatchman.scan.CandidateDependency;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Matches each CandidateDependency to its resolver and diffs current vs.
 * latest to produce zero or one ProposedUpdate per candidate.
 *
 * Dispatch is by currentDigest() presence, not identifier shape: only
 * NixImageRefExtractor ever sets a digest, so "has a digest" reliably means
 * "OCI image ref" for the extractor set this planner currently serves.
 */
@Service
public class UpdatePlanner {

    // Excludes arch-prefixed ("amd64-...") and branch-like ("latest", "main")
    // tags, which aren't comparable to a plain pinned version.
    private static final Pattern VERSION_LIKE = Pattern.compile("^v?\\d[0-9a-zA-Z.\\-]*$");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+|\\D+");

    private final OciRegistryClient ociRegistryClient;
    private final GitHubReleaseClient gitHubReleaseClient;
    private final Comparator<String> versionComparator = new NaturalVersionComparator();

    public UpdatePlanner(OciRegistryClient ociRegistryClient, GitHubReleaseClient gitHubReleaseClient) {
        this.ociRegistryClient = ociRegistryClient;
        this.gitHubReleaseClient = gitHubReleaseClient;
    }

    public List<ProposedUpdate> plan(List<CandidateDependency> candidates) {
        List<ProposedUpdate> updates = new ArrayList<>();
        for (CandidateDependency candidate : candidates) {
            planOne(candidate).ifPresent(updates::add);
        }
        return updates;
    }

    private Optional<ProposedUpdate> planOne(CandidateDependency candidate) {
        return candidate.currentDigest() != null
                ? planOciImage(candidate)
                : planGitHubBackedVersion(candidate);
    }

    private Optional<ProposedUpdate> planOciImage(CandidateDependency candidate) {
        String currentSkeleton = skeleton(candidate.currentValue());

        Optional<String> newerTag = ociRegistryClient.listTags(candidate.identifier()).stream()
                .filter(t -> VERSION_LIKE.matcher(t).matches())
                // Only compare tags with the identical non-digit "shape" as the
                // pinned tag - otherwise a variant like "1.31.9-trixie-perl" can
                // out-sort the real update, "1.31.9", for a plain "1.31.8" pin.
                .filter(t -> skeleton(t).equals(currentSkeleton))
                .max(versionComparator)
                .filter(t -> versionComparator.compare(t, candidate.currentValue()) > 0);

        if (newerTag.isPresent()) {
            // No reliable way to map an arbitrary OCI image (e.g.
            // ghcr.io/linuxserver/sonarr) to its upstream changelog in this
            // MVP, so releaseNotesUrl/releaseNotes stay null here - the AI
            // prompt is written to handle that honestly rather than guess.
            return Optional.of(new ProposedUpdate(candidate, UpdateKind.VERSION_BUMP,
                    describeCurrent(candidate), candidate.identifier() + ":" + newerTag.get(), null, null));
        }

        Optional<String> latestDigest = ociRegistryClient.resolveDigest(candidate.identifier(), candidate.currentValue());
        if (latestDigest.isPresent() && !latestDigest.get().equals(candidate.currentDigest())) {
            return Optional.of(new ProposedUpdate(candidate, UpdateKind.DIGEST_REFRESH,
                    describeCurrent(candidate),
                    candidate.identifier() + ":" + candidate.currentValue() + "@" + latestDigest.get(),
                    null, null));
        }

        return Optional.empty();
    }

    /**
     * Collapses digit runs to a same-length run of "#", preserving digit
     * COUNT (not value). Plain "#" for every digit run was too loose: tag
     * histories mix versioning schemes (e.g. lidarr's old "3.1.6" and a
     * since-abandoned "8.1.2135" scheme, or transmission's leftover 2021
     * date-stamped tags like "2021.11.18") that share shape "#.#.#" but
     * represent incomparable epochs. Preserving run length tells "3.1.6"
     * apart from "8.1.2135" (#.#.# vs #.#.####) and from "2021.11.18"
     * (####.##.##) without needing per-tag publish timestamps.
     */
    private static String skeleton(String tag) {
        StringBuilder skeleton = new StringBuilder();
        Matcher matcher = DIGIT_RUN.matcher(tag);
        while (matcher.find()) {
            String token = matcher.group();
            skeleton.append(Character.isDigit(token.charAt(0)) ? "#".repeat(token.length()) : token);
        }
        return skeleton.toString();
    }

    /**
     * The same granularity ProposedUpdate.oldText()/newText() use: full
     * "repo:tag@digest" for OCI image refs, bare version string for
     * GitHub-release-backed deps. Exposed so callers can describe a
     * candidate's current state even when it has no pending update (e.g.
     * for persisting full DependencyState coverage, not just deltas).
     */
    public String describeCurrent(CandidateDependency candidate) {
        return candidate.currentDigest() != null
                ? candidate.identifier() + ":" + candidate.currentValue() + "@" + candidate.currentDigest()
                : candidate.currentValue();
    }

    private Optional<ProposedUpdate> planGitHubBackedVersion(CandidateDependency candidate) {
        String[] ownerRepo = candidate.identifier().split("/", 2);

        return gitHubReleaseClient.latestRelease(ownerRepo[0], ownerRepo[1])
                .filter(release -> !release.tag().equals(candidate.currentValue()))
                .map(release -> new ProposedUpdate(candidate, UpdateKind.VERSION_BUMP,
                        describeCurrent(candidate), release.tag(), release.url(), release.notes()));
    }
}
