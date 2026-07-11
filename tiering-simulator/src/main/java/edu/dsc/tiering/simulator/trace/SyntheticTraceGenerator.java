package edu.dsc.tiering.simulator.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 시드 고정 {@link Random}으로 결정적 합성 트레이스를 생성한다. 같은 {@link SyntheticSpec}
 * (특히 같은 seed)이면 항상 바이트 단위로 동일한 이벤트 시퀀스가 나온다 — 이는 매 파일마다
 * "그룹 선택 → 크기 구간 선택 → 크기 선택 → 생성일 선택" 순서로 {@code rnd}에서 정확히 4개의
 * {@code nextDouble()}을 순서대로 소비하고, 파일 인덱스 0..fileCount-1을 고정된 순서로
 * 순회하기 때문이다. 이 순서를 바꾸면 기존 seed의 출력이 달라지므로 변경 시 주의.
 * <p>
 * 생성 후에는 모든 이벤트를 {@code at} 오름차순으로 안정 정렬한다(동시각 이벤트는 위
 * 생성 순서 — 파일 생성 → 파일별 접근 → 반복 job — 를 그대로 보존).
 */
public final class SyntheticTraceGenerator {

    /** DECAYING 그룹이 생성 이후 접근되는 기간(일). 그 이후로는 재접근이 없다. */
    public static final int DECAY_WINDOW_DAYS = 7;

    private static final long MS_PER_DAY = 86_400_000L;

    public List<TraceEvent> generate(SyntheticSpec spec) {
        Random rnd = new Random(spec.seed());

        List<FileSpec> files = new ArrayList<>(spec.fileCount());
        for (int i = 0; i < spec.fileCount(); i++) {
            AccessGroup group = spec.accessPatternMix().pick(rnd.nextDouble());
            SizeCategory sizeCategory = spec.sizeDistribution().pick(rnd.nextDouble());
            SizeBucket bucket = spec.sizeDistribution().bucket(sizeCategory);
            long sizeBytes = nextLongInRange(rnd, bucket.minBytes(), bucket.maxBytesExclusive());
            int createdAtDay = nextIntInRange(rnd, 0, spec.periodDays());

            files.add(new FileSpec("/data/synthetic/file-" + i, sizeBytes, group, createdAtDay));
        }

        List<TraceEvent> events = new ArrayList<>();
        for (FileSpec file : files) {
            events.add(new FileCreateEvent(file.createdAtDay * MS_PER_DAY, file.path, file.sizeBytes));
        }
        for (FileSpec file : files) {
            addAccessEvents(events, file, spec.periodDays());
        }
        if (spec.repeatingJobSpec() != null) {
            addRepeatingJobEvents(events, files, spec.repeatingJobSpec(), spec.periodDays());
        }

        events.sort((a, b) -> Long.compare(a.at(), b.at())); // 안정 정렬 — 동시각은 삽입 순서 유지
        return List.copyOf(events);
    }

    private static void addAccessEvents(List<TraceEvent> events, FileSpec file, int periodDays) {
        switch (file.group) {
            case HOT:
                for (int day = file.createdAtDay; day < periodDays; day++) {
                    events.add(new ReadEvent(day * MS_PER_DAY, file.path));
                }
                break;
            case DECAYING:
                int decayEnd = Math.min(file.createdAtDay + DECAY_WINDOW_DAYS, periodDays);
                for (int day = file.createdAtDay; day < decayEnd; day++) {
                    events.add(new ReadEvent(day * MS_PER_DAY, file.path));
                }
                break;
            case COLD:
                break; // 재접근 없음
            default:
                throw new IllegalStateException("Unknown AccessGroup: " + file.group);
        }
    }

    private static void addRepeatingJobEvents(List<TraceEvent> events, List<FileSpec> files,
                                               RepeatingJobSpec jobSpec, int periodDays) {
        int inputCount = Math.min(jobSpec.inputPathsPerJob(), files.size());
        List<String> inputPaths = new ArrayList<>(inputCount);
        for (int i = 0; i < inputCount; i++) {
            inputPaths.add(files.get(i).path); // 파일 인덱스 순서 그대로 — 결정적, 추가 난수 소비 없음
        }

        for (int occurrence = 0; occurrence < jobSpec.occurrences(); occurrence++) {
            int day = jobSpec.firstRunDay() + occurrence * jobSpec.intervalDays();
            if (day >= periodDays) {
                break;
            }
            String jobId = jobSpec.jobIdPrefix() + "-" + occurrence;
            events.add(new JobEvent(day * MS_PER_DAY, jobId, inputPaths));
        }
    }

    /** [min, maxExclusive)에서 rnd.nextDouble() 한 번을 소비해 long을 뽑는다. */
    private static long nextLongInRange(Random rnd, long min, long maxExclusive) {
        if (maxExclusive <= min) {
            return min;
        }
        long range = maxExclusive - min;
        return min + (long) (rnd.nextDouble() * range);
    }

    /** [minInclusive, maxExclusive)에서 rnd.nextDouble() 한 번을 소비해 int를 뽑는다. */
    private static int nextIntInRange(Random rnd, int minInclusive, int maxExclusive) {
        if (maxExclusive <= minInclusive) {
            return minInclusive;
        }
        int range = maxExclusive - minInclusive;
        return minInclusive + (int) (rnd.nextDouble() * range);
    }

    private static final class FileSpec {
        final String path;
        final long sizeBytes;
        final AccessGroup group;
        final int createdAtDay;

        FileSpec(String path, long sizeBytes, AccessGroup group, int createdAtDay) {
            this.path = path;
            this.sizeBytes = sizeBytes;
            this.group = group;
            this.createdAtDay = createdAtDay;
        }
    }
}
