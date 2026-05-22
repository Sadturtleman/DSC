package edu.dsc.tiering.hdfs;

import edu.dsc.tiering.model.Tier;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * HDFS 블록의 실제 스토리지 타입을 조회해 목표 정책 충족 여부를 반환한다.
 *
 * <p>hdfs-auto-tiering 의 {@code HdfsApiCaller} 와 같은 {@code hdfs/} 패키지에 위치.
 * HdfsApiCaller 가 정책 변경·SPS 호출을 담당하고,
 * HdfsPolicyChecker 는 이동 완료 여부 검증만 담당한다.
 *
 * <p>완료 판정 기준 (Tier 별):
 * <ul>
 *   <li>HOT  (All_SSD)  : 전체 블록 스토리지 타입의 95 % 이상이 SSD</li>
 *   <li>WARM (One_SSD)  : SSD 타입 블록이 1개 이상 존재</li>
 *   <li>COLD (Cold)     : 전체 블록 스토리지 타입의 95 % 이상이 ARCHIVE</li>
 * </ul>
 *
 * <p>설계 결정:
 * {@code hdfs fsck} subprocess 대신 {@code DFSClient.getBlockLocations} 직접 호출.
 * fsck 는 파일 트리 전체를 순회하며 NameNode read lock 을 오래 점유하지만,
 * getBlockLocations 는 해당 파일만 조회하고 즉시 락을 해제해 NN 부하가 적다.
 */
public class HdfsPolicyChecker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HdfsPolicyChecker.class);
    private static final double COMPLETION_RATIO = 0.95;

    private final DistributedFileSystem dfs;

    // ── 생성자 ──────────────────────────────────────────────────────────

    public HdfsPolicyChecker(String fsDefaultName, String confDir) throws IOException {
        Configuration conf = new Configuration();
        if (confDir != null && !confDir.isBlank()) {
            conf.addResource(new Path(confDir + "/core-site.xml"));
            conf.addResource(new Path(confDir + "/hdfs-site.xml"));
        }
        conf.set("fs.defaultFS", fsDefaultName);
        FileSystem fs = FileSystem.get(URI.create(fsDefaultName), conf);
        if (!(fs instanceof DistributedFileSystem)) {
            throw new IllegalStateException("fs.defaultFS 가 HDFS 가 아님: " + fsDefaultName);
        }
        this.dfs = (DistributedFileSystem) fs;
        log.info("HdfsPolicyChecker 초기화. NN={}", fsDefaultName);
    }

    /** 테스트용 — 이미 생성된 DFS 주입 */
    HdfsPolicyChecker(DistributedFileSystem dfs) {
        this.dfs = dfs;
    }

    // ── 공개 API ────────────────────────────────────────────────────────

    /**
     * 파일의 블록 스토리지 타입을 샘플링해 목표 정책 충족 여부를 반환한다.
     * IOException 등 예외 발생 시 {@code false} 를 반환해 다음 사이클에서 재시도하게 한다.
     */
    public boolean isSatisfied(String filePath, Tier targetTier) {
        try {
            Path path = new Path(filePath);
            FileStatus status = dfs.getFileStatus(path);
            LocatedBlocks blocks = dfs.getClient()
                    .getLocatedBlocks(filePath, 0, status.getLen());

            if (blocks == null || blocks.getLocatedBlocks().isEmpty()) {
                log.warn("블록 정보 없음 path={}", filePath);
                return false;
            }
            return evaluate(blocks.getLocatedBlocks(), targetTier);

        } catch (IOException e) {
            log.error("HDFS 블록 조회 실패 path={}: {}", filePath, e.getMessage());
            return false;
        }
    }

    // ── 내부 판정 로직 ──────────────────────────────────────────────────

    private boolean evaluate(List<LocatedBlock> blocks, Tier tier) {
        String[] expected = tier.expectedStorageTypes();
        int total = 0, hits = 0;

        for (LocatedBlock block : blocks) {
            if (block.getStorageTypes() == null) continue;
            for (var st : block.getStorageTypes()) {
                total++;
                if (matches(st.name(), expected)) hits++;
            }
        }

        if (total == 0) {
            log.warn("스토리지 타입 정보 없음");
            return false;
        }

        // WARM(One_SSD): SSD 1개 이상 존재하면 충족
        if (tier.isMixedPolicy()) return hits > 0;

        double ratio = (double) hits / total;
        log.debug("완료율 tier={} {}/{} = {:.2f}", tier, hits, total, ratio);
        return ratio >= COMPLETION_RATIO;
    }

    private boolean matches(String actual, String[] expected) {
        return Arrays.stream(expected).anyMatch(e -> e.equalsIgnoreCase(actual));
    }

    @Override
    public void close() throws IOException {
        dfs.close();
    }
}
