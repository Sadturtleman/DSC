package edu.dsc.tiering.scoring;

import edu.dsc.tiering.model.Tier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriorityRuleTest {

    private final Instant now = Instant.parse("2026-05-23T00:00:00Z");
    private final PriorityRule rule = new PriorityRule(0.5, 0.5, now);

    @Test
    void olderFilesHaveHigherPriorityThanRecentFiles() {
        FileMetadata recent = file("/recent", 1024L, now.minus(10, ChronoUnit.DAYS));
        FileMetadata old = file("/old", 1024L, now.minus(120, ChronoUnit.DAYS));

        assertTrue(rule.score(old) > rule.score(recent));
    }

    @Test
    void targetTierFollowsAccessAgeAndCurrentTier() {
        assertNull(rule.targetTier(file("/hot", 1024L, now.minus(10, ChronoUnit.DAYS)), Tier.HOT));
        assertEquals(Tier.WARM, rule.targetTier(file("/warm", 1024L, now.minus(60, ChronoUnit.DAYS)), Tier.HOT));
        assertEquals(Tier.COLD, rule.targetTier(file("/cold", 1024L, now.minus(120, ChronoUnit.DAYS)), Tier.HOT));
        assertNull(rule.targetTier(file("/already-cold", 1024L, now.minus(120, ChronoUnit.DAYS)), Tier.COLD));
    }

    private static FileMetadata file(String path, long size, Instant atime) {
        return new FileMetadata(path, size, atime.toEpochMilli(), atime.toEpochMilli(), 7);
    }
}
