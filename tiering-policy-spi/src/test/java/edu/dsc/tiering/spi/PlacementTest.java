package edu.dsc.tiering.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacementTest {

    @Test
    void hdfsPolicyNameMatchesEnumConstantForAllFiveValues() {
        for (Placement p : Placement.values()) {
            assertEquals(p.name(), p.hdfsPolicyName());
        }
    }

    @Test
    void exactlyFiveNormalFormsExist() {
        assertEquals(5, Placement.values().length);
    }
}
