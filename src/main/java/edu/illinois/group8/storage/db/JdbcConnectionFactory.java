package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface JdbcConnectionFactory {
    /**
     * Opens a fresh connection owned by the caller. JdbcAcceptedEventStore manages
     * transaction boundaries and closes the returned connection after each batch.
     */
    Connection openConnection() throws SQLException;
}
