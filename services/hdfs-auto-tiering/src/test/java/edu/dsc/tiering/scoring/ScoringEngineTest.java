package edu.dsc.tiering.scoring;

import edu.dsc.tiering.model.Tier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoringEngineTest {

    private final Instant now = Instant.parse("2026-05-23T00:00:00Z");
    private final PriorityRule rule = new PriorityRule(0.5, 0.5, now);
    private final ScoringEngine engine = new ScoringEngine(null, rule, null, 1L, List.of("/"));

    @Test
    void priorityScoreUsesEqualAccessAndSizeRanksLowerFirst() {
        List<ScoringEngine.ScoredJob> jobs = engine.score(List.of(
                file("/old-large", 300, 200, 12),
                file("/old-small", 100, 120, 12),
                file("/warm-medium", 200, 60, 12)));

        Map<String, Double> scores = jobs.stream().collect(Collectors.toMap(
                job -> job.meta().path(),
                ScoringEngine.ScoredJob::priorityScore));

        assertEquals(1.0, scores.get("/old-large"));
        assertTrue(scores.get("/old-large") < scores.get("/old-small"));
        assertTrue(scores.get("/old-large") < scores.get("/warm-medium"));
    }

    @Test
    void allSsdPolicyCanMoveToWarmAndCold() {
        Map<String, Tier> targets = engine.score(List.of(
                file("/all-ssd-warm", 100, 60, 12),
                file("/all-ssd-cold", 100, 120, 12)))
                .stream()
                .collect(Collectors.toMap(
                        job -> job.meta().path(),
                        ScoringEngine.ScoredJob::targetTier));

        assertEquals(Tier.WARM, targets.get("/all-ssd-warm"));
        assertEquals(Tier.COLD, targets.get("/all-ssd-cold"));
    }

    @Test
    void oneSsdPolicyIsAlreadyWarm() {
        List<ScoringEngine.ScoredJob> jobs = engine.score(List.of(
                file("/one-ssd-warm", 100, 60, 10)));

        assertFalse(jobs.stream().anyMatch(job -> job.meta().path().equals("/one-ssd-warm")));
    }

    @Test
    void targetDirectoryWhitelistKeepsOnlyConfiguredSubtrees() {
        ScoringEngine scopedEngine = new ScoringEngine(null, rule, null, 1L,
                List.of("/test/auto-tiering-e2e", "/test/scenario_e2e/"));

        List<String> paths = scopedEngine.filterTargetFiles(List.of(
                file("/test/auto-tiering-e2e/sample.dat", 100, 120, 12),
                file("/test/scenario_e2e/app.log", 100, 120, 12),
                file("/test/auto-tiering-e2e-backup/sample.dat", 100, 120, 12),
                file("/tmp/other.dat", 100, 120, 12)))
                .stream()
                .map(FileMetadata::path)
                .collect(Collectors.toList());

        assertEquals(List.of(
                "/test/auto-tiering-e2e/sample.dat",
                "/test/scenario_e2e/app.log"), paths);
    }

    @Test
    void emptyTargetDirectoryWhitelistMatchesNothing() {
        ScoringEngine scopedEngine = new ScoringEngine(null, rule, null, 1L, List.of());

        assertTrue(scopedEngine.filterTargetFiles(List.of(
                file("/test/auto-tiering-e2e/sample.dat", 100, 120, 12))).isEmpty());
    }

    private FileMetadata file(String path, long size, long daysAgo, int storagePolicy) {
        long atime = now.minus(daysAgo, ChronoUnit.DAYS).toEpochMilli();
        return new FileMetadata(path, size, atime, atime, storagePolicy);
    }
}
