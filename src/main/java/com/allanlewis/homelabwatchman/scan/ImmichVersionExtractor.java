package com.allanlewis.homelabwatchman.scan;

import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads {@code nixos/profiles/immich/default.nix} for the
 * {@code immichVersion = "vX.Y.Z";} let-binding, which tracks
 * immich-app/immich release tags.
 *
 * TODO: locate the file relative to repoRoot, extract the let-binding value.
 */
@Component
public class ImmichVersionExtractor implements DependencyExtractor {

    static final String TARGET_FILE = "nixos/profiles/immich/default.nix";
    static final String VARIABLE_NAME = "immichVersion";

    @Override
    public List<CandidateDependency> extract(Path repoRoot) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
