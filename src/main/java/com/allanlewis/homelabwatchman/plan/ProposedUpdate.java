package com.allanlewis.homelabwatchman.plan;

import com.allanlewis.homelabwatchman.scan.CandidateDependency;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * newBareVersion is an explicit field, not derived from newText(): each
 * ecosystem's newText() has a different shape (full "repo:tag[@digest]"
 * for OCI images, a quoted ">= X.Y.Z" constraint for Terraform providers,
 * already-bare for GitHub releases), and guessing the bare value back out
 * of that got fragile fast. The planner already knows the bare value when
 * it builds each ProposedUpdate, so it just says so directly.
 */
public record ProposedUpdate(
        CandidateDependency candidate,
        UpdateKind kind,
        String oldText,
        String newText,
        String releaseNotesUrl,
        String releaseNotes,
        String newBareVersion
) {

    public Path file() {
        return candidate.file();
    }

    /** The bare old version/tag, with no registry/repo prefix, digest, or constraint operator. */
    public String oldBareVersion() {
        return candidate.currentValue();
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
