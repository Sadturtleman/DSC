package edu.dsc.tiering.spi;

/** Policy SPI DTO가 v1.0 계약(규약)을 어길 때 던지는 예외. */
public class SpiValidationException extends RuntimeException {

    public SpiValidationException(String message) {
        super(message);
    }
}
