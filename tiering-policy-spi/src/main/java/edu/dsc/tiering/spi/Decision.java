package edu.dsc.tiering.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** PlacementDecision.decisions[] 원소 — 파일 하나에 대한 목표 배치 선언. */
public final class Decision {

    private final String path;
    private final Placement targetPlacement;
    private final double priority;
    private final String reason;

    @JsonCreator
    public Decision(@JsonProperty("path") String path,
                     @JsonProperty("target_placement") Placement targetPlacement,
                     @JsonProperty("priority") double priority,
                     @JsonProperty("reason") String reason) {
        this.path = path;
        this.targetPlacement = targetPlacement;
        this.priority = priority;
        this.reason = reason;
    }

    @JsonProperty("path")
    public String path() {
        return path;
    }

    @JsonProperty("target_placement")
    public Placement targetPlacement() {
        return targetPlacement;
    }

    /** [0, 1] 범위. 실행 계층이 rate limit 하에서 이관 순서를 정하는 데 사용한다. */
    @JsonProperty("priority")
    public double priority() {
        return priority;
    }

    /** 형식 자유, 논문 사례 분석용. */
    @JsonProperty("reason")
    public String reason() {
        return reason;
    }
}
