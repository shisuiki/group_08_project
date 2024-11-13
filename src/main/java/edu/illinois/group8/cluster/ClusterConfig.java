package edu.illinois.group8.cluster;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.Map;

public class ClusterConfig {
    private Map<String, Object> config;

    public ClusterConfig(String configFile) {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(configFile)) {
            config = yaml.load(in);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load configuration", e);
        }
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    // Add methods to retrieve specific configurations as needed
}

