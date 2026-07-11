package edu.dsc.tiering.spi;

/**
 * HDFS storage policy로 표현 가능한 5종 정규형.
 * <p>
 * 논리 티어({@code edu.dsc.tiering.model.Tier})와 이름이 겹치는 상수(HOT, WARM)가 있으나
 * 별개 개념이다. 신규 SPI에서는 항상 이 Placement 5종만 사용한다.
 */
public enum Placement {

    /** [SSD, SSD, SSD] */
    ALL_SSD,

    /** [SSD, DISK, DISK] — P1 부분 티어링의 핵심 */
    ONE_SSD,

    /** [DISK, DISK, DISK] (HDFS 기본) */
    HOT,

    /** [DISK, ARCHIVE, ARCHIVE] */
    WARM,

    /** [ARCHIVE, ARCHIVE, ARCHIVE] */
    COLD;

    /** 이 정규형에 대응하는 HDFS storage policy 이름. */
    public String hdfsPolicyName() {
        return name();
    }
}
