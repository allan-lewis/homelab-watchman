package com.allanlewis.homelabwatchman.scan;

import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Scans every {@code .nix} file under the repo root for refs of the form
 * {@code image = "registry/repo:tag@sha256:digest";}, e.g. the ~20 instances
 * under {@code nixos/modules/oci-containers/**} in allans-home-lab.
 *
 * TODO: walk .nix files, apply the regex, emit one CandidateDependency per
 * match with identifier = "registry/repo" and currentValue = tag.
 */
@Component
public class NixImageRefExtractor implements DependencyExtractor {

    @Override
    public List<CandidateDependency> extract(Path repoRoot) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
