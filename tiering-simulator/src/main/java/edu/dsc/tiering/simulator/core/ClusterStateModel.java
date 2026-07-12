package edu.dsc.tiering.simulator.core;

import edu.dsc.tiering.simulator.model.PhysicalTier;
import edu.dsc.tiering.simulator.model.PlacementFootprint;
import edu.dsc.tiering.simulator.trace.FileCreateEvent;
import edu.dsc.tiering.simulator.trace.JobEvent;
import edu.dsc.tiering.simulator.trace.ReadEvent;
import edu.dsc.tiering.simulator.trace.TraceEvent;
import edu.dsc.tiering.spi.Placement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 클러스터의 "실제" 상태: 파일별 (크기, 현재 Placement, 마지막 접근 시각)과 물리 티어별
 * 용량/사용량. {@link SimulationDriver}에 {@link SimListener}로 등록해 트레이스 재생에 맞춰
 * 자동 갱신한다.
 * <p>
 * {@code file_create}로 새로 생성된 파일은 HDFS 기본 정책({@link Placement#HOT}, DISK×3)에서
 * 출발한다 — 실제 HDFS도 storage policy를 지정하지 않으면 "Hot"(DISK만)이 기본이기 때문이다
 * (hdfs-auto-tiering의 {@code ScoringEngine.POLICY_TO_TIER} 정책 코드 7/기본값 처리와
 * 같은 전제).
 */
public final class ClusterStateModel implements SimListener {

    private static final Logger log = LoggerFactory.getLogger(ClusterStateModel.class);

    /** file_create로 새로 생성된 파일의 시작 Placement. 클래스 javadoc 참고. */
    private static final Placement INITIAL_PLACEMENT = Placement.HOT;

    private final Map<String, ClusterFileState> files = new LinkedHashMap<>();
    private final Map<PhysicalTier, Long> capacityBytes;
    private final Map<PhysicalTier, Long> usedBytes;
    private long rejectionCount = 0;

    public ClusterStateModel(Map<PhysicalTier, Long> capacityBytes) {
        this.capacityBytes = new EnumMap<>(capacityBytes);
        this.usedBytes = new EnumMap<>(PhysicalTier.class);
        for (PhysicalTier tier : PhysicalTier.values()) {
            usedBytes.put(tier, 0L);
        }
    }

    @Override
    public void onEvent(long atMillis, TraceEvent event) {
        if (event instanceof FileCreateEvent) {
            FileCreateEvent e = (FileCreateEvent) event;
            registerFile(e.path(), e.sizeBytes(), atMillis);
        } else if (event instanceof ReadEvent) {
            touch(((ReadEvent) event).path(), atMillis);
        } else if (event instanceof JobEvent) {
            for (String path : ((JobEvent) event).inputPaths()) {
                touch(path, atMillis);
            }
        }
    }

    @Override
    public void onTimeAdvance(long fromMillis, long toMillis) {
        // ClusterStateModel 자체는 시간 전진만으로 바뀌는 상태가 없다 (이관 지연 도입 시 여기서 처리).
    }

    public ClusterFileState fileState(String path) {
        return files.get(path);
    }

    /** SnapshotAssembler가 ObservationSnapshot.files를 조립할 때 쓰는 전체 파일 뷰(읽기 전용). */
    public Collection<ClusterFileState> allFiles() {
        return Collections.unmodifiableCollection(files.values());
    }

    public long usedBytes(PhysicalTier tier) {
        return usedBytes.get(tier);
    }

    public long capacityBytes(PhysicalTier tier) {
        return capacityBytes.getOrDefault(tier, 0L);
    }

    public long rejectionCount() {
        return rejectionCount;
    }

    /**
     * path의 Placement를 target으로 변경 요청한다. 순서 7번(이관 모델)이 붙기 전까지는
     * 즉시 반영 — 인터페이스만 분리해 두어 나중에 지연 이관으로 교체 가능하게 한다.
     * <p>
     * 목표 배치가 어느 물리 티어의 사용량을 늘리는데 그 티어의 잔여 용량이 부족하면 변경
     * 전체를 거부하고(부분 반영 없음) {@link #rejectionCount()}를 증가시킨다.
     */
    public PlacementChangeResult requestPlacementChange(String path, Placement target) {
        ClusterFileState state = files.get(path);
        if (state == null) {
            log.warn("존재하지 않는 파일에 대한 배치 변경 요청 거부: path={}", path);
            rejectionCount++;
            return PlacementChangeResult.REJECTED_UNKNOWN_FILE;
        }

        Map<PhysicalTier, Long> before = PlacementFootprint.occupiedBytes(state.placement(), state.sizeBytes());
        Map<PhysicalTier, Long> after = PlacementFootprint.occupiedBytes(target, state.sizeBytes());

        for (PhysicalTier tier : PhysicalTier.values()) {
            long delta = after.getOrDefault(tier, 0L) - before.getOrDefault(tier, 0L);
            long capacity = capacityBytes.getOrDefault(tier, 0L);
            if (delta > 0 && usedBytes.get(tier) + delta > capacity) {
                log.warn("용량 부족으로 배치 변경 거부: path={} {}→{} tier={} used={} delta={} capacity={}",
                        path, state.placement(), target, tier, usedBytes.get(tier), delta, capacity);
                rejectionCount++;
                return PlacementChangeResult.REJECTED_CAPACITY;
            }
        }

        subtractUsage(before);
        addUsage(after);
        state.setPlacement(target);
        return PlacementChangeResult.ACCEPTED;
    }

    private void registerFile(String path, long sizeBytes, long atMillis) {
        ClusterFileState state = new ClusterFileState(path, sizeBytes, INITIAL_PLACEMENT, atMillis);
        files.put(path, state);
        addUsage(PlacementFootprint.occupiedBytes(INITIAL_PLACEMENT, sizeBytes));
    }

    private void touch(String path, long atMillis) {
        ClusterFileState state = files.get(path);
        if (state == null) {
            log.warn("존재하지 않는 파일에 대한 접근 이벤트 무시: path={} at={}", path, atMillis);
            return;
        }
        state.touch(atMillis);
    }

    private void addUsage(Map<PhysicalTier, Long> delta) {
        delta.forEach((tier, bytes) -> usedBytes.merge(tier, bytes, Long::sum));
    }

    private void subtractUsage(Map<PhysicalTier, Long> delta) {
        delta.forEach((tier, bytes) -> usedBytes.merge(tier, -bytes, Long::sum));
    }
}
