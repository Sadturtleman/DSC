package edu.dsc.tiering.simulator.observe;

import edu.dsc.tiering.simulator.core.ClusterFileState;
import edu.dsc.tiering.simulator.core.ClusterStateModel;
import edu.dsc.tiering.simulator.core.PlacementChangeListener;
import edu.dsc.tiering.simulator.core.PlacementChangeResult;
import edu.dsc.tiering.simulator.core.SimulationDriver;
import edu.dsc.tiering.spi.Decision;
import edu.dsc.tiering.spi.ObservationSnapshot;
import edu.dsc.tiering.spi.Placement;
import edu.dsc.tiering.spi.PlacementDecision;
import edu.dsc.tiering.spi.TieringPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * 드라이버의 반복 예약 이벤트로 매 주기(기본 {@link #DEFAULT_INTERVAL_MILLIS})마다
 * {@link SnapshotAssembler}로 관측을 조립하고, {@link TieringPolicy#decide}를 호출한 뒤,
 * 결정을 {@link ClusterStateModel#requestPlacementChange}로 적용한다.
 * <p>
 * ACCEPTED된 변경만 {@link PlacementChangeListener}에게 통지한다 — REJECTED된 결정은
 * 실제로 아무 상태도 바뀌지 않았으므로 측정기가 알 필요가 없다.
 */
public final class PolicyRunner {

    public static final long DEFAULT_INTERVAL_MILLIS = 24L * 60 * 60 * 1000; // 24h

    private final TieringPolicy policy;
    private final SnapshotAssembler assembler;
    private final ClusterStateModel model;
    private final List<PlacementChangeListener> listeners = new ArrayList<>();

    public PolicyRunner(TieringPolicy policy, SnapshotAssembler assembler, ClusterStateModel model) {
        this.policy = policy;
        this.assembler = assembler;
        this.model = model;
    }

    public void addPlacementChangeListener(PlacementChangeListener listener) {
        listeners.add(listener);
    }

    /** driver에 주기 반복 예약 이벤트로 등록한다. */
    public void attachTo(SimulationDriver driver, long firstAtMillis, long intervalMillis) {
        driver.scheduleRepeating(firstAtMillis, intervalMillis, this::runCycle);
    }

    /** 조립 → decide → 적용 한 사이클. 테스트에서 직접 호출 가능하도록 package-private. */
    void runCycle(long atMillis) {
        ObservationSnapshot snapshot = assembler.assemble(atMillis);
        PlacementDecision decision = policy.decide(snapshot);

        for (Decision d : decision.decisions()) {
            ClusterFileState fileState = model.fileState(d.path());
            if (fileState == null) {
                continue; // 방어적: 스냅샷 조립 이후 사라진 파일(이 트레이스 모델에서는 발생하지 않음)
            }
            Placement from = fileState.placement();
            Placement to = d.targetPlacement();
            if (from == to) {
                continue; // 이미 목표 배치 — ClusterStateModel까지 갈 필요 없음
            }

            PlacementChangeResult result = model.requestPlacementChange(d.path(), to);
            if (result == PlacementChangeResult.ACCEPTED) {
                for (PlacementChangeListener listener : listeners) {
                    listener.onPlacementChangeApplied(atMillis, d.path(), from, to);
                }
            }
        }
    }
}
