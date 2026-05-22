package edu.dsc.tiering.tracking;

import edu.dsc.tiering.config.AppConfig;
import edu.dsc.tiering.hdfs.HdfsPolicyChecker;
import edu.dsc.tiering.model.PendingJob;
import edu.dsc.tiering.model.Tier;
import edu.dsc.tiering.model.JobStatus;
import edu.dsc.tiering.repository.PendingJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CompletionTracker 단위 테스트.
 * Docker 불필요 — PendingJobRepository / HdfsPolicyChecker 를 Mockito 로 대체.
 *
 * <p>hdfs-auto-tiering {@code BatchSchedulerTest} 와 동일한 패턴.
 * {@code runCycle()} 을 직접 호출해 상태 전이 로직만 격리해서 검증한다.
 */
class CompletionTrackerTest {

    private PendingJobRepository mockRepo;
    private HdfsPolicyChecker    mockChecker;
    private CompletionTracker    tracker;

    /** application.yaml 과 동일한 기본값 */
    private static final AppConfig.TrackerSettings CFG =
            new AppConfig.TrackerSettings(45, 60, 20, 0.95, 5, 3);

    @BeforeEach
    void setUp() {
        mockRepo    = mock(PendingJobRepository.class);
        mockChecker = mock(HdfsPolicyChecker.class);
        tracker     = new CompletionTracker(CFG, mockRepo, mockChecker);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private PendingJob job(long id, Tier tier, int minutesAgo) {
        return new PendingJob(
                id,
                "/data/file" + id + ".log",
                1024L,
                Tier.HOT,
                tier,
                100.0,
                JobStatus.DISPATCHED,
                java.time.OffsetDateTime.now(),
                java.time.OffsetDateTime.now().minus(minutesAgo, ChronoUnit.MINUTES),
                null,
                0,
                null,
                null);
    }

    // ── 빈 배치 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("DISPATCHED job 없음 → checker / repo 갱신 미호출")
    void emptyBatch_noOp() {
        doReturn(List.of()).when(mockRepo).claimBatch(anyInt());

        tracker.runCycle();

        verify(mockChecker, never()).isSatisfied(any(), any());
        verify(mockRepo,    never()).markCompleted(anyLong());
        verify(mockRepo,    never()).markFailed(anyLong());
        verify(mockRepo,    never()).touchCheckedAt(anyLong());
    }

    // ── 상태 전이 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("블록 이동 완료 → markCompleted 호출")
    void blockMoved_markCompleted() {
        PendingJob j = job(1L, Tier.COLD, 10);
        doReturn(List.of(j)).when(mockRepo).claimBatch(anyInt());
        when(mockChecker.isSatisfied(j.filePath(), Tier.COLD)).thenReturn(true);

        tracker.runCycle();

        verify(mockRepo).markCompleted(1L);
        verify(mockRepo, never()).markFailed(anyLong());
        verify(mockRepo, never()).touchCheckedAt(anyLong());
    }

    @Test
    @DisplayName("블록 이동 미완료 → touchCheckedAt 호출, 상태 변경 없음")
    void blockNotMoved_touchCheckedAt() {
        PendingJob j = job(2L, Tier.COLD, 10);
        doReturn(List.of(j)).when(mockRepo).claimBatch(anyInt());
        when(mockChecker.isSatisfied(j.filePath(), Tier.COLD)).thenReturn(false);

        tracker.runCycle();

        verify(mockRepo).touchCheckedAt(2L);
        verify(mockRepo, never()).markCompleted(anyLong());
        verify(mockRepo, never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("타임아웃(60분) 초과 → markFailed 호출 (checker 결과 무관)")
    void timeout_markFailed() {
        PendingJob j = job(3L, Tier.COLD, 90); // 90분 전 DISPATCHED
        doReturn(List.of(j)).when(mockRepo).claimBatch(anyInt());
        when(mockChecker.isSatisfied(any(), any())).thenReturn(true); // 완료여도 타임아웃 우선

        tracker.runCycle();

        verify(mockRepo).markFailed(3L);
        verify(mockRepo, never()).markCompleted(anyLong());
    }

    // ── 배치 독립성 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("배치 내 일부 완료 + 일부 미완료 → 각 job 독립 처리")
    void partialBatch_independent() {
        PendingJob ok   = job(10L, Tier.COLD, 5);
        PendingJob fail = job(11L, Tier.COLD, 5);
        doReturn(List.of(ok, fail)).when(mockRepo).claimBatch(anyInt());
        when(mockChecker.isSatisfied(ok.filePath(),   Tier.COLD)).thenReturn(true);
        when(mockChecker.isSatisfied(fail.filePath(), Tier.COLD)).thenReturn(false);

        tracker.runCycle();

        verify(mockRepo).markCompleted(10L);
        verify(mockRepo).touchCheckedAt(11L);
        verify(mockRepo, never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("배치 내 일부 타임아웃 + 일부 정상 → 독립 처리")
    void partialTimeout_independent() {
        PendingJob fresh   = job(20L, Tier.COLD,  5);
        PendingJob expired = job(21L, Tier.COLD, 90);
        doReturn(List.of(fresh, expired)).when(mockRepo).claimBatch(anyInt());
        when(mockChecker.isSatisfied(fresh.filePath(), Tier.COLD)).thenReturn(true);

        tracker.runCycle();

        verify(mockRepo).markCompleted(20L);
        verify(mockRepo).markFailed(21L);
    }

    // ── HOT / WARM 정책 전이 ─────────────────────────────────────────────

    @Test
    @DisplayName("HOT 정책 완료 → markCompleted")
    void hot_completed() {
        PendingJob j = job(30L, Tier.HOT, 5);
        doReturn(List.of(j)).when(mockRepo).claimBatch(anyInt());
        when(mockChecker.isSatisfied(j.filePath(), Tier.HOT)).thenReturn(true);

        tracker.runCycle();

        verify(mockRepo).markCompleted(30L);
    }

    @Test
    @DisplayName("WARM 정책 완료 → markCompleted")
    void warm_completed() {
        PendingJob j = job(31L, Tier.WARM, 5);
        doReturn(List.of(j)).when(mockRepo).claimBatch(anyInt());
        when(mockChecker.isSatisfied(j.filePath(), Tier.WARM)).thenReturn(true);

        tracker.runCycle();

        verify(mockRepo).markCompleted(31L);
    }

    // ── HDFS 오류 복원력 ──────────────────────────────────────────────────

    @Test
    @DisplayName("checker RuntimeException → touchCheckedAt, 다음 사이클에서 재시도")
    void checkerException_touchCheckedAt() {
        PendingJob j = job(40L, Tier.COLD, 5);
        doReturn(List.of(j)).when(mockRepo).claimBatch(anyInt());
        when(mockChecker.isSatisfied(any(), any()))
                .thenThrow(new RuntimeException("NN unreachable"));

        tracker.runCycle();

        verify(mockRepo).touchCheckedAt(40L);
        verify(mockRepo, never()).markCompleted(anyLong());
        verify(mockRepo, never()).markFailed(anyLong());
    }
}
