package com.allanlewis.homelabwatchman.scan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads {@code nixos/profiles/immich/default.nix} for the
 * {@code immichVersion = "vX.Y.Z";} let-binding, which tracks
 * immich-app/immich release tags.
 */
@Component
public class ImmichVersionExtractor implements DependencyExtractor {

    static final String TARGET_FILE = "nixos/profiles/immich/default.nix";
    static final String IDENTIFIER = "immich-app/immich";

    private static final Pattern VERSION = Pattern.compile("immichVersion\\s*=\\s*\"([^\"]+)\"");

    @Override
    public List<CandidateDependency> extract(Path repoRoot) {
        Path file = repoRoot.resolve(TARGET_FILE);
        if (!Files.exists(file)) {
            return List.of();
        }

        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Matcher matcher = VERSION.matcher(content);
        if (!matcher.find()) {
            return List.of();
        }

        return List.of(new CandidateDependency(IDENTIFIER, file, matcher.group(1), null, Ecosystem.GITHUB_RELEASE));
    }
}
