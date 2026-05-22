package edu.dsc.tiering;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.dsc.tiering.config.AppConfig;
import edu.dsc.tiering.config.ConfigLoader;
import edu.dsc.tiering.hdfs.HdfsApiCaller;
import edu.dsc.tiering.repository.PendingJobRepository;
import edu.dsc.tiering.scheduler.BatchScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        AppConfig cfg = args.length > 0
                ? ConfigLoader.fromFile(Paths.get(args[0]))
                : ConfigLoader.fromClasspath("application.yaml");
        log.info("loaded config (db={}, namenode={})",
                cfg.database().url(), cfg.hdfs().fsDefaultName());

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.database().url());
        hc.setUsername(cfg.database().username());
        hc.setPassword(cfg.database().password());
        hc.setMaximumPoolSize(cfg.database().pool().maximumPoolSize());
        hc.setMinimumIdle(cfg.database().pool().minimumIdle());
        hc.setPoolName("dsc-tiering-pool");

        try (HikariDataSource ds = new HikariDataSource(hc);
             HdfsApiCaller hdfs = new HdfsApiCaller(cfg.hdfs());
             BatchScheduler scheduler = new BatchScheduler(
                     new PendingJobRepository(ds), hdfs, cfg.scheduler())) {

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("shutdown signal received");
                scheduler.stop();
            }, "shutdown-hook"));

            scheduler.run();
        }
    }
}
