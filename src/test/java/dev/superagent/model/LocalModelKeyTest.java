package dev.superagent.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalModelKeyTest {
    @TempDir Path directory;

    @Test void readsLocalKeyWithBomAndKeepsEnvironmentPrecedence() throws Exception {
        Path config = directory.resolve(".env");
        Files.writeString(config, "\uFEFF# local settings\nDEEPSEEK_API_KEY = fixture-file\n");
        String endpoint = "https://api.deepseek.com";
        assertEquals("fixture-file", Config.resolveKey(Map.of(), config, "deepseek", endpoint));
        assertEquals("fixture-env", Config.resolveKey(
                Map.of("SUPERAGENT_API_KEY", "", "DEEPSEEK_API_KEY", "fixture-env"), config, "deepseek", endpoint));
        assertEquals("fixture-primary", Config.resolveKey(
                Map.of("SUPERAGENT_API_KEY", "fixture-primary", "DEEPSEEK_API_KEY", "fixture-env"),
                config, "deepseek", endpoint));
    }

    @Test void doesNotForwardSavedKeyToAnotherHostOrProvider() throws Exception {
        Path config = directory.resolve(".env");
        Files.writeString(config, "DEEPSEEK_API_KEY=fixture-file\n");
        assertEquals("", Config.resolveKey(Map.of(), config, "standard", "https://api.deepseek.com"));
        assertEquals("", Config.resolveKey(Map.of(), config, "deepseek", "https://api.deepseek.com.example.invalid"));
        assertEquals("", Config.resolveKey(Map.of(), config, "deepseek", "http://api.deepseek.com"));
        assertEquals("", Config.resolveKey(Map.of(), config, "deepseek", "https://api.deepseek.com:8443"));
    }
}
