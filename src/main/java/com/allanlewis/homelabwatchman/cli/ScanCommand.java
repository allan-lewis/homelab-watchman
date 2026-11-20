package com.allanlewis.homelabwatchman.cli;

import com.allanlewis.homelabwatchman.metrics.ScanMetricsRecorder;
import com.allanlewis.homelabwatchman.persist.DependencyStateWriter;
import com.allanlewis.homelabwatchman.persist.ScanRun;
import com.allanlewis.homelabwatchman.persist.ScanRunRepository;
import com.allanlewis.homelabwatchman.plan.ProposedUpdate;
import com.allanlewis.homelabwatchman.plan.UpdateKind;
import com.allanlewis.homelabwatchman.plan.UpdatePlanner;
import com.allanlewis.homelabwatchman.scan.CandidateDependency;
import com.allanlewis.homelabwatchman.scan.DependencyExtractor;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Entry point for `java -jar homelab-watchman.jar scan --repo <path>`.
 * Dry-run by default: reports what would change, opens nothing. --apply
 * will gate the real PR-opening behavior once ai/vcs are implemented.
 *
 * Default output is one concise line per candidate in scope: "no-op", a
 * bare old->new version, or "digest refresh". --verbose/-v instead prints
 * full old/new refs (registry, tag, digest) for every candidate with a
 * pending update.
 */
@Component
public class ScanCommand implements CommandLineRunner {

    private final List<DependencyExtractor> extractors;
    private final UpdatePlanner updatePlanner;
    private final ScanRunRepository scanRunRepository;
    private final DependencyStateWriter dependencyStateWriter;
    private final ScanMetricsRecorder metricsRecorder;
    private final Path metricsOutputPath;

    public ScanCommand(
            List<DependencyExtractor> extractors,
            UpdatePlanner updatePlanner,
            ScanRunRepository scanRunRepository,
            DependencyStateWriter dependencyStateWriter,
            ScanMetricsRecorder metricsRecorder,
            @Value("${homelab.watchman.metrics.output-path}") String metricsOutputPath) {
        this.extractors = extractors;
        this.updatePlanner = updatePlanner;
        this.scanRunRepository = scanRunRepository;
        this.dependencyStateWriter = dependencyStateWriter;
        this.metricsRecorder = metricsRecorder;
        this.metricsOutputPath = Path.of(metricsOutputPath);
    }

    @Override
    public void run(String... args) {
        if (args.length == 0 || !"scan".equals(args[0])) {
            System.out.println("Usage: scan --repo <path> [--apply] [-v|--verbose]");
            return;
        }

        List<String> argList = Arrays.asList(args);
        Path repoRoot = parseRepoArg(args);
        boolean apply = argList.contains("--apply");
        boolean verbose = argList.contains("-v") || argList.contains("--verbose");

        ScanRun scanRun = new ScanRun();
        scanRun.setStartedAt(Instant.now());

        List<CandidateDependency> candidates = extractors.stream()
                .flatMap(extractor -> extractor.extract(repoRoot).stream())
                .toList();

        List<ProposedUpdate> updates = updatePlanner.plan(candidates);
        Map<CandidateDependency, ProposedUpdate> byCandidate = ProposedUpdate.index(updates);

        System.out.println(candidates.size() + " dependencies in scope");
        for (CandidateDependency candidate : candidates) {
            printLine(candidate, byCandidate.get(candidate), verbose);
        }

        long versionBumps = updates.stream().filter(u -> u.kind() == UpdateKind.VERSION_BUMP).count();
        long digestRefreshes = updates.stream().filter(u -> u.kind() == UpdateKind.DIGEST_REFRESH).count();
        long noOps = candidates.size() - updates.size();

        System.out.println();
        System.out.printf("%d version bump(s), %d digest refresh(es), %d up to date%n",
                versionBumps, digestRefreshes, noOps);
        if (!updates.isEmpty() && !apply) {
            System.out.println("--apply is not yet implemented.");
        }

        dependencyStateWriter.save(candidates, byCandidate);

        scanRun.setFinishedAt(Instant.now());
        scanRun.setCandidatesFound(candidates.size());
        scanRun.setPrsOpened(0);
        scanRunRepository.save(scanRun);

        Map<UpdateKind, Long> countsByKind = updates.stream()
                .collect(Collectors.groupingBy(ProposedUpdate::kind, Collectors.counting()));
        metricsRecorder.recordCandidatesByKind(countsByKind);
        metricsRecorder.recordPrsOpened(0);
        metricsRecorder.writeTo(metricsOutputPath);
    }

    private void printLine(CandidateDependency candidate, ProposedUpdate update, boolean verbose) {
        if (verbose) {
            String status = update == null
                    ? "no-op (" + updatePlanner.describeCurrent(candidate) + ")"
                    : update.kind() + "  " + update.oldText() + " -> " + update.newText();
            System.out.printf("%-30s %s%n", candidate.identifier(), status);
            return;
        }

        String status = update == null
                ? "no-op"
                : switch (update.kind()) {
                    case VERSION_BUMP -> update.oldBareVersion() + " -> " + update.newBareVersion();
                    case DIGEST_REFRESH -> "digest refresh";
                };
        System.out.printf("%-30s %s%n", candidate.identifier(), status);
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
