package org.aidan.inventorypages;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class MySQLConnector {
    private org.aidan.inventorypages.InventoryPages plugin;
    private Connection connection;
    private HashMap<UUID, List<ItemStack>> pickupStash = new HashMap<>();
    public MySQLConnector(org.aidan.inventorypages.InventoryPages plugin) {
        this.plugin = plugin;
    }
    public Connection getConnection() {
        return this.connection;
    }

    public void connect() {
        if (plugin.getConfig().getBoolean("mysql.enabled")) {
            String host = plugin.getConfig().getString("mysql.host");
            int port = plugin.getConfig().getInt("mysql.port");
            String database = plugin.getConfig().getString("mysql.database");
            String username = plugin.getConfig().getString("mysql.username");
            String password = plugin.getConfig().getString("mysql.password");

            try {
                synchronized (this) {
                    if (connection != null && !connection.isClosed()) {
                        return;
                    }
                    Class.forName("com.mysql.jdbc.Driver");
                    connection = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + database, username, password);
                    plugin.getLogger().info("Connected to MySQL database.");
                }
            } catch (SQLException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    public void disconnect() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("Disconnected from MySQL database.");
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    public void update(String query) {
        try {
            PreparedStatement statement = connection.prepareStatement(query);
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void checkAndCreateTable() {
        try {
            Statement statement = this.getConnection().createStatement();

            // SQL statement to check if the table exists
            String checkTable = "SHOW TABLES LIKE 'invpages_inventories';";
            ResultSet rs = statement.executeQuery(checkTable);

            // If ResultSet is empty, table does not exist
            if (!rs.next()) {
                // SQL to create table
                String createTable = "CREATE TABLE invpages_inventories (" +
                        "player_uuid VARCHAR(36), " +
                        "page INT, " +
                        "item_data TEXT, " +
                        "PRIMARY KEY (player_uuid, page));";

                statement.executeUpdate(createTable);
                System.out.println("Created table 'invpages_inventories'");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
