package edu.dsc.tiering.simulator.trace;

/**
 * 같은 {@code input_paths}로 주기 재실행되는 job 생성 스펙 (RQ3: 힌트 기반 선제 승급의
 * "재접근" 실험용). {@link SyntheticSpec#repeatingJobSpec()}이 {@code null}이면 job 이벤트를
 * 생성하지 않는다.
 */
public final class RepeatingJobSpec {

    private final int firstRunDay;
    private final int intervalDays;
    private final int occurrences;
    private final int inputPathsPerJob;
    private final String jobIdPrefix;

    public RepeatingJobSpec(int firstRunDay, int intervalDays, int occurrences,
                             int inputPathsPerJob, String jobIdPrefix) {
        if (firstRunDay < 0) {
            throw new IllegalArgumentException("firstRunDay는 음수일 수 없습니다: " + firstRunDay);
        }
        if (intervalDays <= 0) {
            throw new IllegalArgumentException("intervalDays는 양수여야 합니다: " + intervalDays);
        }
        if (occurrences < 0) {
            throw new IllegalArgumentException("occurrences는 음수일 수 없습니다: " + occurrences);
        }
        if (inputPathsPerJob <= 0) {
            throw new IllegalArgumentException("inputPathsPerJob은 양수여야 합니다: " + inputPathsPerJob);
        }
        this.firstRunDay = firstRunDay;
        this.intervalDays = intervalDays;
        this.occurrences = occurrences;
        this.inputPathsPerJob = inputPathsPerJob;
        this.jobIdPrefix = jobIdPrefix == null || jobIdPrefix.isBlank() ? "job" : jobIdPrefix;
    }

    public int firstRunDay() {
        return firstRunDay;
    }

    public int intervalDays() {
        return intervalDays;
    }

    public int occurrences() {
        return occurrences;
    }

    public int inputPathsPerJob() {
        return inputPathsPerJob;
    }

    public String jobIdPrefix() {
        return jobIdPrefix;
    }
}
