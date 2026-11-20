package com.allanlewis.homelabwatchman.plan;

import com.allanlewis.homelabwatchman.resolve.GitHubReleaseClient;
import com.allanlewis.homelabwatchman.resolve.NaturalVersionComparator;
import com.allanlewis.homelabwatchman.resolve.OciRegistryClient;
import com.allanlewis.homelabwatchman.resolve.TerraformRegistryClient;
import com.allanlewis.homelabwatchman.scan.CandidateDependency;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Matches each CandidateDependency to its resolver (dispatched explicitly
 * by Ecosystem) and diffs current vs. latest to produce zero or one
 * ProposedUpdate per candidate.
 */
@Service
public class UpdatePlanner {

    // Excludes arch-prefixed ("amd64-...") and branch-like ("latest", "main")
    // tags, which aren't comparable to a plain pinned version.
    private static final Pattern VERSION_LIKE = Pattern.compile("^v?\\d[0-9a-zA-Z.\\-]*$");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+|\\D+");

    private final OciRegistryClient ociRegistryClient;
    private final GitHubReleaseClient gitHubReleaseClient;
    private final TerraformRegistryClient terraformRegistryClient;
    private final Comparator<String> versionComparator = new NaturalVersionComparator();

    public UpdatePlanner(
            OciRegistryClient ociRegistryClient,
            GitHubReleaseClient gitHubReleaseClient,
            TerraformRegistryClient terraformRegistryClient) {
        this.ociRegistryClient = ociRegistryClient;
        this.gitHubReleaseClient = gitHubReleaseClient;
        this.terraformRegistryClient = terraformRegistryClient;
    }

    public List<ProposedUpdate> plan(List<CandidateDependency> candidates) {
        List<ProposedUpdate> updates = new ArrayList<>();
        for (CandidateDependency candidate : candidates) {
            planOne(candidate).ifPresent(updates::add);
        }
        return updates;
    }

    private Optional<ProposedUpdate> planOne(CandidateDependency candidate) {
        return switch (candidate.ecosystem()) {
            case OCI_IMAGE -> planOciImage(candidate);
            case GITHUB_RELEASE -> planGitHubBackedVersion(candidate);
            case TERRAFORM_PROVIDER -> planTerraformProvider(candidate);
        };
    }

    private Optional<ProposedUpdate> planOciImage(CandidateDependency candidate) {
        Optional<String> newerTag = findNewerSameShapeVersion(
                ociRegistryClient.listTags(candidate.identifier()), candidate.currentValue());

        if (newerTag.isPresent()) {
            // No reliable way to map an arbitrary OCI image (e.g.
            // ghcr.io/linuxserver/sonarr) to its upstream changelog in this
            // MVP, so releaseNotesUrl/releaseNotes stay null here - the AI
            // prompt is written to handle that honestly rather than guess.
            return Optional.of(new ProposedUpdate(candidate, UpdateKind.VERSION_BUMP,
                    describeCurrent(candidate), candidate.identifier() + ":" + newerTag.get(),
                    null, null, newerTag.get()));
        }

        // currentDigest() == null means the file never pinned a digest at
        // all (e.g. a VERSION_BUMP merge only ever writes "identifier:tag",
        // no digest) - there's nothing to "refresh" in that case. Without
        // this guard, any resolved digest trivially satisfies
        // !digest.equals(null), so this falsely fired on every single run
        // for an unpinned tag regardless of whether anything upstream
        // actually changed - confirmed live: ghcr.io/gethomepage/homepage's
        // real digest for v2.8.0 was stable across repeated checks.
        if (candidate.currentDigest() == null) {
            return Optional.empty();
        }

        Optional<String> latestDigest = ociRegistryClient.resolveDigest(candidate.identifier(), candidate.currentValue());
        if (latestDigest.isPresent() && !latestDigest.get().equals(candidate.currentDigest())) {
            return Optional.of(new ProposedUpdate(candidate, UpdateKind.DIGEST_REFRESH,
                    describeCurrent(candidate),
                    candidate.identifier() + ":" + candidate.currentValue() + "@" + latestDigest.get(),
                    null, null, candidate.currentValue()));
        }

        return Optional.empty();
    }

