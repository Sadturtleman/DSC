package edu.dsc.tiering.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * TieringPolicy#decide()의 출력. 명령이 아닌 선언(목표 배치) — 이관 순서·rate limit·
 * 차이 계산은 실행 계층의 몫이다.
 * <p>
 * 응답에 없는 파일은 현재 상태를 유지한다.
 */
public final class PlacementDecision {

    private final String schemaVersion;
    private final String policyId;
    private final List<Decision> decisions;

    @JsonCreator
    public PlacementDecision(@JsonProperty("schema_version") String schemaVersion,
                              @JsonProperty("policy_id") String policyId,
                              @JsonProperty("decisions") List<Decision> decisions) {
        this.schemaVersion = schemaVersion;
        this.policyId = policyId;
        this.decisions = decisions;
    }

    @JsonProperty("schema_version")
    public String schemaVersion() {
        return schemaVersion;
    }

    @JsonProperty("policy_id")
    public String policyId() {
        return policyId;
    }

    @JsonProperty("decisions")
    public List<Decision> decisions() {
        return decisions;
    }
}
