package com.allanlewis.homelabwatchman.scan;

import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads {@code nixos/profiles/authentik/default.nix} for the
 * {@code authentikVersion = "YYYY.M.P";} let-binding, which tracks
 * goauthentik/authentik release tags.
 *
 * TODO: locate the file relative to repoRoot, extract the let-binding value.
 */
@Component
public class AuthentikVersionExtractor implements DependencyExtractor {

    static final String TARGET_FILE = "nixos/profiles/authentik/default.nix";
    static final String VARIABLE_NAME = "authentikVersion";

    @Override
    public List<CandidateDependency> extract(Path repoRoot) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