    private Optional<ProposedUpdate> planGitHubBackedVersion(CandidateDependency candidate) {
        String[] ownerRepo = candidate.identifier().split("/", 2);

        return gitHubReleaseClient.latestRelease(ownerRepo[0], ownerRepo[1])
                .filter(release -> !release.tag().equals(candidate.currentValue()))
                .map(release -> new ProposedUpdate(candidate, UpdateKind.VERSION_BUMP,
                        describeCurrent(candidate), release.tag(), release.url(), release.notes(), release.tag()));
    }

    /**
     * Only ever bumps the floor constraint in providers.tf, never
     * .terraform.lock.hcl - see TerraformProviderExtractor for why. oldText/
     * newText are the exact quoted constraint text (e.g. '">= 0.97.0"'),
     * matching the literal source, not just the bare version.
     */
    private Optional<ProposedUpdate> planTerraformProvider(CandidateDependency candidate) {
        Optional<String> newerVersion = findNewerSameShapeVersion(
                terraformRegistryClient.listVersions(candidate.identifier()), candidate.currentValue());

        return newerVersion.map(version -> new ProposedUpdate(candidate, UpdateKind.VERSION_BUMP,
                "\">= " + candidate.currentValue() + "\"",
                "\">= " + version + "\"",
                null, null, version));
    }

    /**
     * Shared by OCI tags and Terraform provider versions: only consider
     * values with the identical non-digit "shape" as the current one -
     * otherwise a differently-shaped value (a Docker tag variant like
     * "1.31.9-trixie-perl", or a Terraform provider prerelease like
     * "0.123.1-rc1") can out-sort the real update. See skeleton() for why
     * digit-run LENGTH also has to match, not just digit-run position.
     */
    private Optional<String> findNewerSameShapeVersion(List<String> candidates, String currentValue) {
        String currentSkeleton = skeleton(currentValue);

        return candidates.stream()
                .filter(v -> VERSION_LIKE.matcher(v).matches())
                .filter(v -> skeleton(v).equals(currentSkeleton))
                .max(versionComparator)
                .filter(v -> versionComparator.compare(v, currentValue) > 0);
    }

    /**
     * Collapses every digit run except the LAST one to a single "#";
     * the last digit run keeps its exact length. Plain "#" everywhere was
     * too loose: tag histories mix versioning schemes (e.g. lidarr's old
     * "3.1.6" and a since-abandoned "8.1.2135" scheme, or transmission's
     * leftover 2021 date-stamped tags like "2021.11.18") that share shape
     * "#.#.#" but represent incomparable epochs - caught because their
     * LAST segment's digit count differs ("#.#.#" vs "#.#.####" vs
     * "#.#.##"). But preserving length on EVERY run was too strict the
     * other way: a real, legitimate minor-version bump can cross a digit-
     * count boundary in a middle segment (bpg/proxmox going 0.97.0 ->
     * 0.123.1, 2 digits -> 3 digits) without that being a different
     * versioning scheme at all - only the trailing/patch segment turning
     * out a wildly different length is the actual anomaly signal.
     */
    private static String skeleton(String tag) {
        Matcher matcher = DIGIT_RUN.matcher(tag);
        List<String> tokens = new ArrayList<>();
        int lastDigitTokenIndex = -1;
        while (matcher.find()) {
            String token = matcher.group();
            tokens.add(token);
            if (Character.isDigit(token.charAt(0))) {
                lastDigitTokenIndex = tokens.size() - 1;
            }
        }

        StringBuilder skeleton = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!Character.isDigit(token.charAt(0))) {
                skeleton.append(token);
            } else if (i == lastDigitTokenIndex) {
                skeleton.append("#".repeat(token.length()));
            } else {
                skeleton.append("#");
            }
        }
        return skeleton.toString();
    }

    /**
     * The same granularity ProposedUpdate.oldText()/newText() use for each
     * ecosystem. Exposed so callers can describe a candidate's current
     * state even when it has no pending update (e.g. for persisting full
     * DependencyState coverage, not just deltas).
     */
    public String describeCurrent(CandidateDependency candidate) {
        return candidate.currentDigest() != null
                ? candidate.identifier() + ":" + candidate.currentValue() + "@" + candidate.currentDigest()
                : candidate.currentValue();
    }
}
