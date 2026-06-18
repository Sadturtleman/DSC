package edu.dsc.tiering.hdfs;

import edu.dsc.tiering.config.AppConfig;
import edu.dsc.tiering.model.Tier;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.client.HdfsAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

public class HdfsApiCaller implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HdfsApiCaller.class);

    private final HdfsAdmin admin;
    private final FileSystem fs;
    private final Map<Tier, String> tierToPolicy;

    public HdfsApiCaller(AppConfig.Hdfs cfg) throws IOException {
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", cfg.fsDefaultName());
        if (cfg.user() != null && !cfg.user().isBlank()) {
            System.setProperty("HADOOP_USER_NAME", cfg.user());
        }
        URI nn = URI.create(cfg.fsDefaultName());
        this.admin = new HdfsAdmin(nn, conf);
        this.fs = FileSystem.get(nn, conf);
        this.tierToPolicy = Map.copyOf(cfg.policyMapping());
    }

    /**
     * 스토리지 정책 변경 + SPS 트리거. 두 RPC 모두 성공해야 정상 반환.
     * 실패 시 IOException 으로 즉시 전파 (호출자가 retry/상태 갱신 책임).
     */
    public void applyTier(String filePath, Tier targetTier) throws IOException {
        String policy = tierToPolicy.get(targetTier);
        if (policy == null) {
            throw new IllegalArgumentException("No HDFS policy mapped for tier: " + targetTier);
        }
        Path p = new Path(filePath);
        fs.setStoragePolicy(p, policy);
        admin.satisfyStoragePolicy(p);
        log.debug("applied tier={} policy={} path={}", targetTier, policy, filePath);
    }

    @Override
    public void close() throws IOException {
        if (fs != null) fs.close();
    }
}
