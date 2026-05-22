package edu.dsc.tiering.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .registerModule(new ParameterNamesModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);

    private ConfigLoader() {}

    public static AppConfig fromClasspath(String resource) throws IOException {
        try (InputStream in = Thread.currentThread()
                .getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Resource not found on classpath: " + resource);
            }
            return YAML.readValue(in, AppConfig.class);
        }
    }

    public static AppConfig fromFile(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return YAML.readValue(in, AppConfig.class);
        }
    }
}
