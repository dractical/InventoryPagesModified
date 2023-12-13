package org.aidan.inventorypages;

import org.aidan.inventorypages.InventoryPages;
import org.apache.commons.dbcp2.BasicDataSource;

import java.sql.*;
import org.bukkit.configuration.file.FileConfiguration;

public class DatabaseManager {

    private BasicDataSource dataSource;
    private FileConfiguration config;

    public DatabaseManager(InventoryPages plugin) {
        // Initialize the config with the plugin's configuration
        this.config = plugin.getConfig();

        // Now you can use the config object
        String host = config.getString("mysql.host");
        int port = config.getInt("mysql.port");
        String database = config.getString("mysql.database");
        String username = config.getString("mysql.username");
        String password = config.getString("mysql.password");

        BasicDataSource ds = new BasicDataSource();
        ds.setUrl("jdbc:mysql://" + host + ":" + port + "/" + database);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMinIdle(5);
        ds.setMaxIdle(10);
        ds.setMaxOpenPreparedStatements(100);

        this.dataSource = ds;
    }
    public void initializeDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            // Check and create the pickup_stash table
            String pickupStashTable = config.getString("mysql.table_prefix") + "pickupstash";
            if (!tableExists(conn, pickupStashTable)) {
                createPickupStashTable(conn, pickupStashTable);
            }

            // Check and create any other tables you might need
        } catch (SQLException e) {
            // Log and handle the exception
            e.printStackTrace();
        }
    }
    private void createPickupStashTable(Connection conn, String tableName) {
        String createTableSQL = "CREATE TABLE " + tableName + " (" +
                "player_uuid VARCHAR(36) NOT NULL, " + // Assuming player_uuid is a UUID
                "item_data TEXT NOT NULL, " +
                "PRIMARY KEY (player_uuid)" + // Removed quantity as it's not used in your code
                ");";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet resultSet = meta.getTables(null, null, tableName, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }
}
