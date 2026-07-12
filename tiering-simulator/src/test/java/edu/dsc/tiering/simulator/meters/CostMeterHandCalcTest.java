package edu.dsc.tiering.simulator.meters;

import edu.dsc.tiering.simulator.config.SimConstants;
import edu.dsc.tiering.simulator.model.PhysicalTier;
import edu.dsc.tiering.simulator.trace.FileCreateEvent;
import edu.dsc.tiering.spi.Placement;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 손계산 검증 (CLAUDE.md 구현 순서 6번, 항목 8). 구현 전에 시나리오를 고정하고 기대값을
 * 손으로 계산해 둔다.
 *
 * <h2>시나리오</h2>
 * <pre>
 *   파일A: 1 GB(=1,000,000,000 바이트, 십진 GB), t=0일 생성, t=10일에 HOT→COLD, t=30일까지 유지
 *   파일B: 2 GB(=2,000,000,000 바이트), t=0일 생성, t=30일 내내 HOT 유지
 *   단가(가상, 6:1:0.3 비율): SSD=$0.30/GB·h, DISK=$0.05/GB·h, ARCHIVE=$0.015/GB·h
 *   이관 단가: $0.02/GB
 * </pre>
 *
 * <h2>손계산</h2>
 * <pre>
 *   HOT = DISK×3, COLD = ARCHIVE×3 (PlacementFootprint)
 *
 *   구간1 [0,10일) = 240시간: A(HOT,1GB)+B(HOT,2GB) → DISK 점유 = 3GB+6GB = 9GB
 *     DISK 비용 = 9GB × 240h × $0.05 = $108.00
 *
 *   t=10일: A가 HOT→COLD. 이동 복제본 = 3개(DISK 3벌 전부, 겹치는 티어 없음) × 1GB = 3GB
 *     이관 비용 = 3GB × $0.02 = $0.06
 *
 *   구간2 [10,30일) = 480시간: A(COLD,1GB) → ARCHIVE 3GB, B(HOT,2GB) → DISK 6GB
 *     DISK 비용    = 6GB × 480h × $0.05  = $144.00
 *     ARCHIVE 비용 = 3GB × 480h × $0.015 = $21.60
 *
 *   티어별 합계: DISK = 108.00+144.00 = $252.00, ARCHIVE = $21.60, SSD = $0
 *   저장 비용 합계 = $273.60
 *   총비용 = 저장 273.60 + 이관 0.06 = $273.66
 *   이관 바이트 = 3,000,000,000 (3GB)
 * </pre>
 */
class CostMeterHandCalcTest {

    private static final long ONE_GB = 1_000_000_000L;
    private static final long DAY_MILLIS = 86_400_000L;

    @Test
    void twoFileScenarioMatchesHandCalculatedTotals() {
        CostMeter meter = new CostMeter(constants(), 0L);

        meter.onEvent(0L, new FileCreateEvent(0L, "/a", ONE_GB));
        meter.onEvent(0L, new FileCreateEvent(0L, "/b", 2 * ONE_GB));

        long tenDays = 10 * DAY_MILLIS;
        meter.onPlacementChangeApplied(tenDays, "/a", Placement.HOT, Placement.COLD);

        long thirtyDays = 30 * DAY_MILLIS;
        CostReport report = meter.finalizeReport(thirtyDays, 0L);

        assertEquals(252.00, report.costByTier(PhysicalTier.DISK), 1e-9);
        assertEquals(21.60, report.costByTier(PhysicalTier.ARCHIVE), 1e-9);
        assertEquals(0.0, report.costByTier(PhysicalTier.SSD), 1e-9);

        assertEquals(3 * ONE_GB, report.migratedBytes());
        assertEquals(0.06, report.migrationCost(), 1e-9);

        assertEquals(273.60, report.costByTier(PhysicalTier.DISK) + report.costByTier(PhysicalTier.ARCHIVE)
                + report.costByTier(PhysicalTier.SSD), 1e-9);
        assertEquals(273.66, report.totalCost(), 1e-9);
        assertEquals(0L, report.rejectionCount());
    }

    static SimConstants constants() {
        Map<PhysicalTier, Long> throughput = tierLongMap(1L, 1L, 1L); // 이 테스트에서 미사용
        Map<PhysicalTier, Double> price = tierDoubleMap(0.30, 0.05, 0.015);
        Map<PhysicalTier, Long> capacity = tierLongMap(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, Long.MAX_VALUE / 4);
        return new SimConstants(throughput, 1L, 1L, price, 0.02, capacity);
    }

    private static Map<PhysicalTier, Long> tierLongMap(long ssd, long disk, long archive) {
        Map<PhysicalTier, Long> m = new EnumMap<>(PhysicalTier.class);
        m.put(PhysicalTier.SSD, ssd);
        m.put(PhysicalTier.DISK, disk);
        m.put(PhysicalTier.ARCHIVE, archive);
        return m;
    }

    private static Map<PhysicalTier, Double> tierDoubleMap(double ssd, double disk, double archive) {
        Map<PhysicalTier, Double> m = new EnumMap<>(PhysicalTier.class);
        m.put(PhysicalTier.SSD, ssd);
        m.put(PhysicalTier.DISK, disk);
        m.put(PhysicalTier.ARCHIVE, archive);
        return m;
    }
}
