package edu.dsc.tiering.simulator.meters;

import edu.dsc.tiering.simulator.config.SimConstants;
import edu.dsc.tiering.simulator.model.PhysicalTier;
import edu.dsc.tiering.simulator.trace.FileCreateEvent;
import edu.dsc.tiering.spi.Placement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CLAUDE.md 구현 순서 6번, 항목 9: 적분 정확성 — 무변경 구간의 선형성, 경계 시각(변경
 * 직전/직후)에서 이중계산·누락이 없는지 검증.
 */
class CostMeterIntegrationAccuracyTest {

    private static final long ONE_GB = 1_000_000_000L;
    private static final long HOUR_MILLIS = 3_600_000L;
    private static final long DAY_MILLIS = 86_400_000L;

    @Test
    void costIsLinearInTimeWhenPlacementNeverChanges() {
        // 1GB 파일 1개, HOT 유지. DISK 점유 = 3GB 고정 → 비용은 경과시간에 정비례해야 한다.
        CostMeter meterA = new CostMeter(CostMeterHandCalcTest.constants(), 0L);
        meterA.onEvent(0L, new FileCreateEvent(0L, "/a", ONE_GB));
        CostReport reportAt100h = meterA.finalizeReport(100 * HOUR_MILLIS, 0L);

        CostMeter meterB = new CostMeter(CostMeterHandCalcTest.constants(), 0L);
        meterB.onEvent(0L, new FileCreateEvent(0L, "/a", ONE_GB));
        CostReport reportAt200h = meterB.finalizeReport(200 * HOUR_MILLIS, 0L);

        assertEquals(15.0, reportAt100h.costByTier(PhysicalTier.DISK), 1e-9); // 3GB×100h×$0.05
        assertEquals(2 * reportAt100h.totalCost(), reportAt200h.totalCost(), 1e-9);
    }

    @Test
    void boundaryChangeAtCreationTimeChargesOnlyNewTierGoingForward() {
        // 생성과 동시에(같은 atMillis) HOT->COLD로 옮기면, DISK에 머문 시간이 정확히 0이므로
        // DISK 비용은 0이어야 하고 전체 구간이 ARCHIVE 요율로만 청구되어야 한다(누락도, 이중계산도 없이).
        CostMeter meter = new CostMeter(CostMeterHandCalcTest.constants(), 0L);
        meter.onEvent(0L, new FileCreateEvent(0L, "/a", ONE_GB));
        meter.onPlacementChangeApplied(0L, "/a", Placement.HOT, Placement.COLD);

        CostReport report = meter.finalizeReport(240 * HOUR_MILLIS, 0L);

        assertEquals(0.0, report.costByTier(PhysicalTier.DISK), 1e-9);
        assertEquals(10.8, report.costByTier(PhysicalTier.ARCHIVE), 1e-9); // 3GB×240h×$0.015
    }

    @Test
    void chainedChangesAtSameInstantDoNotDoubleCountOrLoseCost() {
        // 1GB 파일: [0,5일) HOT, 5일차에 HOT->WARM->COLD를 같은 시각에 연달아 적용,
        // [5일,10일) COLD. 중간 WARM 상태는 지속시간이 0이므로 비용에 기여하지 않아야 하고,
        // 두 구간(HOT 120h, COLD 120h)의 비용 합이 전체 240h를 빠짐없이·겹침없이 커버해야 한다.
        CostMeter meter = new CostMeter(CostMeterHandCalcTest.constants(), 0L);
        meter.onEvent(0L, new FileCreateEvent(0L, "/a", ONE_GB));

        long fiveDays = 5 * DAY_MILLIS;
        meter.onPlacementChangeApplied(fiveDays, "/a", Placement.HOT, Placement.WARM);
        meter.onPlacementChangeApplied(fiveDays, "/a", Placement.WARM, Placement.COLD);

        long tenDays = 10 * DAY_MILLIS;
        CostReport report = meter.finalizeReport(tenDays, 0L);

        // DISK: [0,5일)=120h 동안만 3GB 점유 → 3×120×0.05 = 18.0. WARM 구간은 0h이므로 기여 없음.
        assertEquals(18.0, report.costByTier(PhysicalTier.DISK), 1e-9);
        // ARCHIVE: [5일,10일)=120h 동안 3GB 점유(COLD) → 3×120×0.015 = 5.4.
        assertEquals(5.4, report.costByTier(PhysicalTier.ARCHIVE), 1e-9);

        // 이관 바이트: HOT->WARM(2벌 이동) + WARM->COLD(1벌 이동) = 3GB. (직접 HOT->COLD였다면
        // 역시 3벌이지만, 이건 우연의 일치다 — 각 hop은 독립적으로 과금되며 경로에 따라 총
        // 이동량이 직접 전이보다 커질 수도 있다. 여기서는 정확히 같은 값이 나오는 경로를 골랐다.)
        assertEquals(3 * ONE_GB, report.migratedBytes());
        assertEquals(0.06, report.migrationCost(), 1e-9); // (2GB+1GB)×$0.02

        assertEquals(23.46, report.totalCost(), 1e-9); // 18.0+5.4(저장) + 0.06(이관)
    }
}
