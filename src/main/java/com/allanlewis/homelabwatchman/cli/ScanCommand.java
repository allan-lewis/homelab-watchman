package com.allanlewis.homelabwatchman.cli;

import com.allanlewis.homelabwatchman.ai.PrText;
import com.allanlewis.homelabwatchman.ai.PrTextGenerator;
import com.allanlewis.homelabwatchman.metrics.ScanMetricsRecorder;
import com.allanlewis.homelabwatchman.persist.DependencyStateWriter;
import com.allanlewis.homelabwatchman.persist.ScanRun;
import com.allanlewis.homelabwatchman.persist.ScanRunRepository;
import com.allanlewis.homelabwatchman.plan.ProposedUpdate;
import com.allanlewis.homelabwatchman.plan.UpdateKind;
import com.allanlewis.homelabwatchman.plan.UpdatePlanner;
import com.allanlewis.homelabwatchman.scan.CandidateDependency;
import com.allanlewis.homelabwatchman.scan.DependencyExtractor;
import com.allanlewis.homelabwatchman.scan.GitCheckoutGuard;
import com.allanlewis.homelabwatchman.vcs.PullRequestClient;
import com.allanlewis.homelabwatchman.vcs.PullRequestClient.PrOutcome;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Entry point for `java -jar homelab-watchman.jar scan --repo <path>`.
 * Dry-run by default: reports what would change, opens nothing. --apply
 * drafts PR text via AI and actually reconciles PRs via vcs (idempotent:
 * creates, updates an existing one in place, skips a previously-rejected
 * target, or no-ops if already current).
 *
 * Default output is one concise line per candidate in scope: "no-op", a
 * bare old->new version, or "digest refresh". --verbose/-v instead prints
 * full old/new refs (registry, tag, digest) for every candidate with a
 * pending update.
 */
@Component
public class ScanCommand implements CommandLineRunner {

    private final List<DependencyExtractor> extractors;
    private final GitCheckoutGuard gitCheckoutGuard;
    private final UpdatePlanner updatePlanner;
    private final ScanRunRepository scanRunRepository;
    private final DependencyStateWriter dependencyStateWriter;
    private final ScanMetricsRecorder metricsRecorder;
    private final PrTextGenerator prTextGenerator;
    private final PullRequestClient pullRequestClient;
    private final Path metricsOutputPath;

    public ScanCommand(
            List<DependencyExtractor> extractors,
            GitCheckoutGuard gitCheckoutGuard,
            UpdatePlanner updatePlanner,
            ScanRunRepository scanRunRepository,
            DependencyStateWriter dependencyStateWriter,
            ScanMetricsRecorder metricsRecorder,
            PrTextGenerator prTextGenerator,
            PullRequestClient pullRequestClient,
            @Value("${homelab.watchman.metrics.output-path}") String metricsOutputPath) {
        this.extractors = extractors;
        this.gitCheckoutGuard = gitCheckoutGuard;
        this.updatePlanner = updatePlanner;
        this.scanRunRepository = scanRunRepository;
        this.dependencyStateWriter = dependencyStateWriter;
        this.metricsRecorder = metricsRecorder;
        this.prTextGenerator = prTextGenerator;
        this.pullRequestClient = pullRequestClient;
        this.metricsOutputPath = Path.of(metricsOutputPath);
    }

    @Override
    public void run(String... args) {
        if (args.length == 0 || !"scan".equals(args[0])) {
            System.out.println("Usage: scan --repo <path> [--apply] [-v|--verbose] [--only <identifier>]");
            return;
        }

        List<String> argList = Arrays.asList(args);
        Path repoRoot = parseRepoArg(args);
        boolean apply = argList.contains("--apply");
        boolean verbose = argList.contains("-v") || argList.contains("--verbose");
        String only = parseOptionalArg(args, "--only");

        gitCheckoutGuard.verify(repoRoot);

        ScanRun scanRun = new ScanRun();
        scanRun.setStartedAt(Instant.now());

        List<CandidateDependency> candidates = extractors.stream()
                .flatMap(extractor -> extractor.extract(repoRoot).stream())
                .filter(c -> only == null || c.identifier().equals(only))
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

        Map<CandidateDependency, PrOutcome> prOutcomes = Map.of();
        int prsOpened = 0;

        if (apply) {
            try {
                int branchesDeleted = pullRequestClient.cleanupMergedBranches();
                if (branchesDeleted > 0) {
                    System.out.println();
                    System.out.println("Deleted " + branchesDeleted + " merged PR branch(es).");
                }
            } catch (RuntimeException e) {
                System.out.println();
                System.out.println("Merged-branch cleanup FAILED - " + e.getMessage());
            }

            if (!updates.isEmpty()) {
                prOutcomes = reconcileAll(repoRoot, updates);
                prsOpened = (int) prOutcomes.values().stream()
                        .filter(o -> o.status() == PrOutcome.Status.CREATED)
                        .count();
            }
        } else if (!updates.isEmpty()) {
            System.out.println("Pass --apply to draft PR text and open/update PRs for these updates.");
        }

        dependencyStateWriter.save(candidates, byCandidate, prOutcomes);

        scanRun.setFinishedAt(Instant.now());
        scanRun.setCandidatesFound(candidates.size());
        scanRun.setPrsOpened(prsOpened);
        scanRunRepository.save(scanRun);

        Map<UpdateKind, Long> countsByKind = updates.stream()
                .collect(Collectors.groupingBy(ProposedUpdate::kind, Collectors.counting()));
        metricsRecorder.recordCandidatesByKind(countsByKind);
        metricsRecorder.recordPrsOpened(prsOpened);
        metricsRecorder.writeTo(metricsOutputPath);
    }

    private Map<CandidateDependency, PrOutcome> reconcileAll(Path repoRoot, List<ProposedUpdate> updates) {
        Map<CandidateDependency, PrOutcome> outcomes = new LinkedHashMap<>();

        System.out.println();
        System.out.println("Reconciling PRs:");
        for (ProposedUpdate update : updates) {
            System.out.println();
            try {
                reconcileOne(repoRoot, update, outcomes);
            } catch (RuntimeException e) {
                // One dependency's GitHub/AI call failing (rate limit, transient
                // API error, etc.) shouldn't abort every other update in this run.
                System.out.println(update.candidate().identifier() + ": FAILED - " + e.getMessage());
            }
        }

        return outcomes;
    }

    private void reconcileOne(Path repoRoot, ProposedUpdate update, Map<CandidateDependency, PrOutcome> outcomes) {
        PrOutcome predicted = pullRequestClient.checkStatus(repoRoot, update);
        PrOutcome outcome = predicted;

        System.out.print(update.candidate().identifier() + ": " + predicted.status());

        if (predicted.status() == PrOutcome.Status.CREATED || predicted.status() == PrOutcome.Status.UPDATED) {
            PrText text = prTextGenerator.generate(update);
            outcome = pullRequestClient.apply(repoRoot, update, text);
            System.out.println(" " + outcome.prUrl());
            System.out.println("  title: " + text.title());
            System.out.println("  body:  " + text.body());
        } else {
            System.out.println(predicted.prUrl() != null ? " " + predicted.prUrl() : "");
        }

        outcomes.put(update.candidate(), outcome);
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
        String repo = parseOptionalArg(args, "--repo");
        if (repo == null) {
            throw new IllegalArgumentException("missing required --repo <path>");
        }
        return Path.of(repo);
    }

    private String parseOptionalArg(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }
}
