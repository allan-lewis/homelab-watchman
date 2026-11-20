package com.allanlewis.homelabwatchman.cli;

import com.allanlewis.homelabwatchman.plan.UpdatePlanner;
import com.allanlewis.homelabwatchman.scan.DependencyExtractor;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Entry point for `java -jar homelab-watchman.jar scan --repo <path>`.
 *
 * TODO: real arg parsing (repo path is required); run each extractor, hand
 * results to UpdatePlanner, generate PR text, open PRs, persist state, write
 * the metrics .prom file.
 */
@Component
public class ScanCommand implements CommandLineRunner {

    private final List<DependencyExtractor> extractors;
    private final UpdatePlanner updatePlanner;

    public ScanCommand(List<DependencyExtractor> extractors, UpdatePlanner updatePlanner) {
        this.extractors = extractors;
        this.updatePlanner = updatePlanner;
    }

    @Override
    public void run(String... args) {
        if (args.length == 0) {
            System.out.println("Usage: scan --repo <path>");
            return;
        }
        Path repoRoot = parseRepoArg(args);
        throw new UnsupportedOperationException("not yet implemented: repoRoot=" + repoRoot);
    }

    private Path parseRepoArg(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--repo".equals(args[i])) {
                return Path.of(args[i + 1]);
            }
        }
        throw new IllegalArgumentException("missing required --repo <path>");
    }
}
