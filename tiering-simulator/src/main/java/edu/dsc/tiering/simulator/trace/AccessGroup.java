package edu.dsc.tiering.simulator.trace;

/**
 * {@link SyntheticTraceGenerator}가 파일마다 배정하는 접근 패턴.
 * <ul>
 *   <li>{@link #HOT} — 생성 이후 트레이스 끝까지 매일 접근(읽기)됨</li>
 *   <li>{@link #DECAYING} — 생성 직후 {@link SyntheticTraceGenerator#DECAY_WINDOW_DAYS}일간만
 *       접근되다가 이후 방치됨</li>
 *   <li>{@link #COLD} — 생성 이후 재접근 없음</li>
 * </ul>
 */
public enum AccessGroup {
    HOT,
    DECAYING,
    COLD
}
