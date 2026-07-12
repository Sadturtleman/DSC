package edu.dsc.tiering.simulator.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import edu.dsc.tiering.simulator.model.PhysicalTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * {@code sim-constants.yaml}(CLAUDE.md의 키 구조)을 로드한다. 각 값이 문자열 {@code "TODO"}면
 * 경고 로그를 남기고 아래 {@code PLACEHOLDER_*} 임시 기본값을 대신 사용한다 — yaml 파일의
 * TODO 표시 자체는 절대 지우지 않는다({@link #load} 어디에서도 파일을 쓰지 않는다).
 * <p>
 * <b>PLACEHOLDER_* 값은 논문 숫자 산출에 쓰면 안 된다.</b> bench/ 마이크로벤치마크 실측치로
 * sim-constants.yaml의 TODO가 채워지기 전까지, 시뮬레이터가 end-to-end로 돌아가는지
 * 확인하는 용도로만 존재한다(구현 순서 12번에서 교체 예정).
 */
public final class SimConstantsLoader {

    private static final Logger log = LoggerFactory.getLogger(SimConstantsLoader.class);
    private static final String TODO_SENTINEL = "TODO";
    private static final String DEFAULT_RESOURCE_PATH = "/sim-constants.yaml";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private static final Map<PhysicalTier, Long> PLACEHOLDER_THROUGHPUT_BYTES_PER_SEC = tierLongMap(
            500_000_000L, 150_000_000L, 20_000_000L); // SSD, DISK, ARCHIVE
    private static final long PLACEHOLDER_MIGRATION_BANDWIDTH_BYTES_PER_SEC = 100_000_000L;
    private static final long PLACEHOLDER_RATE_LIMIT_BYTES_PER_SEC = 200_000_000L;
    // SSD:DISK:ARCHIVE = 6:1:0.3 (구현 순서 6번 항목 7 지시 — 가상 값, 실측치 아님)
    private static final Map<PhysicalTier, Double> PLACEHOLDER_PRICE_PER_GB_HOUR = tierDoubleMap(
            0.06, 0.01, 0.003); // SSD, DISK, ARCHIVE
    private static final double PLACEHOLDER_MIGRATION_PRICE_PER_GB = 0.01;
    private static final Map<PhysicalTier, Long> PLACEHOLDER_TIER_CAPACITY_BYTES = tierLongMap(
            1_000_000_000_000L, 10_000_000_000_000L, 100_000_000_000_000L); // SSD, DISK, ARCHIVE

    private SimConstantsLoader() {
    }

    /** 클래스패스의 tiering-simulator/src/main/resources/sim-constants.yaml을 로드한다. */
    public static SimConstants loadDefault() throws IOException {
        try (InputStream in = SimConstantsLoader.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (in == null) {
                throw new IOException("클래스패스에서 " + DEFAULT_RESOURCE_PATH + "를 찾을 수 없습니다");
            }
            return load(in);
        }
    }

    public static SimConstants load(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        }
    }

    public static SimConstants load(InputStream in) throws IOException {
        JsonNode root = YAML_MAPPER.readTree(in);
        return new SimConstants(
                resolveTierLongMap(root, "throughput_bytes_per_sec", PLACEHOLDER_THROUGHPUT_BYTES_PER_SEC),
                resolveScalarLong(root, "migration_bandwidth_bytes_per_sec", PLACEHOLDER_MIGRATION_BANDWIDTH_BYTES_PER_SEC),
                resolveScalarLong(root, "rate_limit_bytes_per_sec", PLACEHOLDER_RATE_LIMIT_BYTES_PER_SEC),
                resolveTierDoubleMap(root, "price_per_gb_hour", PLACEHOLDER_PRICE_PER_GB_HOUR),
                resolveScalarDouble(root, "migration_price_per_gb", PLACEHOLDER_MIGRATION_PRICE_PER_GB),
                resolveTierLongMap(root, "tier_capacity_bytes", PLACEHOLDER_TIER_CAPACITY_BYTES));
    }

    // ── 필드별 resolve ───────────────────────────────────────────────────────

    private static long resolveScalarLong(JsonNode root, String field, long placeholder) {
        JsonNode value = root.get(field);
        if (isTodo(value)) {
            warnTodo(field, null, placeholder);
            return placeholder;
        }
        if (value == null || !value.isNumber()) {
            throw new SimConstantsFormatException(field + " 값이 없거나 숫자/TODO가 아닙니다: " + value);
        }
        return value.asLong();
    }

    private static double resolveScalarDouble(JsonNode root, String field, double placeholder) {
        JsonNode value = root.get(field);
        if (isTodo(value)) {
            warnTodo(field, null, placeholder);
            return placeholder;
        }
        if (value == null || !value.isNumber()) {
            throw new SimConstantsFormatException(field + " 값이 없거나 숫자/TODO가 아닙니다: " + value);
        }
        return value.asDouble();
    }

    private static Map<PhysicalTier, Long> resolveTierLongMap(JsonNode root, String field,
                                                                Map<PhysicalTier, Long> placeholders) {
        JsonNode node = root.get(field);
        Map<PhysicalTier, Long> result = new EnumMap<>(PhysicalTier.class);
        for (PhysicalTier tier : PhysicalTier.values()) {
            JsonNode value = node == null ? null : node.get(tier.name());
            if (isTodo(value)) {
                long fallback = placeholders.get(tier);
                warnTodo(field, tier, fallback);
                result.put(tier, fallback);
            } else if (value != null && value.isNumber()) {
                result.put(tier, value.asLong());
            } else {
                throw new SimConstantsFormatException(
                        field + "." + tier + " 값이 없거나 숫자/TODO가 아닙니다: " + value);
            }
        }
        return result;
    }

    private static Map<PhysicalTier, Double> resolveTierDoubleMap(JsonNode root, String field,
                                                                    Map<PhysicalTier, Double> placeholders) {
        JsonNode node = root.get(field);
        Map<PhysicalTier, Double> result = new EnumMap<>(PhysicalTier.class);
        for (PhysicalTier tier : PhysicalTier.values()) {
            JsonNode value = node == null ? null : node.get(tier.name());
            if (isTodo(value)) {
                double fallback = placeholders.get(tier);
                warnTodo(field, tier, fallback);
                result.put(tier, fallback);
            } else if (value != null && value.isNumber()) {
                result.put(tier, value.asDouble());
            } else {
                throw new SimConstantsFormatException(
                        field + "." + tier + " 값이 없거나 숫자/TODO가 아닙니다: " + value);
            }
        }
        return result;
    }

    private static boolean isTodo(JsonNode value) {
        return value != null && value.isTextual() && TODO_SENTINEL.equals(value.asText());
    }

    private static void warnTodo(String field, PhysicalTier tier, Object fallback) {
        String key = tier == null ? field : field + "." + tier;
        log.warn("sim-constants.yaml: {}이(가) TODO입니다. 임시 기본값 {}을(를) 사용합니다 "
                + "(bench/ 마이크로벤치마크 실측치로 교체 필요, 논문 숫자에 그대로 쓰지 말 것)", key, fallback);
    }

    private static Map<PhysicalTier, Long> tierLongMap(long ssd, long disk, long archive) {
        Map<PhysicalTier, Long> m = new EnumMap<>(PhysicalTier.class);
        m.put(PhysicalTier.SSD, ssd);
        m.put(PhysicalTier.DISK, disk);
        m.put(PhysicalTier.ARCHIVE, archive);
        return Map.copyOf(m);
    }

    private static Map<PhysicalTier, Double> tierDoubleMap(double ssd, double disk, double archive) {
        Map<PhysicalTier, Double> m = new EnumMap<>(PhysicalTier.class);
        m.put(PhysicalTier.SSD, ssd);
        m.put(PhysicalTier.DISK, disk);
        m.put(PhysicalTier.ARCHIVE, archive);
        return Map.copyOf(m);
    }
}
