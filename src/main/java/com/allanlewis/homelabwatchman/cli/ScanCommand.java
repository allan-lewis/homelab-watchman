package com.allanlewis.homelabwatchman.cli;

import com.allanlewis.homelabwatchman.plan.ProposedUpdate;
import com.allanlewis.homelabwatchman.plan.UpdatePlanner;
import com.allanlewis.homelabwatchman.scan.CandidateDependency;
import com.allanlewis.homelabwatchman.scan.DependencyExtractor;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Entry point for `java -jar homelab-watchman.jar scan --repo <path>`.
 * Dry-run by default: reports what would change, opens nothing. --apply
 * will gate the real PR-opening behavior once ai/vcs are implemented.
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
        if (args.length == 0 || !"scan".equals(args[0])) {
            System.out.println("Usage: scan --repo <path> [--apply]");
            return;
        }

        Path repoRoot = parseRepoArg(args);
        boolean apply = Arrays.asList(args).contains("--apply");

        List<CandidateDependency> candidates = extractors.stream()
                .flatMap(extractor -> extractor.extract(repoRoot).stream())
                .toList();

        System.out.println(candidates.size() + " candidate dependencies found");

        List<ProposedUpdate> updates = updatePlanner.plan(candidates);

        if (updates.isEmpty()) {
            System.out.println("Everything up to date.");
            return;
        }

        for (ProposedUpdate update : updates) {
            System.out.printf("%-30s %-45s -> %-45s %s%n",
                    update.kind(), update.oldText(), update.newText(), update.candidate().file());
        }

        if (!apply) {
            System.out.println();
            System.out.println(updates.size() + " update(s) found. --apply is not yet implemented.");
        }
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
