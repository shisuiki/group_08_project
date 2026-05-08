package edu.illinois.group8.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatencyStatsTest {
    @Test
    void summarizesPercentiles() {
        LatencyStats stats = new LatencyStats(5);
        stats.record(10);
        stats.record(20);
        stats.record(30);
        stats.record(40);
        stats.record(50);

        LatencyStats.Summary summary = stats.summarize();

        assertEquals(5, summary.count());
        assertEquals(10, summary.minNs());
        assertEquals(30, summary.p50Ns());
        assertEquals(48, summary.p95Ns());
        assertEquals(50, summary.maxNs());
        assertEquals(30, summary.meanNs());
    }
}
