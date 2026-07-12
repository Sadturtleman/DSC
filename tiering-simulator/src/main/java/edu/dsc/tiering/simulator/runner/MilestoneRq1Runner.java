package edu.dsc.tiering.simulator.runner;

import edu.dsc.tiering.simulator.config.SimConstants;
import edu.dsc.tiering.simulator.config.SimConstantsLoader;
import edu.dsc.tiering.simulator.core.ClusterStateModel;
import edu.dsc.tiering.simulator.core.SimulationDriver;
import edu.dsc.tiering.simulator.meters.CostMeter;
import edu.dsc.tiering.simulator.meters.CostReport;
import edu.dsc.tiering.simulator.observe.PolicyRunner;
import edu.dsc.tiering.simulator.observe.SnapshotAssembler;
import edu.dsc.tiering.simulator.trace.AccessPatternMix;
import edu.dsc.tiering.simulator.trace.SizeBucket;
import edu.dsc.tiering.simulator.trace.SizeDistribution;
import edu.dsc.tiering.simulator.trace.SyntheticSpec;
import edu.dsc.tiering.simulator.trace.SyntheticTraceGenerator;
import edu.dsc.tiering.simulator.trace.TraceEvent;
import edu.dsc.tiering.spi.Params;
import edu.dsc.tiering.spi.TieringPolicy;
import edu.dsc.tiering.spi.policy.RuleWeightedPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * CLAUDE.md 구현 순서 6번, 1차 마일스톤: RQ1의 비용 축만 산출한다(job 완료시간 축은 순서
 * 7번 성능 측정기 이후). 합성 트레이스(파일 200개, 30일, hot/decaying/cold 혼합, 시드 42)에
 * 대해 (a) 정책 없음(전 파일 HOT 유지)과 (b) B1(RuleWeightedPolicy, 24h 주기)을 각각
 * 재생해 CostReport를 비교한다.
 * <p>
 * <b>주의</b>: sim-constants.yaml의 단가·용량이 아직 TODO 플레이스홀더이므로, 여기서 나오는
 * 절대 비용·절감률은 가상 값 기준이다 — bench/ 실측치로 교체되기 전까지는 논문 숫자로
 * 쓸 수 없다({@link SimConstantsLoader} 참고).
 */
public final class MilestoneRq1Runner {

    private static final Logger log = LoggerFactory.getLogger(MilestoneRq1Runner.class);

    private static final long DAY_MILLIS = 86_400_000L;

    static final long SEED = 42L;
    static final int FILE_COUNT = 200;

    /** SyntheticSpec.periodDays — 트레이스 이벤트(생성/접근/job)가 분포하는 기간. 지시된 30일. */
    static final int TRACE_PERIOD_DAYS = 30;

    /**
     * 시뮬레이션/비용 정산 지평선은 트레이스 기간보다 길다. B1의 티어 결정 임계값
     * (RuleWeightedPolicy의 HOT_TO_WARM_DAYS=30, WARM_TO_COLD_DAYS=90, PriorityRule과 동일,
     * override 불가)은 "마지막 접근 후 30/90일 경과"를 요구하는데, 30일짜리 트레이스
     * 안에서는 가장 이른 파일(0일차 생성)조차 트레이스가 끝나는 30일차에야 겨우
     * daysSinceAccess=30에 도달한다 — WARM 전이는 극히 일부만, COLD 전이는 사실상 아무도
     * 겪지 못한다. 트레이스 생성은 30일로 고정하되(지시사항), 정책 주기 평가와 비용 정산은
     * 트레이스 종료 후에도 TRACE_PERIOD_DAYS+WARM_TO_COLD_DAYS(90)일까지 계속 진행해
     * 가장 늦게 생성된 파일까지 COLD 전이 기회를 준다. (SimulationDriver는 트레이스가
     * 소진된 뒤에도 예약 이벤트만으로 horizon까지 계속 진행하도록 이미 설계되어 있다 —
     * 5번 구현의 lazy-pull 구조 그대로 재사용.)
     * <p>
     * 부작용: 트레이스 종료(30일) 이후로는 새 read 이벤트가 전혀 생성되지 않으므로, hot 그룹
     * 파일도 결국 나이가 들어 강등 대상이 된다("계속 접근되는 파일"이라는 그룹 의도가 트레이스
     * 밖에서는 유지되지 않음). 1차 마일스톤(정성적 비용 절감 증명)에는 문제 없지만, RQ1의
     * 최종 곡선을 위해서는 더 긴 트레이스나 트레이스 이후 접근 지속을 모델링해야 한다.
     */
    static final int SIMULATION_HORIZON_DAYS = TRACE_PERIOD_DAYS + 90;
    static final long HORIZON_MILLIS = (long) SIMULATION_HORIZON_DAYS * DAY_MILLIS;

