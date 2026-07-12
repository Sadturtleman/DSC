package edu.dsc.tiering.simulator.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 레포 루트를 찾는다. {@code mvn test}는 작업 디렉터리를 모듈 basedir(tiering-simulator/)로
 * 두고, {@code exec:java}도 마찬가지다 — 두 경우 모두 "설치 경로·환경변수에 의존하지 않는다"
 * (CLAUDE.md) 원칙을 지키기 위해, 현재 작업 디렉터리에서 위로 올라가며 루트 pom.xml
 * (artifactId=dsc-parent)을 찾는 방식으로 경로를 해석한다.
 */
final class RepoPaths {

    private RepoPaths() {
    }

    static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom) && looksLikeRootPom(pom)) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "dsc-parent pom.xml을 찾지 못했습니다 (탐색 시작 위치: " + Path.of("").toAbsolutePath() + ")");
    }

    private static boolean looksLikeRootPom(Path pom) {
        try {
            return Files.readString(pom).contains("<artifactId>dsc-parent</artifactId>");
        } catch (IOException e) {
            return false;
        }
    }
}
