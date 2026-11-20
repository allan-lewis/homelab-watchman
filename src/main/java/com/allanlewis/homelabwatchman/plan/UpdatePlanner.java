package com.allanlewis.homelabwatchman.plan;

import com.allanlewis.homelabwatchman.resolve.GitHubReleaseClient;
import com.allanlewis.homelabwatchman.resolve.NaturalVersionComparator;
import com.allanlewis.homelabwatchman.resolve.OciRegistryClient;
import com.allanlewis.homelabwatchman.scan.CandidateDependency;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
            return Optional.of(new ProposedUpdate(candidate, UpdateKind.VERSION_BUMP,
                    oldImageRef(candidate), candidate.identifier() + ":" + newerTag.get(), null));
        }

        Optional<String> latestDigest = ociRegistryClient.resolveDigest(candidate.identifier(), candidate.currentValue());
        if (latestDigest.isPresent() && !latestDigest.get().equals(candidate.currentDigest())) {
            return Optional.of(new ProposedUpdate(candidate, UpdateKind.DIGEST_REFRESH,
                    oldImageRef(candidate),
                    candidate.identifier() + ":" + candidate.currentValue() + "@" + latestDigest.get(),
                    null));
        }

        return Optional.empty();
    }

    private static String skeleton(String tag) {
        return tag.replaceAll("\\d+", "#");
    }

    private String oldImageRef(CandidateDependency candidate) {
        return candidate.identifier() + ":" + candidate.currentValue()
                + (candidate.currentDigest() != null ? "@" + candidate.currentDigest() : "");
    }

    private Optional<ProposedUpdate> planGitHubBackedVersion(CandidateDependency candidate) {
        String[] ownerRepo = candidate.identifier().split("/", 2);

        return gitHubReleaseClient.latestReleaseTag(ownerRepo[0], ownerRepo[1])
                .filter(tag -> !tag.equals(candidate.currentValue()))
                .map(tag -> new ProposedUpdate(candidate, UpdateKind.VERSION_BUMP,
                        candidate.currentValue(), tag, null));
    }
}
