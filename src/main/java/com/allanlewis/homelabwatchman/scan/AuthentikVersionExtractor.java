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
 * Reads {@code nixos/profiles/authentik/default.nix} for the
 * {@code authentikVersion = "YYYY.M.P";} let-binding, which tracks
 * goauthentik/authentik release tags. Note: authentik's actual GitHub
 * release tags are "version/YYYY.M.P" - the "version/" prefix is stripped
 * when resolving (see GitHubReleaseClient), not here.
 */
@Component
public class AuthentikVersionExtractor implements DependencyExtractor {

    static final String TARGET_FILE = "nixos/profiles/authentik/default.nix";
    static final String IDENTIFIER = "goauthentik/authentik";

    private static final Pattern VERSION = Pattern.compile("authentikVersion\\s*=\\s*\"([^\"]+)\"");

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
