package com.allanlewis.homelabwatchman.plan;

import com.allanlewis.homelabwatchman.scan.CandidateDependency;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record ProposedUpdate(
        CandidateDependency candidate,
        UpdateKind kind,
        String oldText,
        String newText,
        String releaseNotesUrl,
        String releaseNotes
) {

    public Path file() {
        return candidate.file();
    }

    /** The bare old version/tag, with no registry/repo prefix or digest. */
    public String oldBareVersion() {
        return candidate.currentValue();
    }

    /**
     * The bare new version/tag for a VERSION_BUMP, with no registry/repo
     * prefix or digest. For OCI image refs, newText() is "identifier:tag" -
     * strip the identifier; for GitHub-release-backed deps, newText() is
     * already bare.
     */
    public String newBareVersion() {
        return candidate.currentDigest() != null
                ? newText.substring(newText.lastIndexOf(':') + 1)
                : newText;
    }

    /**
     * First 7 hex chars of the new digest for a DIGEST_REFRESH, matching
     * Renovate's own "Docker digest to e0c5d45"-style abbreviation - only
     * meaningful when kind() is DIGEST_REFRESH, since that's the only case
     * newText() carries an "@sha256:..." suffix at all.
     */
    public String newDigestShort() {
        int at = newText.indexOf("@sha256:");
        String digest = newText.substring(at + "@sha256:".length());
        return digest.substring(0, Math.min(7, digest.length()));
    }

    /**
     * mergeFunction (a, b) -> a: candidates compare equal only when
     * identifier/file/value/digest are all identical (e.g. twingate's
     * duplicated container block), so either side is an identical update.
     */
    public static Map<CandidateDependency, ProposedUpdate> index(List<ProposedUpdate> updates) {
        return updates.stream().collect(Collectors.toMap(ProposedUpdate::candidate, Function.identity(), (a, b) -> a));
    }
}
