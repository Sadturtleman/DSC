package edu.dsc.tiering.simulator.runner;

import edu.dsc.tiering.simulator.meters.CostReport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLAUDE.md 구현 순서 6번, 완료 조건: 마일스톤 실행 출력(두 CostReport와 절감률)을 보여준다
 * (System.out — surefire 콘솔 로그에서 확인 가능) + CSV 산출물 검증.
 */
class MilestoneRq1RunnerTest {

    @Test
    void b1CostsLessThanKeepingEverythingHot() throws IOException {
        MilestoneRq1Runner.Result result = MilestoneRq1Runner.runExperiment();
        CostReport baseline = result.baseline;
        CostReport b1 = result.b1;

        System.out.println("=== RQ1 마일스톤 실행 결과 (파일 " + MilestoneRq1Runner.FILE_COUNT
                + "개, 트레이스 " + MilestoneRq1Runner.TRACE_PERIOD_DAYS + "일 + 정산 지평선 "
                + MilestoneRq1Runner.SIMULATION_HORIZON_DAYS + "일, seed=" + MilestoneRq1Runner.SEED + ") ===");
        System.out.println("(a) 정책 없음(HOT 유지): " + baseline);
        System.out.println("(b) B1-rule-weighted(24h 주기): " + b1);
        System.out.printf(Locale.ROOT, "비용 절감률 (a-b)/a = %.4f%%%n", result.costSavingPct);

        assertTrue(baseline.totalCost() > 0, "베이스라인 비용은 0보다 커야 한다");
        assertTrue(b1.totalCost() > 0, "B1 비용은 0보다 커야 한다");

        assertEquals(0L, baseline.migratedBytes(), "정책이 없으면 이관이 전혀 없어야 한다");
        assertEquals(0.0, baseline.migrationCost(), 1e-9);
        assertEquals(0L, baseline.rejectionCount(), "충분한 용량 하에서는 거부가 없어야 한다");
        assertEquals(0L, b1.rejectionCount());

        assertTrue(b1.migratedBytes() > 0, "B1은 최소 일부 파일을 WARM/COLD로 옮겨야 한다");
        assertTrue(b1.totalCost() < baseline.totalCost(),
                "B1은 오래된 파일을 저렴한 티어로 옮기므로 HOT 고정보다 저렴해야 한다");
        assertTrue(result.costSavingPct > 0, "비용 절감률은 양수여야 한다: " + result.costSavingPct);
    }

    @Test
    void mainWritesCsvHeaderAndOneDataRowForB1() throws IOException {
        MilestoneRq1Runner.main(new String[0]);

        Path csvPath = RepoPaths.findRepoRoot().resolve(Path.of("paper", "data", "milestone-rq1-cost.csv"));
        assertTrue(Files.exists(csvPath), "CSV가 생성되어야 한다: " + csvPath);

        List<String> lines = Files.readAllLines(csvPath);
        assertEquals(2, lines.size(), "헤더 1줄 + 데이터 1줄이어야 한다");
        assertEquals("policy_id,param_set,cost_total,cost_saving_pct,avg_job_slowdown_pct,p95_job_slowdown_pct,migrated_bytes",
                lines.get(0));
        assertTrue(lines.get(1).startsWith("B1-rule-weighted,access_horizon_days=90;interval_hours=24,"));

        String[] fields = lines.get(1).split(",", -1);
        assertEquals(7, fields.length);
        assertEquals("", fields[4]); // avg_job_slowdown_pct 비어있음
        assertEquals("", fields[5]); // p95_job_slowdown_pct 비어있음
        assertTrue(Long.parseLong(fields[6]) > 0); // migrated_bytes
    }
}
