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
 * Scans required_providers blocks in .tf files for provider version floor
 * constraints (e.g. {@code version = ">= 0.97.0"}).
 *
 * Deliberately tracks the floor constraint only, never
 * .terraform.lock.hcl: bumping the floor above the currently locked
 * version is a plain text edit (same model every other extractor here
 * uses) that makes `terraform init` hard-fail until a human runs
 * `terraform init -upgrade` - rather than this tool trying to regenerate
 * lock file hashes itself, which would need an explicit per-platform list
 * that isn't recoverable from the lock file, plus a real
 * `terraform providers lock` run to do correctly.
 */
@Component
public class TerraformProviderExtractor implements DependencyExtractor {

    private static final Pattern REQUIRED_PROVIDERS_BLOCK = Pattern.compile("required_providers\\s*\\{");
    private static final Pattern PROVIDER_ENTRY = Pattern.compile("\\w+\\s*=\\s*\\{([^}]*)\\}");
    private static final Pattern SOURCE = Pattern.compile("source\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_FLOOR = Pattern.compile("version\\s*=\\s*\">=\\s*([^\"]+?)\\s*\"");

    @Override
    public List<CandidateDependency> extract(Path repoRoot) {
        List<CandidateDependency> found = new ArrayList<>();
        try (var paths = Files.walk(repoRoot)) {
            paths.filter(p -> p.toString().endsWith(".tf"))
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
        Matcher blockStart = REQUIRED_PROVIDERS_BLOCK.matcher(content);
        while (blockStart.find()) {
            String block = extractBracedBlock(content, blockStart.end() - 1);
            Matcher entry = PROVIDER_ENTRY.matcher(block);
            while (entry.find()) {
                String entryBody = entry.group(1);
                Matcher source = SOURCE.matcher(entryBody);
                Matcher version = VERSION_FLOOR.matcher(entryBody);
                if (source.find() && version.find()) {
                    matches.add(new CandidateDependency(
                            source.group(1), file, version.group(1), null, Ecosystem.TERRAFORM_PROVIDER));
                }
            }
        }
        return matches;
    }

    /** openBraceIndex must point at the "{" itself; returns the text strictly between it and its matching "}". */
    private String extractBracedBlock(String content, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(openBraceIndex + 1, i);
                }
            }
        }
        throw new IllegalStateException("Unbalanced braces in required_providers block");
    }
}
