package edu.dsc.tiering.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ObservationSnapshot.params — 실험 러너가 주입하는 정책 파라미터.
 * 정책 구현은 여기 없는 설정값을 하드코딩하지 않는다.
 */
public final class Params {

    private final double aggressiveness;
    private final int accessHorizonDays;

    @JsonCreator
    public Params(@JsonProperty("aggressiveness") double aggressiveness,
                  @JsonProperty("access_horizon_days") int accessHorizonDays) {
        this.aggressiveness = aggressiveness;
        this.accessHorizonDays = accessHorizonDays;
    }

    @JsonProperty("aggressiveness")
    public double aggressiveness() {
        return aggressiveness;
    }

    @JsonProperty("access_horizon_days")
    public int accessHorizonDays() {
        return accessHorizonDays;
    }
}
