package edu.dsc.tiering.simulator.trace;

/** 크기 구간 하나: 발생 확률(weight)과 바이트 범위 [minBytes, maxBytesExclusive). */
public final class SizeBucket {

    private final double weight;
    private final long minBytes;
    private final long maxBytesExclusive;

    public SizeBucket(double weight, long minBytes, long maxBytesExclusive) {
        if (weight < 0.0) {
            throw new IllegalArgumentException("weight는 음수일 수 없습니다: " + weight);
        }
        if (minBytes < 0 || maxBytesExclusive < minBytes) {
            throw new IllegalArgumentException(
                    "잘못된 바이트 범위: [" + minBytes + ", " + maxBytesExclusive + ")");
        }
        this.weight = weight;
        this.minBytes = minBytes;
        this.maxBytesExclusive = maxBytesExclusive;
    }

    public double weight() {
        return weight;
    }

    public long minBytes() {
        return minBytes;
    }

    public long maxBytesExclusive() {
        return maxBytesExclusive;
    }
}
