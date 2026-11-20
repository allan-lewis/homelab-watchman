package com.allanlewis.homelabwatchman.scan;

import java.nio.file.Path;

/**
 * A dependency reference found in the target repository, before any
 * resolution against upstream has happened.
 */
public record CandidateDependency(
        String identifier,
        Path file,
        String currentValue,
        String currentDigest
) {
}
