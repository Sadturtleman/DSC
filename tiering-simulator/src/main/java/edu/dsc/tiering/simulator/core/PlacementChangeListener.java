package edu.dsc.tiering.simulator.core;

import edu.dsc.tiering.spi.Placement;

/**
 * {@code ClusterStateModel.requestPlacementChange}가 ACCEPTED로 성공했을 때 통지받는 접점.
 * <p>
 * 트레이스 이벤트(file_create/read/job)는 {@link SimListener}로 통지되지만, 정책이 유발하는
 * 배치 변경은 트레이스 이벤트가 아니라 {@code PolicyRunner}(observe 패키지)가 명시적으로
 * 호출한 결과다. {@code ClusterStateModel} 자체는 이 리스너를 모른다 — 변경을 수행하는 쪽
 * (PolicyRunner)이 성공한 변경만 골라 통지하는 구조로, 코어를 수정하지 않고도 측정기
 * (예: meters.CostMeter)가 배치 변경에 반응할 수 있게 한다.
 */
public interface PlacementChangeListener {
    void onPlacementChangeApplied(long atMillis, String path, Placement from, Placement to);
}
