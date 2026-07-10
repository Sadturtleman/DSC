package edu.dsc.tiering.scheduler;

import edu.dsc.tiering.config.AppConfig;
import edu.dsc.tiering.hdfs.HdfsApiCaller;
import edu.dsc.tiering.model.JobStatus;
import edu.dsc.tiering.model.PendingJob;
import edu.dsc.tiering.model.Tier;
import edu.dsc.tiering.repository.PendingJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BatchSchedulerTest {

    @Mock PendingJobRepository repo;
    @Mock HdfsApiCaller hdfs;

    private BatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        // concurrency=1: Mockito mock은 여러 스레드에서 동시에 호출되는 상황을 지원 보장하지
        // 않는다 (mock 내부 상태가 스레드 세이프하지 않음). 워커 2개 이상이면 두 job이 서로
        // 다른 스레드에서 같은 mock(hdfs)을 동시 호출해 검증 결과가 비결정적이 될 수 있다.
        AppConfig.Scheduler cfg = new AppConfig.Scheduler(
                10,
                List.of(new AppConfig.Scheduler.Window("test", "00:00", "00:00", 100, 100L)),
                1,
                3
        );
        scheduler = new BatchScheduler(repo, hdfs, cfg);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) scheduler.close();
    }

    @Test
    void emptyBatchIsNoOp() {
        scheduler.dispatch(List.of());
        verifyNoInteractions(hdfs);
        verifyNoInteractions(repo);
    }

    @Test
    void allSuccessRecordsNoFailures() throws Exception {
        scheduler.dispatch(List.of(job(1, "/a"), job(2, "/b")));

        verify(hdfs).applyTier("/a", Tier.COLD);
        verify(hdfs).applyTier("/b", Tier.COLD);
        verify(repo, never()).recordHdfsFailure(anyLong(), anyString(), anyInt());
    }

    @Test
    void hdfsFailureRecordsOnlyFailedJob() throws Exception {
        // applyTier에 이미 "/bad"용 stub이 있는 상태에서 "/ok"를 무stub으로 호출하면
        // strict stubbing이 이를 argument mismatch로 보고 PotentialStubbingProblem을 던진다.
        // "/ok"도 명시적으로 stub해서 두 인자 조합 모두 등록시켜야 한다.
        doNothing().when(hdfs).applyTier(eq("/ok"), any());
        doThrow(new IOException("nn down")).when(hdfs).applyTier(eq("/bad"), any());

        scheduler.dispatch(List.of(job(1, "/ok"), job(2, "/bad")));

        verify(hdfs).applyTier("/ok", Tier.COLD);
        verify(hdfs).applyTier("/bad", Tier.COLD);
        verify(repo, never()).recordHdfsFailure(eq(1L), anyString(), anyInt());
        verify(repo).recordHdfsFailure(eq(2L), contains("nn down"), eq(3));
    }

    @Test
    void runtimeExceptionAlsoRecordedAsFailure() throws Exception {
        doThrow(new IllegalStateException("boom")).when(hdfs).applyTier(eq("/oops"), any());

        scheduler.dispatch(List.of(job(7, "/oops")));

        verify(repo).recordHdfsFailure(eq(7L), contains("boom"), eq(3));
    }

    private static PendingJob job(long id, String path) {
        return new PendingJob(
                id, path, 1024L, Tier.HOT, Tier.COLD, 1.0,
                JobStatus.DISPATCHED,
                OffsetDateTime.now(), OffsetDateTime.now(), null,
                0, null, null
        );
    }
}
