package dev.example;

public final class UsernameValidator {
    public boolean isValid(String username) {
        return !username.isBlank();
    }
}
