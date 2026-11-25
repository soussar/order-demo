package com.example.orders.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

class QualityGateExperimentTest {
    @Test
    void failsIntentionally() {
        fail("Intentional failure to validate the CI quality gate");
    }
}
