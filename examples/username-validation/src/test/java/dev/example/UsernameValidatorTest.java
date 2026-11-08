package dev.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsernameValidatorTest {
    private final UsernameValidator validator = new UsernameValidator();

    @Test
    void acceptsTypicalUsername() {
        assertTrue(validator.isValid("Alice_01"));
    }

    @Test
    void rejectsBlankUsername() {
        assertFalse(validator.isValid(" "));
    }
}
