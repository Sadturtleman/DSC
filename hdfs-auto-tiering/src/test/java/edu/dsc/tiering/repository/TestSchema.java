package edu.dsc.tiering.repository;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 테스트 컨테이너에 공유 DDL(V001) 을 적용.
 * <p>
 * {@code db/migrations/V001__pending_jobs.sql} 은 pom.xml 의 testResources 설정으로
 * 테스트 classpath 의 {@code db/migrations/} 아래에 노출된다. PostgreSQL JDBC 드라이버는
 * 단일 {@link Statement#execute(String)} 호출로 다중 statement (PL/pgSQL {@code $$} 블록 포함)
 * 를 처리할 수 있으므로 별도 파서 없이 그대로 실행한다.
 */
final class TestSchema {

    private static final String SCRIPT_PATH = "db/migrations/V001__pending_jobs.sql";

    private TestSchema() {}

    static void apply(DataSource ds) throws Exception {
        String script;
        try (InputStream in = TestSchema.class.getClassLoader().getResourceAsStream(SCRIPT_PATH)) {
            if (in == null) {
                throw new IllegalStateException(
                        SCRIPT_PATH + " not on test classpath — check pom.xml <testResources>");
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(script);
        }
    }
}