    /** B1 실행에 쓰는 access_horizon_days — PriorityRule의 원래 기본값(override 없음). */
    private static final int B1_ACCESS_HORIZON_DAYS = 90;

    private MilestoneRq1Runner() {
    }

    public static void main(String[] args) throws IOException {
        Result result = runExperiment();
        printSummary(result);

        Path csvPath = RepoPaths.findRepoRoot().resolve(Path.of("paper", "data", "milestone-rq1-cost.csv"));
        writeCsv(result, csvPath);
        log.info("CSV 저장: {}", csvPath);
    }

    static Result runExperiment() throws IOException {
        SimConstants constants = SimConstantsLoader.loadDefault();
        SyntheticSpec spec = buildSpec();
        List<TraceEvent> trace = new SyntheticTraceGenerator().generate(spec);

        CostReport baseline = runScenario(constants, trace, null);
        CostReport b1 = runScenario(constants, trace, new RuleWeightedPolicy());

        double savingPct = (baseline.totalCost() - b1.totalCost()) / baseline.totalCost() * 100.0;
        return new Result(baseline, b1, savingPct);
    }

    private static SyntheticSpec buildSpec() {
        SizeDistribution sizes = new SizeDistribution(
                new SizeBucket(0.5, 1_000_000L, 10_000_000L),        // 소: 1MB~10MB
                new SizeBucket(0.3, 10_000_000L, 1_000_000_000L),    // 중: 10MB~1GB
                new SizeBucket(0.2, 1_000_000_000L, 50_000_000_000L)); // 대: 1GB~50GB
        AccessPatternMix mix = new AccessPatternMix(0.3, 0.3, 0.4); // hot/decaying/cold
        return new SyntheticSpec(SEED, FILE_COUNT, TRACE_PERIOD_DAYS, sizes, mix, null);
    }

    private static CostReport runScenario(SimConstants constants, List<TraceEvent> trace, TieringPolicy policy) {
        ClusterStateModel model = new ClusterStateModel(constants.tierCapacityBytes());
        CostMeter costMeter = new CostMeter(constants, 0L);

        SimulationDriver driver = new SimulationDriver(trace.iterator(), 0L, HORIZON_MILLIS);
        driver.addListener(model);
        driver.addListener(costMeter);

        if (policy != null) {
            SnapshotAssembler assembler = new SnapshotAssembler(model, new Params(0.5, B1_ACCESS_HORIZON_DAYS));
            PolicyRunner runner = new PolicyRunner(policy, assembler, model);
            runner.addPlacementChangeListener(costMeter);
            runner.attachTo(driver, PolicyRunner.DEFAULT_INTERVAL_MILLIS, PolicyRunner.DEFAULT_INTERVAL_MILLIS);
        }

        driver.run();
        return costMeter.finalizeReport(HORIZON_MILLIS, model.rejectionCount());
    }

    private static void printSummary(Result result) {
        log.warn("sim-constants.yaml이 아직 TODO 플레이스홀더입니다 — 아래 숫자는 가상 단가 기준이며 논문 숫자가 아닙니다.");
        log.info("=== RQ1 마일스톤: 비용 축 (파일 {}개, 트레이스 {}일 + 정산 지평선 {}일, seed={}) ===",
                FILE_COUNT, TRACE_PERIOD_DAYS, SIMULATION_HORIZON_DAYS, SEED);
        log.info("(a) 정책 없음(HOT 유지): {}", result.baseline);
        log.info("(b) B1-rule-weighted(24h 주기): {}", result.b1);
        log.info(String.format(Locale.ROOT, "비용 절감률 (a-b)/a = %.4f%%", result.costSavingPct));
    }

    private static void writeCsv(Result result, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent());
        try (Writer w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8)) {
            w.write("policy_id,param_set,cost_total,cost_saving_pct,avg_job_slowdown_pct,p95_job_slowdown_pct,migrated_bytes\n");
            w.write(String.format(Locale.ROOT, "B1-rule-weighted,access_horizon_days=%d;interval_hours=24,%.6f,%.6f,,,%d\n",
                    B1_ACCESS_HORIZON_DAYS, result.b1.totalCost(), result.costSavingPct, result.b1.migratedBytes()));
        }
    }

    static final class Result {
        final CostReport baseline;
        final CostReport b1;
        final double costSavingPct;

        Result(CostReport baseline, CostReport b1, double costSavingPct) {
            this.baseline = baseline;
            this.b1 = b1;
            this.costSavingPct = costSavingPct;
        }
    }
}
