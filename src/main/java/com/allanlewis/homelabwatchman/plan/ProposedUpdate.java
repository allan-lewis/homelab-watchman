package com.allanlewis.homelabwatchman.plan;

import com.allanlewis.homelabwatchman.scan.CandidateDependency;
import java.nio.file.Path;

public record ProposedUpdate(
        CandidateDependency candidate,
        UpdateKind kind,
        String oldText,
        String newText,
        String releaseNotesUrl
) {

    public Path file() {
        return candidate.file();
    }
}
