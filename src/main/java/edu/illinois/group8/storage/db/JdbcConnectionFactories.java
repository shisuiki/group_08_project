package edu.illinois.group8.storage.db;

import java.sql.DriverManager;
import java.util.Objects;

public final class JdbcConnectionFactories {
    private JdbcConnectionFactories() {
    }

    public static JdbcConnectionFactory fromDriverManager(String url, String user, String password) {
        Objects.requireNonNull(url, "url");
        return () -> {
            if (user == null || user.isBlank()) {
                return DriverManager.getConnection(url);
            }
            return DriverManager.getConnection(url, user, password);
        };
    }
}
