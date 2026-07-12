package edu.dsc.tiering.simulator.observe;

import edu.dsc.tiering.simulator.core.ClusterFileState;
import edu.dsc.tiering.simulator.core.ClusterStateModel;
import edu.dsc.tiering.simulator.model.PhysicalTier;
import edu.dsc.tiering.spi.ClusterInfo;
import edu.dsc.tiering.spi.FileState;
import edu.dsc.tiering.spi.ObservationMode;
import edu.dsc.tiering.spi.ObservationSnapshot;
import edu.dsc.tiering.spi.Params;
import edu.dsc.tiering.spi.TierInfo;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link ClusterStateModel}의 현재 상태를 {@code TieringPolicy}가 소비하는
 * {@code ObservationSnapshot}으로 조립한다.
 * <p>
 * 지금은 {@code observation_mode=fsimage} 고정, {@code observed_at == decided_at}(관측
 * 지연/staleness 없음), {@code access_stats=null}, {@code events=[]}로 단순화되어 있다.
 * fsimage 주기·관측 지연 파라미터화(구현 순서 8번, observe 패키지 정식화)에서 이 클래스를
 * 확장해 staleness를 도입한다 — <b>이 클래스는 그때 만들어질 정식 관측 어댑터의 기반이다.</b>
 * <p>
 * {@code mtime}은 {@link ClusterFileState#createdAtMillis()}를 쓴다 — 이 시뮬레이터의 트레이스
 * 모델에는 "내용 수정" 이벤트가 없어(파일은 생성 후 불변) 생성 시각이 곧 마지막 수정 시각이다.
 */
public final class SnapshotAssembler {

    private final ClusterStateModel model;
    private final Params params;

    public SnapshotAssembler(ClusterStateModel model, Params params) {
        this.model = model;
        this.params = params;
    }

    public ObservationSnapshot assemble(long atMillis) {
        Instant observedAt = Instant.ofEpochMilli(atMillis);

        List<FileState> files = model.allFiles().stream()
                .map(this::toFileState)
                .collect(Collectors.toList());

        ClusterInfo cluster = new ClusterInfo(
                Arrays.stream(PhysicalTier.values())
                        .map(tier -> new TierInfo(tier.name(), model.capacityBytes(tier), model.usedBytes(tier)))
                        .collect(Collectors.toList()));

        return new ObservationSnapshot("1.0", observedAt, observedAt, ObservationMode.FSIMAGE,
                cluster, files, List.of(), params);
    }

    private FileState toFileState(ClusterFileState f) {
        return new FileState(
                f.path(),
                f.sizeBytes(),
                f.placement(),
                Instant.ofEpochMilli(f.lastAccessAtMillis()),
                Instant.ofEpochMilli(f.createdAtMillis()),
                null);
    }
}
