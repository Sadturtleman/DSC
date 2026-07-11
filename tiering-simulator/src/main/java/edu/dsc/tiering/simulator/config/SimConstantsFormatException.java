package edu.dsc.tiering.simulator.config;

/** sim-constants.yaml이 기대한 키/타입(숫자 또는 TODO 문자열)을 갖추지 못했을 때. */
public class SimConstantsFormatException extends RuntimeException {
    public SimConstantsFormatException(String message) {
        super(message);
    }
}
