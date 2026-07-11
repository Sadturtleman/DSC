package edu.dsc.tiering.simulator.core;

/** {@link ClusterStateModel#requestPlacementChange}의 결과. */
public enum PlacementChangeResult {
    ACCEPTED,
    /** 목표 배치가 늘리려는 물리 티어 중 하나 이상의 잔여 용량이 부족해 거부됨. */
    REJECTED_CAPACITY,
    /** 존재하지 않는 path에 대한 요청. */
    REJECTED_UNKNOWN_FILE
}
