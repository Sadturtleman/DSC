package edu.dsc.tiering.spi;

/**
 * ObservationSnapshot/PlacementDecision에 대한 의미(semantic) 검증.
 * <p>
 * Jackson 역직렬화만으로는 잡을 수 없는 v1.0 규약 위반(범위, 모드별 필드 조합)을
 * 명확한 메시지의 예외로 조기에 드러낸다. Placement 5종 외의 값은 각 DTO의
 * Jackson enum 역직렬화 단계에서 이미 실패하므로 여기서 다시 검사하지 않는다.
 */
public final class SpiValidator {

    private SpiValidator() {
    }

    public static void validate(ObservationSnapshot snapshot) {
        if (snapshot.observationMode() == ObservationMode.FSIMAGE
                && snapshot.events() != null
                && !snapshot.events().isEmpty()) {
            throw new SpiValidationException(
                    "fsimage 모드에서는 events가 비어있어야 합니다. 실제 이벤트 수: "
                            + snapshot.events().size());
        }
    }

    public static void validate(PlacementDecision decision) {
        if (decision.decisions() == null) {
            return;
        }
        for (Decision d : decision.decisions()) {
            double priority = d.priority();
            if (priority < 0.0 || priority > 1.0) {
                throw new SpiValidationException(
                        "priority는 0~1 범위여야 합니다: path=" + d.path() + ", priority=" + priority);
            }
        }
    }
}
