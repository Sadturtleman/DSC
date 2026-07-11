package edu.dsc.tiering.simulator.core;

import edu.dsc.tiering.simulator.trace.TraceEvent;

/**
 * {@link SimulationDriver}에 끼어드는 리스너 접점. 측정기(meters)·관측 어댑터(observe)가
 * 코어 수정 없이 붙을 수 있도록 트레이스 이벤트와 시계 전진을 통지받는다.
 * <p>
 * 주기 반복 관측(fsimage 스냅샷 등)은 이 인터페이스가 아니라
 * {@link SimulationDriver#scheduleRepeating}로 별도 등록한다 — 모든 리스너에 매번 방송할
 * 필요가 없는, 특정 콜백 하나만의 주기 실행이기 때문이다.
 */
public interface SimListener {

    /** 트레이스 이벤트(file_create/job/read)가 처리될 때 호출된다. */
    void onEvent(long atMillis, TraceEvent event);

    /** 가상 시계가 실제로 전진했을 때(from &lt; to) 호출된다. */
    void onTimeAdvance(long fromMillis, long toMillis);
}
