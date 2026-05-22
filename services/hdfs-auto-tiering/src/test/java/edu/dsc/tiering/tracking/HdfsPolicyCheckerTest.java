package edu.dsc.tiering.tracking;

import edu.dsc.tiering.hdfs.HdfsPolicyChecker;
import edu.dsc.tiering.model.Tier;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HdfsPolicyChecker 단위 테스트.
 * Docker 불필요 — DistributedFileSystem / DFSClient 를 Mockito 로 대체.
 */
class HdfsPolicyCheckerTest {

    private DistributedFileSystem mockDfs;
    private DFSClient             mockClient;
    private HdfsPolicyChecker     checker;

    @BeforeEach
    void setUp() throws Exception {
        mockDfs    = mock(DistributedFileSystem.class);
        mockClient = mock(DFSClient.class);
        when(mockDfs.getClient()).thenReturn(mockClient);
        checker    = new HdfsPolicyChecker(mockDfs); // package-private 생성자
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    /** 단일 블록, 지정 타입 배열 */
    private void givenBlocks(String path, StorageType... types) throws IOException {
        FileStatus fs = mock(FileStatus.class);
        when(fs.getLen()).thenReturn(128L * 1024 * 1024);
        when(mockDfs.getFileStatus(new Path(path))).thenReturn(fs);

        LocatedBlock block = mock(LocatedBlock.class);
        when(block.getStorageTypes()).thenReturn(types);

        LocatedBlocks blocks = mock(LocatedBlocks.class);
        when(blocks.getLocatedBlocks()).thenReturn(List.of(block));
        when(mockClient.getLocatedBlocks(eq(path), anyLong(), anyLong()))
                .thenReturn(blocks);
    }

    /** 다중 블록 — 블록별 타입 배열 */
    private void givenMultipleBlocks(String path,
                                     List<StorageType[]> typesPerBlock) throws IOException {
        FileStatus fs = mock(FileStatus.class);
        when(fs.getLen()).thenReturn(512L * 1024 * 1024);
        when(mockDfs.getFileStatus(new Path(path))).thenReturn(fs);

        List<LocatedBlock> blockList = new ArrayList<>();
        for (StorageType[] types : typesPerBlock) {
            LocatedBlock b = mock(LocatedBlock.class);
            when(b.getStorageTypes()).thenReturn(types);
            blockList.add(b);
        }
        LocatedBlocks blocks = mock(LocatedBlocks.class);
        when(blocks.getLocatedBlocks()).thenReturn(blockList);
        when(mockClient.getLocatedBlocks(eq(path), anyLong(), anyLong()))
                .thenReturn(blocks);
    }

    // ── COLD 정책 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("COLD: 모든 블록 ARCHIVE → true")
    void cold_allArchive() throws Exception {
        givenBlocks("/cold/file.log", StorageType.ARCHIVE);
        assertThat(checker.isSatisfied("/cold/file.log", Tier.COLD)).isTrue();
    }

    @Test
    @DisplayName("COLD: 모든 블록 DISK → false")
    void cold_allDisk() throws Exception {
        givenBlocks("/cold/file.log", StorageType.DISK);
        assertThat(checker.isSatisfied("/cold/file.log", Tier.COLD)).isFalse();
    }

    @Test
    @DisplayName("COLD: 정확히 95% ARCHIVE → true (경계값)")
    void cold_exactly95pct() throws Exception {
        // 20블록: 19 ARCHIVE + 1 DISK = 95%
        List<StorageType[]> types = new ArrayList<>();
        for (int i = 0; i < 19; i++) types.add(new StorageType[]{StorageType.ARCHIVE});
        types.add(new StorageType[]{StorageType.DISK});
        givenMultipleBlocks("/cold/file.log", types);
        assertThat(checker.isSatisfied("/cold/file.log", Tier.COLD)).isTrue();
    }

    @Test
    @DisplayName("COLD: 94% ARCHIVE → false")
    void cold_94pct() throws Exception {
        // 50블록: 47 ARCHIVE + 3 DISK = 94%
        List<StorageType[]> types = new ArrayList<>();
        for (int i = 0; i < 47; i++) types.add(new StorageType[]{StorageType.ARCHIVE});
        for (int i = 0; i < 3;  i++) types.add(new StorageType[]{StorageType.DISK});
        givenMultipleBlocks("/cold/file.log", types);
        assertThat(checker.isSatisfied("/cold/file.log", Tier.COLD)).isFalse();
    }

    // ── HOT 정책 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("HOT: 모든 블록 SSD → true")
    void hot_allSsd() throws Exception {
        givenBlocks("/hot/file.orc", StorageType.SSD);
        assertThat(checker.isSatisfied("/hot/file.orc", Tier.HOT)).isTrue();
    }

    @Test
    @DisplayName("HOT: SSD/DISK 50% 혼합 → false")
    void hot_mixed50pct() throws Exception {
        givenMultipleBlocks("/hot/file.orc", List.of(
                new StorageType[]{StorageType.SSD},
                new StorageType[]{StorageType.DISK}));
        assertThat(checker.isSatisfied("/hot/file.orc", Tier.HOT)).isFalse();
    }

    // ── WARM 정책 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("WARM: SSD 1개 + DISK 2개 → true (One_SSD 정책)")
    void warm_oneSsd() throws Exception {
        givenMultipleBlocks("/warm/file.parquet", List.of(
                new StorageType[]{StorageType.SSD},
                new StorageType[]{StorageType.DISK},
                new StorageType[]{StorageType.DISK}));
        assertThat(checker.isSatisfied("/warm/file.parquet", Tier.WARM)).isTrue();
    }

    @Test
    @DisplayName("WARM: SSD 없이 모두 DISK → false")
    void warm_noSsd() throws Exception {
        givenBlocks("/warm/file.parquet", StorageType.DISK);
        assertThat(checker.isSatisfied("/warm/file.parquet", Tier.WARM)).isFalse();
    }

    // ── 엣지 케이스 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("빈 블록 목록 → false")
    void emptyBlocks_false() throws Exception {
        FileStatus fs = mock(FileStatus.class);
        when(fs.getLen()).thenReturn(0L);
        when(mockDfs.getFileStatus(any())).thenReturn(fs);
        LocatedBlocks blocks = mock(LocatedBlocks.class);
        when(blocks.getLocatedBlocks()).thenReturn(List.of());
        when(mockClient.getLocatedBlocks(anyString(), anyLong(), anyLong()))
                .thenReturn(blocks);

        assertThat(checker.isSatisfied("/empty/file", Tier.COLD)).isFalse();
    }

    @Test
    @DisplayName("IOException (NN 다운) → false, 예외 미전파")
    void ioException_returnsFalse() throws Exception {
        when(mockDfs.getFileStatus(any())).thenThrow(new IOException("NN down"));
        assertThat(checker.isSatisfied("/fail/file", Tier.COLD)).isFalse();
    }
}
