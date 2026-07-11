package edu.dsc.tiering.spi;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Policy SPI DTO용으로 설정된 공유 {@link ObjectMapper}.
 * <p>
 * 골든 픽스처와 언어 간 검증의 기준이 JSON이므로, 여기서 정한 설정(snake_case 필드명은
 * 각 DTO의 {@code @JsonProperty}로 명시, 알 수 없는 필드는 실패)이 스키마의 실질적
 * 원천이다.
 */
public final class SpiObjectMapper {

    private static final ObjectMapper MAPPER = build();

    private SpiObjectMapper() {
    }

    public static ObjectMapper get() {
        return MAPPER;
    }

    private static ObjectMapper build() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        return mapper;
    }
}
