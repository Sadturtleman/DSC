package edu.dsc.tiering.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModulePlaceholderTest {

    @Test
    void reportsModuleName() {
        assertEquals("tiering-simulator", ModulePlaceholder.moduleName());
    }
}
