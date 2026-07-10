package edu.dsc.tiering.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModulePlaceholderTest {

    @Test
    void reportsModuleName() {
        assertEquals("tiering-policy-spi", ModulePlaceholder.moduleName());
    }
}
