package edu.dsc.tiering.simulator.core;

import edu.dsc.tiering.simulator.trace.TraceEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * {@code PriorityQueue<SimEvent>} 기반 재생 드라이버. 트레이스 이벤트와 내부 예약(reserved)
 * 이벤트(주기 반복 포함)를 시간순으로 처리한다.
 * <p>
 * 트레이스 전체를 큐에 미리 채우지 않는다 — {@link TraceEvent} 이터레이터에서 항상 "아직
 * 소비하지 않은 다음 한 개"만 큐에 넣어두고(lazy pull), 처리될 때마다 그 다음 것을 채운다.
 * 이렇게 하면 큐에는 예약 이벤트들 + 트레이스 이벤트 최대 1개만 존재해 대용량 트레이스에서도
 * 메모리가 예약 이벤트 개수에만 비례한다.
 * <p>
 * <b>horizon이 반드시 필요한 이유:</b> 주기 반복(scheduleRepeating) 예약 이벤트는 발화할
 * 때마다 스스로를 다시 큐에 넣는다. 트레이스가 소진된 뒤에도 반복 이벤트가 남아있으면 큐가
 * 절대 비지 않아 {@link #run()}이 끝나지 않는다. 그래서 생성자에 {@code horizonMillis}를
 * 받아, 그 시각을 넘어서는 이벤트는 아예 처리(및 재등록)하지 않는다.
 */
public final class SimulationDriver {

    private final PriorityQueue<SimEvent> queue = new PriorityQueue<>();
    private final Iterator<TraceEvent> traceSource;
    private final VirtualClock clock;
    private final long horizonMillis;
    private final List<SimListener> listeners = new ArrayList<>();
    private long sequenceCounter = 0;

    public SimulationDriver(Iterator<TraceEvent> traceSource, long startAtMillis, long horizonMillis) {
        if (horizonMillis < startAtMillis) {
            throw new IllegalArgumentException(
                    "horizonMillis(" + horizonMillis + ")는 startAtMillis(" + startAtMillis + ") 이상이어야 합니다");
        }
        this.traceSource = traceSource;
        this.clock = new VirtualClock(startAtMillis);
        this.horizonMillis = horizonMillis;
        pullNextTraceEvent();
    }

    public VirtualClock clock() {
        return clock;
    }

    public void addListener(SimListener listener) {
        listeners.add(listener);
    }

    /** at(&gt;=현재 시각일 필요 없음, horizon 이내면 언제든) 1회 발화. */
    public void scheduleOnce(long at, ReservedCallback callback) {
        enqueueReserved(at, new ReservedRegistration(callback, 0));
    }

    /** firstAt부터 intervalMillis 간격으로 horizon까지 반복 발화. */
    public void scheduleRepeating(long firstAt, long intervalMillis, ReservedCallback callback) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis는 양수여야 합니다: " + intervalMillis);
        }
        enqueueReserved(firstAt, new ReservedRegistration(callback, intervalMillis));
    }

    /** 큐가 빌 때까지(또는 남은 이벤트가 모두 horizon을 넘을 때까지) 처리한다. */
    public void run() {
        while (!queue.isEmpty() && queue.peek().at() <= horizonMillis) {
            SimEvent next = queue.poll();
            advanceClockTo(next.at());

            if (next.isTrace()) {
                TraceEvent event = next.traceEvent();
                for (SimListener listener : listeners) {
                    listener.onEvent(clock.now(), event);
                }
                pullNextTraceEvent();
            } else {
                ReservedRegistration reserved = next.reserved();
                reserved.callback().onFire(clock.now());
                if (reserved.isRepeating()) {
                    long nextAt = next.at() + reserved.intervalMillis();
                    if (nextAt <= horizonMillis) {
                        enqueueReserved(nextAt, reserved);
                    }
                }
            }
        }
    }

    private void enqueueReserved(long at, ReservedRegistration registration) {
        if (at > horizonMillis) {
            return; // horizon 밖 — 등록만 스킵, 오류는 아님(호출부가 매번 horizon을 알 필요 없게)
        }
        queue.add(SimEvent.ofReserved(at, registration, sequenceCounter++));
    }

    private void pullNextTraceEvent() {
        if (traceSource.hasNext()) {
            queue.add(SimEvent.ofTrace(traceSource.next(), sequenceCounter++));
        }
    }

    private void advanceClockTo(long at) {
        long from = clock.now();
        if (at > from) {
            clock.advanceTo(at);
            for (SimListener listener : listeners) {
                listener.onTimeAdvance(from, at);
            }
        }
    }
}
