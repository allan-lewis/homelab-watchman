package com.allanlewis.homelabwatchman.scan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Scans every {@code .nix} file under the repo root for refs of the form
 * {@code image = "registry/repo:tag@sha256:digest";}, e.g. the ~20 instances
 * under {@code nixos/modules/oci-containers/**} in allans-home-lab.
 */
@Component
public class NixImageRefExtractor implements DependencyExtractor {

    private static final Pattern IMAGE_REF = Pattern.compile(
            "image\\s*=\\s*\"([^:@\"\\s]+(?:/[^:@\"\\s]+)*):([^@\"\\s]+)(?:@(sha256:[a-f0-9]+))?\"");

    @Override
    public List<CandidateDependency> extract(Path repoRoot) {
        List<CandidateDependency> found = new ArrayList<>();
        try (var paths = Files.walk(repoRoot)) {
            paths.filter(p -> p.toString().endsWith(".nix"))
                    .filter(p -> !p.toString().contains("/.git/"))
                    .forEach(file -> found.addAll(extractFromFile(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private List<CandidateDependency> extractFromFile(Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<CandidateDependency> matches = new ArrayList<>();
        Matcher matcher = IMAGE_REF.matcher(content);
        while (matcher.find()) {
            matches.add(new CandidateDependency(matcher.group(1), file, matcher.group(2), matcher.group(3)));
        }
        return matches;
    }
}
