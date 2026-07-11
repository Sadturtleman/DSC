package edu.dsc.tiering.simulator.trace;

/**
 * {@link SyntheticTraceGenerator}의 입력 파라미터 묶음.
 * 같은 spec(같은 {@link #seed()} 포함)이면 항상 바이트 단위로 동일한 트레이스가 나온다.
 */
public final class SyntheticSpec {

    private final long seed;
    private final int fileCount;
    private final int periodDays;
    private final SizeDistribution sizeDistribution;
    private final AccessPatternMix accessPatternMix;
    private final RepeatingJobSpec repeatingJobSpec; // null이면 job 이벤트 없음

    public SyntheticSpec(long seed, int fileCount, int periodDays,
                          SizeDistribution sizeDistribution, AccessPatternMix accessPatternMix,
                          RepeatingJobSpec repeatingJobSpec) {
        if (fileCount <= 0) {
            throw new IllegalArgumentException("fileCount는 양수여야 합니다: " + fileCount);
        }
        if (periodDays <= 0) {
            throw new IllegalArgumentException("periodDays는 양수여야 합니다: " + periodDays);
        }
        this.seed = seed;
        this.fileCount = fileCount;
        this.periodDays = periodDays;
        this.sizeDistribution = sizeDistribution;
        this.accessPatternMix = accessPatternMix;
        this.repeatingJobSpec = repeatingJobSpec;
    }

    public long seed() {
        return seed;
    }

    public int fileCount() {
        return fileCount;
    }

    public int periodDays() {
        return periodDays;
    }

    public SizeDistribution sizeDistribution() {
        return sizeDistribution;
    }

    public AccessPatternMix accessPatternMix() {
        return accessPatternMix;
    }

    public RepeatingJobSpec repeatingJobSpec() {
        return repeatingJobSpec;
    }
}
