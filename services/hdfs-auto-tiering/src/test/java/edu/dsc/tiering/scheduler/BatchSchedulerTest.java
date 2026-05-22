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
        AppConfig.Scheduler cfg = new AppConfig.Scheduler(
                10,
                List.of(new AppConfig.Scheduler.Window("test", "00:00", "00:00", 100, 100)),
                2,
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
