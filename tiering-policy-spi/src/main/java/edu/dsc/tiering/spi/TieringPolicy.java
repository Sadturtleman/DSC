package edu.dsc.tiering.spi;

/**
 * 티어링 정책의 접점 계약. 상태 없는 순수 함수 — 접근 통계 등 이력 가공은
 * 관측 어댑터(호출자)의 책임이다.
 */
public interface TieringPolicy {

    String policyId();

    PlacementDecision decide(ObservationSnapshot snapshot);
}
