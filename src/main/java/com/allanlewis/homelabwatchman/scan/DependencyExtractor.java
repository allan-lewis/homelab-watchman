package com.allanlewis.homelabwatchman.scan;

import java.nio.file.Path;
import java.util.List;

/**
 * Finds dependency references of one specific kind within a repository
 * checkout. Each implementation owns one extraction pattern (e.g. image refs
 * embedded in .nix files, or a single named version variable in a specific
 * file) so new ecosystems can be added without touching existing ones.
 */
public interface DependencyExtractor {

    List<CandidateDependency> extract(Path repoRoot);
}
