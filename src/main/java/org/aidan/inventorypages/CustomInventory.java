package org.aidan.inventorypages;

import org.aidan.inventorypages.InventoryPages;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;

public class CustomInventory {
    private InventoryPages plugin;
    private Player player;
    private ItemStack prevItem, nextItem, noPageItem;
    private Integer page = 0, maxPage = 1, prevPos, nextPos;
    private Boolean hasUsedCreative = false;
    private HashMap<Integer, ArrayList<ItemStack>> items = new HashMap<Integer, ArrayList<ItemStack>>();
    private File playerFile;
    private FileConfiguration playerData;
    private ArrayList<ItemStack> creativeItems = new ArrayList<ItemStack>(27);


    // ======================================
    // Constructor
    // ======================================
    CustomInventory(InventoryPages plugin, Player player, int maxPage, ItemStack prevItem, Integer prevPos, ItemStack nextItem, Integer nextPos, ItemStack noPageItem, String itemsMerged, String itemsDropped) {
        this.plugin = plugin;
        this.player = player;
        this.maxPage = maxPage;
        this.prevItem = prevItem;
        this.prevPos = prevPos;
        this.nextItem = nextItem;
        this.nextPos = nextPos;
        this.noPageItem = noPageItem;

        String playerUUID = this.player.getUniqueId().toString();
        this.playerFile = new File(this.plugin.getDataFolder() + "/inventories/" + playerUUID.substring(0, 1) + "/" + playerUUID + ".yml");
        this.playerData = YamlConfiguration.loadConfiguration(this.playerFile);

        // create pages
        for (int i = 0; i < maxPage + 1; i++) {
            if (!pageExists(i)) {
                createPage(i);
            }
        }

        // initialize creative inventory
        for (int i = 0; i < 27; i++) {
            creativeItems.add(null);
        }

        loadInventory();
    }
    public void calculateMaxPagesBasedOnPermissions() {
        int maxPage = 1;
        for (int i = 2; i <= 100; i++) {
            if (player.hasPermission("inventorypages.pages." + i)) {
                maxPage = i;
            }
        }
        this.maxPage = maxPage;
        // Handle any excess items if the max page count is reduced
        handleExcessPageItems();
    }
    private void handleExcessPageItems() {
        // Implement logic to handle items that no longer fit due to reduced page count
        // This might involve moving them to a 'pickup stash' or dropping them
    }

