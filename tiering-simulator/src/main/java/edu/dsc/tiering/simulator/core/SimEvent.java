package edu.dsc.tiering.simulator.core;

import edu.dsc.tiering.simulator.trace.TraceEvent;

/**
 * {@link SimulationDriver}의 {@code PriorityQueue<SimEvent>}에 들어가는 원소. 트레이스
 * 이벤트와 내부 예약(reserved) 이벤트를 같은 큐에서 시간순으로 다루기 위한 래퍼다.
 * <p>
 * 동시각(tie) 처리 순서는 (1) at 오름차순 → (2) TRACE가 RESERVED보다 먼저 → (3) 같은
 * 종류끼리는 삽입 순서(sequence)로 결정된다. TRACE는 큐에 한 번에 최대 하나만 들어가므로
 * (2)는 사실상 "이 시각에 트레이스 이벤트가 있으면 예약 콜백보다 먼저 처리"라는 뜻이고,
 * (3)은 같은 시각에 등록된 여러 예약 이벤트 사이의 결정성을 보장한다.
 */
final class SimEvent implements Comparable<SimEvent> {

    private enum Kind { TRACE, RESERVED }

    private final long at;
    private final Kind kind;
    private final long sequence;
    private final TraceEvent traceEvent;
    private final ReservedRegistration reserved;

    private SimEvent(long at, Kind kind, long sequence, TraceEvent traceEvent, ReservedRegistration reserved) {
        this.at = at;
        this.kind = kind;
        this.sequence = sequence;
        this.traceEvent = traceEvent;
        this.reserved = reserved;
    }

    static SimEvent ofTrace(TraceEvent event, long sequence) {
        return new SimEvent(event.at(), Kind.TRACE, sequence, event, null);
    }

    static SimEvent ofReserved(long at, ReservedRegistration registration, long sequence) {
        return new SimEvent(at, Kind.RESERVED, sequence, null, registration);
    }

    long at() {
        return at;
    }

    boolean isTrace() {
        return kind == Kind.TRACE;
    }

    TraceEvent traceEvent() {
        return traceEvent;
    }

    ReservedRegistration reserved() {
        return reserved;
    }

    @Override
    public int compareTo(SimEvent other) {
        int c = Long.compare(at, other.at);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(kind.ordinal(), other.kind.ordinal());
        if (c != 0) {
            return c;
        }
        return Long.compare(sequence, other.sequence);
    }
}
