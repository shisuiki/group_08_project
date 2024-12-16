// package edu.illinois.group8.historicalDataFetcher;

// import java.sql.Connection;
// import java.sql.DriverManager;
// import java.sql.SQLException;

// import io.github.cdimascio.dotenv.Dotenv;

// public class DatabaseConnectionTest {
//     // Redshift connection details
//     static Dotenv dotenv = Dotenv.load();

    
//     private static final String REDSHIFT_URL = "jdbc:redshift://kalshi-cluster.cqnzqxki7plp.us-east-2.redshift.amazonaws.com:5439/dev";
//     private static final String DB_USER = dotenv.get("DB_USER");
//     private static final String DB_PASSWORD = dotenv.get("DB_PASSWORD");

//     public static void main(String[] args) {
//         Connection connection = null;
//         try {
//             // Load JDBC driver
//             Class.forName("com.amazon.redshift.jdbc42.Driver");

//             System.out.println("Attempting to connect to the database...");
//             // Establish connection
//             connection = DriverManager.getConnection(REDSHIFT_URL, DB_USER, DB_PASSWORD);

//             if (connection != null) {
//                 System.out.println("Connection successful!");
//             } else {
//                 System.out.println("Connection failed!");
//             }
//         } catch (ClassNotFoundException e) {
//             System.err.println("JDBC Driver not found: " + e.getMessage());
//         } catch (SQLException e) {
//             System.err.println("Database connection error: " + e.getMessage());
//         } finally {
//             try {
//                 if (connection != null) {
//                     connection.close();
//                     System.out.println("Connection closed.");
//                 }
//             } catch (SQLException e) {
//                 System.err.println("Error closing the connection: " + e.getMessage());
//             }
//         }
//     }
// }


package edu.illinois.group8.historicalDataFetcher;

import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.RedshiftDataException;

import io.github.cdimascio.dotenv.Dotenv;


public class RedshiftDataApiExample {
    static Dotenv dotenv = Dotenv.load();
    // Cluster and database details
    private static final String CLUSTER_IDENTIFIER = "kalshi-cluster";
    private static final String DATABASE_NAME = "dev";
    private static final String DB_USER = dotenv.get("DB_USER"); // Replace with your username
    private static final String SQL_STATEMENT = "SELECT * FROM Trades;"; // Replace with your desired SQL query
    private static final Region REGION = Region.US_EAST_2; // Update the region if necessary

    public static void main(String[] args) {
        // Create the Redshift Data API client
        try (RedshiftDataClient redshiftDataClient = RedshiftDataClient.builder()
                .region(REGION)
                .credentialsProvider(ProfileCredentialsProvider.create()) // Assumes AWS credentials profile is configured
                .build()) {

            System.out.println("Executing SQL statement via Redshift Data API...");

            // Execute the SQL statement
            ExecuteStatementRequest executeRequest = ExecuteStatementRequest.builder()
                    .clusterIdentifier(CLUSTER_IDENTIFIER)
                    .database(DATABASE_NAME)
                    .dbUser(DB_USER)
                    .sql(SQL_STATEMENT)
                    .build();

            ExecuteStatementResponse executeResponse = redshiftDataClient.executeStatement(executeRequest);
            String statementId = executeResponse.id();

            System.out.println("SQL statement submitted. Statement ID: " + statementId);

            // Poll for query completion
            boolean isComplete = false;
            while (!isComplete) {
                DescribeStatementRequest describeRequest = DescribeStatementRequest.builder()
                        .id(statementId)
                        .build();

                DescribeStatementResponse describeResponse = redshiftDataClient.describeStatement(describeRequest);
                String status = describeResponse.statusAsString();

                System.out.println("Query status: " + status);

                switch (status) {
                    case "FINISHED":
                        isComplete = true;
                        System.out.println("Query completed successfully!");
                        break;
                    case "FAILED":
                        throw new RuntimeException("Query failed: " + describeResponse.error());
                    default:
                        Thread.sleep(1000); // Wait for 1 second before polling again
                }
            }
        } catch (RedshiftDataException e) {
            System.err.println("Redshift Data API error: " + e.getMessage());
        } catch (InterruptedException e) {
            System.err.println("Polling interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
