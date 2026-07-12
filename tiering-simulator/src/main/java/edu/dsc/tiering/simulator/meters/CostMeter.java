package edu.dsc.tiering.simulator.meters;

import edu.dsc.tiering.simulator.config.SimConstants;
import edu.dsc.tiering.simulator.core.PlacementChangeListener;
import edu.dsc.tiering.simulator.core.SimListener;
import edu.dsc.tiering.simulator.model.MigrationModel;
import edu.dsc.tiering.simulator.model.PhysicalTier;
import edu.dsc.tiering.simulator.model.PlacementFootprint;
import edu.dsc.tiering.simulator.trace.FileCreateEvent;
import edu.dsc.tiering.simulator.trace.TraceEvent;
import edu.dsc.tiering.spi.Placement;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * CLAUDE.md "측정기 정의" 절의 비용 측정기: 이벤트 구동 시간 적분.
 * <p>
 * 폴링/샘플링을 하지 않는다 — 점유가 실제로 바뀌는 시점(파일 생성, 배치 변경, 최종
 * 마감)에만 "직전 점유 스냅샷 × 그 스냅샷이 유지된 시간 × 티어별 단가"를 누적하고, 그
 * 시점의 새 점유로 스냅샷을 갱신한다. 두 입력 경로가 있다:
 * <ul>
 *   <li>{@link #onEvent}(SimListener) — 트레이스의 {@code file_create}로 새 파일의 시작
 *       점유(항상 {@link Placement#HOT}, {@code ClusterStateModel}과 동일 전제)를 등록.</li>
 *   <li>{@link #onPlacementChangeApplied}(PlacementChangeListener) — 정책이 유발한 배치
 *       변경. {@code ClusterStateModel}을 직접 관찰하지 않고, 변경을 수행한 쪽(PolicyRunner)이
 *       ACCEPTED된 것만 통지하는 구조라 코어 수정이 필요 없다.</li>
 * </ul>
 * 이관 비용은 {@link MigrationModel}로 계산한 이동 복제본 바이트 × migration_price_per_gb를
 * 배치 변경 시점에 함께 누적한다.
 */
public final class CostMeter implements SimListener, PlacementChangeListener {

    /** 가격 단위(GB)는 십진 GB(10^9바이트) — 클라우드 과금 관례를 따른다(저장용량 GiB 관례와 다름, 의도적 선택). */
    private static final double BYTES_PER_GB = 1_000_000_000.0;
    private static final double MILLIS_PER_HOUR = 3_600_000.0;

    /** file_create로 새로 생성된 파일의 시작 Placement. ClusterStateModel의 전제와 동일. */
    private static final Placement INITIAL_PLACEMENT = Placement.HOT;

    private final Map<PhysicalTier, Double> pricePerGbHour;
    private final double migrationPricePerGb;

    private final Map<String, Long> fileSizes = new HashMap<>();
    private final Map<PhysicalTier, Long> currentUsage;
    private final Map<PhysicalTier, Double> costByTier;

    private long lastSnapshotAtMillis;
    private long migratedBytes = 0;
    private double migrationCost = 0.0;

    public CostMeter(SimConstants constants, long startAtMillis) {
        this.pricePerGbHour = new EnumMap<>(PhysicalTier.class);
        this.currentUsage = new EnumMap<>(PhysicalTier.class);
        this.costByTier = new EnumMap<>(PhysicalTier.class);
        for (PhysicalTier tier : PhysicalTier.values()) {
            pricePerGbHour.put(tier, constants.pricePerGbHour(tier));
            currentUsage.put(tier, 0L);
            costByTier.put(tier, 0.0);
        }
        this.migrationPricePerGb = constants.migrationPricePerGb();
        this.lastSnapshotAtMillis = startAtMillis;
    }

    @Override
    public void onEvent(long atMillis, TraceEvent event) {
        if (event instanceof FileCreateEvent) {
            FileCreateEvent e = (FileCreateEvent) event;
            registerFile(atMillis, e.path(), e.sizeBytes());
        }
        // read/job은 점유(occupancy)에 영향이 없으므로 무시한다.
    }

    @Override
    public void onTimeAdvance(long fromMillis, long toMillis) {
        // 이벤트 구동 적분 — 점유가 바뀌지 않는 한 시계가 흘러도 할 일이 없다(폴링 금지).
        // registerFile/onPlacementChangeApplied/finalizeReport에서만 정산한다.
    }

    @Override
    public void onPlacementChangeApplied(long atMillis, String path, Placement from, Placement to) {
        if (from == to) {
            return;
        }
        Long sizeBytes = fileSizes.get(path);
        if (sizeBytes == null) {
            throw new IllegalStateException("CostMeter가 모르는 파일에 대한 배치 변경 통지: " + path);
        }

        integrateUpTo(atMillis);
        applyUsageDelta(PlacementFootprint.occupiedBytes(from, sizeBytes), PlacementFootprint.occupiedBytes(to, sizeBytes));

        long moved = MigrationModel.movedBytes(from, to, sizeBytes);
        migratedBytes += moved;
        migrationCost += (moved / BYTES_PER_GB) * migrationPricePerGb;
    }

    /** 시뮬레이션 종료 시각까지 마지막 구간을 정산하고 최종 보고서를 만든다. */
    public CostReport finalizeReport(long atMillis, long rejectionCount) {
        integrateUpTo(atMillis);
        return new CostReport(Map.copyOf(costByTier), migratedBytes, migrationCost, rejectionCount);
    }

    private void registerFile(long atMillis, String path, long sizeBytes) {
        integrateUpTo(atMillis);
        fileSizes.put(path, sizeBytes);
        Map<PhysicalTier, Long> footprint = PlacementFootprint.occupiedBytes(INITIAL_PLACEMENT, sizeBytes);
        footprint.forEach((tier, bytes) -> currentUsage.merge(tier, bytes, Long::sum));
    }

    private void applyUsageDelta(Map<PhysicalTier, Long> before, Map<PhysicalTier, Long> after) {
        for (PhysicalTier tier : PhysicalTier.values()) {
            long delta = after.getOrDefault(tier, 0L) - before.getOrDefault(tier, 0L);
            if (delta != 0) {
                currentUsage.merge(tier, delta, Long::sum);
            }
        }
    }

    private void integrateUpTo(long atMillis) {
        long elapsed = atMillis - lastSnapshotAtMillis;
        if (elapsed < 0) {
            throw new IllegalArgumentException(
                    "CostMeter는 시간을 거슬러 정산할 수 없습니다: " + atMillis + " < " + lastSnapshotAtMillis);
        }
        if (elapsed == 0) {
            return; // 경계 시각에 여러 변경이 겹쳐도 0구간은 비용을 더하지 않는다(이중계산 방지).
        }
        double hours = elapsed / MILLIS_PER_HOUR;
        for (PhysicalTier tier : PhysicalTier.values()) {
            double gb = currentUsage.get(tier) / BYTES_PER_GB;
            costByTier.merge(tier, gb * hours * pricePerGbHour.get(tier), Double::sum);
        }
        lastSnapshotAtMillis = atMillis;
    }
}
