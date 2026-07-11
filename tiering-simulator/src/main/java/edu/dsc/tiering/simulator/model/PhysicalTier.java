package edu.dsc.tiering.simulator.model;

/**
 * 실제 물리 저장 매체 3종. {@code edu.dsc.tiering.spi.Placement}(5종, 복제본 배치 정규형)이나
 * {@code edu.dsc.tiering.model.Tier}(hdfs-auto-tiering의 3종 논리 티어)와는 다른 개념 —
 * 이 열거형은 "바이트가 실제로 어느 매체에 놓이는가"만 나타낸다. cluster.tiers[].name
 * (ObservationSnapshot 스키마)과 이름이 대응한다.
 */
public enum PhysicalTier {
    SSD,
    DISK,
    ARCHIVE
}