    private void loadInventoryFromFile() {

        if (playerFile.exists()) {
            for (int i = 0; i <= maxPage; i++) {
                ArrayList<ItemStack> pageItems = new ArrayList<>(25);
                for (int j = 0; j < 25; j++) {
                    ItemStack item = null;
                    if (playerData.contains("items.main." + i + "." + j)) {
                        try {
                            item = org.aidan.inventorypages.InventoryStringDeSerializer.stacksFromBase64(playerData.getString("items.main." + i + "." + j))[0];
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    pageItems.add(item);
                }
                this.items.put(i, pageItems);
            }

            // Load creative items
            if (playerData.contains("items.creative.0")) {
                ArrayList<ItemStack> creativeItems = new ArrayList<>(27);
                for (int i = 0; i < 27; i++) {
                    ItemStack item = null;
                    if (playerData.contains("items.creative.0." + i)) {
                        try {
                            item = org.aidan.inventorypages.InventoryStringDeSerializer.stacksFromBase64(playerData.getString("items.creative.0." + i))[0];
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    creativeItems.add(item);
                }
                this.setCreativeItems(creativeItems);
            }

            // Load current page
            if (playerData.contains("page")) {
                this.setPage(playerData.getInt("page"));
            }

            // Add any other data loading here as needed...
        }
    }
    // ======================================
    // Save Inventory to MySQL
    // ======================================
    public void saveToMySQL() {
        String playerUUID = this.player.getUniqueId().toString();
        org.aidan.inventorypages.MySQLConnector mySQL = this.plugin.getMySQLConnector();

        for (Map.Entry<Integer, ArrayList<ItemStack>> entry : this.items.entrySet()) {
            int page = entry.getKey();
            ArrayList<ItemStack> items = entry.getValue();

            try {
                String encodedItems = org.aidan.inventorypages.InventoryStringDeSerializer.toBase64(items.toArray(new ItemStack[0]));
                String query = "REPLACE INTO " + this.plugin.getConfig().getString("mysql.table_prefix")
                        + "inventories (player_uuid, page, item_data) VALUES (?, ?, ?);";
                PreparedStatement statement = mySQL.getConnection().prepareStatement(query);
                statement.setString(1, playerUUID);
                statement.setInt(2, page);
                statement.setString(3, encodedItems);
                statement.executeUpdate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void loadFromMySQL() {
        String playerUUID = this.player.getUniqueId().toString();
        org.aidan.inventorypages.MySQLConnector mySQL = this.plugin.getMySQLConnector();

        try {
            String query = "SELECT page, item_data FROM " + this.plugin.getConfig().getString("mysql.table_prefix")
                    + "inventories WHERE player_uuid = ?;";
            PreparedStatement statement = mySQL.getConnection().prepareStatement(query);
            statement.setString(1, playerUUID);
            ResultSet results = statement.executeQuery();

            while (results.next()) {
                int page = results.getInt("page");
                String itemData = results.getString("item_data");
                ItemStack[] itemsArray = org.aidan.inventorypages.InventoryStringDeSerializer.stacksFromBase64(itemData);
                ArrayList<ItemStack> itemsList = new ArrayList<>(Arrays.asList(itemsArray));
                this.items.put(page, itemsList);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadInventory() {
        if (plugin.getConfig().getBoolean("mysql.enabled")) {
            loadFromMySQL();  // Your new MySQL loading method
        } else {
            loadInventoryFromFile();
        }
    }

    // ======================================
    // Save Current Page
    // ======================================
    void saveCurrentPage() {
        if (player.getGameMode() != GameMode.CREATIVE) {
            ArrayList<ItemStack> pageItems = new ArrayList<ItemStack>(25);
            for (int i = 0; i < 27; i++) {
                if (i != prevPos && i != nextPos) {
                    pageItems.add(this.player.getInventory().getItem(i + 9));
                }
            }
            this.items.put(this.page, pageItems);
        } else {
            for (int i = 0; i < 27; i++) {
                creativeItems.set(i, this.player.getInventory().getItem(i + 9));
            }
        }
    }

    // ======================================
    // Clear Page
    // ======================================
    void clearPage(GameMode gm) {
        clearPage(this.page, gm);
    }

    void clearPage(int page, GameMode gm) {
        if (gm != GameMode.CREATIVE) {
            ArrayList<ItemStack> pageItems = new ArrayList<ItemStack>(25);
            for (int i = 0; i < 25; i++) {
                pageItems.add(null);
            }
            this.items.put(page, pageItems);
        } else {
            for (int i = 0; i < 27; i++) {
                creativeItems.set(i, null);
            }
        }
    }

    // ======================================
    // Clear All Pages
    // ======================================
    void clearAllPages(GameMode gm) {
        if (gm != GameMode.CREATIVE) {
            for (int i = 0; i < this.maxPage + 1; i++) {
                clearPage(i, gm);
            }
        } else {
            clearPage(gm);
        }
    }

    // ======================================
    // Drop Page
    // ======================================
    void dropPage(GameMode gm) {
        dropPage(this.page, gm);
    }

    void dropPage(int page, GameMode gm) {
        if (gm != GameMode.CREATIVE) {
            for (int i = 0; i < 25; i++) {
                ItemStack item = this.items.get(page).get(i);
                if (item != null) {
                    this.player.getWorld().dropItemNaturally(this.player.getLocation(), item);
                    this.items.get(page).set(i, null);
                }
            }
        } else {
            for (int i = 0; i < 27; i++) {
                ItemStack item = this.creativeItems.get(i);
                if (item != null) {
                    this.player.getWorld().dropItemNaturally(this.player.getLocation(), item);
                    this.creativeItems.set(i, null);
                }
            }
        }
    }

    // ======================================
    // Drop All Pages
    // ======================================
    void dropAllPages(GameMode gm) {
        if (gm != GameMode.CREATIVE) {
            for (int i = 0; i < this.maxPage + 1; i++) {
                dropPage(i, gm);
            }
        } else {
            dropPage(gm);
        }
    }

    // ======================================
    // Show Page
    // ======================================
    void showPage() {
        this.showPage(this.page);
    }

    void showPage(Integer page) {
        showPage(page, GameMode.SURVIVAL);
    }

    void showPage(GameMode gm) {
        showPage(this.page, gm);
    }

    void showPage(Integer page, GameMode gm) {
        if (page > maxPage) {
            this.page = maxPage;
        } else {
            this.page = page;
        }
        //player.sendMessage("GameMode: " + gm);
        if (gm != GameMode.CREATIVE) {
            Boolean foundPrev = false;
            Boolean foundNext = false;
            for (int i = 0; i < 27; i++) {
                int j = i;
                if (i == prevPos) {
                    if (this.page == 0) {
                        this.player.getInventory().setItem(i + 9, addPageNums(noPageItem));
                    } else {
                        this.player.getInventory().setItem(i + 9, addPageNums(prevItem));
                    }
                    foundPrev = true;
                } else if (i == nextPos) {
                    if (this.page == maxPage) {
                        this.player.getInventory().setItem(i + 9, addPageNums(noPageItem));
                    } else {
                        this.player.getInventory().setItem(i + 9, addPageNums(nextItem));
                    }
                    foundNext = true;
                } else {
                    if (foundPrev) {
                        j--;
                    }
                    if (foundNext) {
                        j--;
                    }
                    this.player.getInventory().setItem(i + 9, this.items.get(this.page).get(j));
                }
            }
            //player.sendMessage("Showing Page: " + this.page);
        } else {
            this.hasUsedCreative = true;
            for (int i = 0; i < 27; i++) {
                this.player.getInventory().setItem(i + 9, this.creativeItems.get(i));
            }
        }
    }

    // ======================================
    // Add Page Numbers
    // ======================================
    ItemStack addPageNums(ItemStack item) {
        ItemStack modItem = new ItemStack(item);
        ItemMeta itemMeta = modItem.getItemMeta();
        List<String> itemLore = itemMeta.getLore();
        for (int j = 0; j < itemLore.size(); j++) {
            Integer currentPageUser = page + 1;
            Integer maxPageUser = maxPage + 1;
            itemLore.set(j, itemLore.get(j).replace("{CURRENT}", currentPageUser.toString()).replace("{MAX}", maxPageUser.toString()));
        }
        itemMeta.setLore(itemLore);
        modItem.setItemMeta(itemMeta);
        return modItem;
    }

    // ======================================
    // Previous Page
    // ======================================
    void prevPage() {
        if (this.page > 0) {
            this.saveCurrentPage();
            this.page = this.page - 1;
            this.showPage();
            this.saveCurrentPage();
        }
    }

    // ======================================
    // Next Page
    // ======================================
    void nextPage() {
        if (this.page < maxPage) {
            this.saveCurrentPage();
            this.page = this.page + 1;
            this.showPage();
            this.saveCurrentPage();
        }
    }

    // ======================================
    // Page Exists
    // ======================================
    Boolean pageExists(Integer page) {
        if (items.containsKey(page)) {
            return true;
        }
        return false;
    }

    // ======================================
    // Create Page
    // ======================================
    void createPage(Integer page) {
        ArrayList<ItemStack> pageItems = new ArrayList<ItemStack>(25);
        for (int i = 0; i < 25; i++) {
            pageItems.add(null);
        }
        this.items.put(page, pageItems);
    }

    // ======================================
    // Get/Set Items
    // ======================================
    HashMap<Integer, ArrayList<ItemStack>> getItems() {
        return this.items;
    }

    void setItems(HashMap<Integer, ArrayList<ItemStack>> items) {
        this.items = items;
    }

    // ======================================
    // Get/Set Creative Items
    // ======================================
    ArrayList<ItemStack> getCreativeItems() {
        return this.creativeItems;
    }

    void setCreativeItems(ArrayList<ItemStack> creativeItems) {
        this.creativeItems = creativeItems;
    }

    // ======================================
    // Get/Set Current Page
    // ======================================
    Integer getPage() {
        return this.page;
    }

    void setPage(Integer page) {
        this.page = page;
    }

    // ======================================
    // Get/Set Has Used Creative Boolean
    // ======================================
    Boolean hasUsedCreative() {
        return this.hasUsedCreative;
    }

    void setUsedCreative(Boolean hasUsedCreative) {
        this.hasUsedCreative = hasUsedCreative;
    }

    // ======================================
    // Next Free Space
    // ======================================
    SimpleEntry<Integer, Integer> nextFreeSpace() {
        for (Integer i = 0; i < maxPage + 1; i++) {
            for (Integer j = 0; j < 25; j++) {
                if (items.get(i).get(j) == null) {
                    SimpleEntry<Integer, Integer> pageAndPos = new AbstractMap.SimpleEntry<Integer, Integer>(i, j);
                    return pageAndPos;
                }
            }
        }
        return null;
    }

    // ======================================
    // Next Creative Free Space
    // ======================================
    int nextCreativeFreeSpace() {
        for (Integer i = 0; i < 27; i++) {
            if (creativeItems.get(i) == null) {
                return i;
            }
        }
        return -1;
    }

    // ======================================
    // Store/Drop Item
    // ======================================
    // returns true if dropped
    Boolean storeOrDropItem(ItemStack item, GameMode gm) {
        if (gm != GameMode.CREATIVE) {
            SimpleEntry<Integer, Integer> nextFreeSpace = nextFreeSpace();
            if (nextFreeSpace != null) {
                this.items.get(nextFreeSpace.getKey()).set(nextFreeSpace.getValue(), item);
                return false;
            } else {
                this.player.getWorld().dropItem(player.getLocation(), item);
                return true;
            }
        } else {
            int nextFreeSpace = nextCreativeFreeSpace();
            if (nextFreeSpace != -1) {
                this.creativeItems.set(nextFreeSpace, item);
                return false;
            } else {
                this.player.getWorld().dropItem(player.getLocation(), item);
                return true;
            }
        }

    }
}