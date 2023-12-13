package org.aidan.inventorypages;

import org.aidan.inventorypages.InventoryStringDeSerializer;
import org.aidan.inventorypages.MetricsLite;
import org.aidan.inventorypages.MySQLConnector;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.Map.Entry;

public class InventoryPages extends JavaPlugin implements Listener {
    File crashedFile = new File(getDataFolder() + "/backups/crashed.yml");
    FileConfiguration crashedData = YamlConfiguration.loadConfiguration(crashedFile);

    private HashMap<String, org.aidan.inventorypages.CustomInventory> playerInvs = new HashMap<String, org.aidan.inventorypages.CustomInventory>();
    org.aidan.inventorypages.ColorConverter colorConv = new org.aidan.inventorypages.ColorConverter(this);

    private ItemStack nextItem, prevItem, noPageItem;
    private Integer prevPos, nextPos;
    private List<String> clearCommands;
    private String noPermission, clear, clearAll, itemsMerged, itemsDropped;
    private Boolean logSavesEnabled;
    private String logSavesMessage;
    private MySQLConnector mySQLConnector;
    private HashMap<UUID, List<ItemStack>> pickupStash;
    private org.aidan.inventorypages.DatabaseManager databaseManager;

    // ======================================
    // Enable
    // ======================================
    public void onEnable() {
        saveDefaultConfig();

        Bukkit.getServer().getLogger().info("[InventoryPages] Registering events.");
        Bukkit.getServer().getPluginManager().registerEvents(this, this);

        if (getConfig().getBoolean("mysql.enabled")) {
            // MySQL Connections
            this.mySQLConnector = new MySQLConnector(this);
            this.mySQLConnector.connect();
            this.mySQLConnector.checkAndCreateTable(); // Check and create table if necessary
            // Initialize and setup database
            this.databaseManager = new org.aidan.inventorypages.DatabaseManager(this);
            this.databaseManager.initializeDatabase();
        }


        if (getConfig().getBoolean("metrics")) {
            try {
                MetricsLite metrics = new MetricsLite(this);
                metrics.start();
                Bukkit.getServer().getLogger().info("[InventoryPages] Metrics enabled!");
            } catch (IOException e) {
                Bukkit.getServer().getLogger().warning("[InventoryPages] Failed to start metrics.");
            }
        } else {
            Bukkit.getServer().getLogger().info("[InventoryPages] Metrics disabled.");
        }

        // initialize next, prev items
        Bukkit.getServer().getLogger().info("[InventoryPages] Setting up items.");
        initItems();

        // initialize commands
        Bukkit.getServer().getLogger().info("[InventoryPages] Setting up commands.");
        initCommands();

        // initialize language
        Bukkit.getServer().getLogger().info("[InventoryPages] Setting up language.");
        initLanguage();

        // load all online players into hashmap
        Bukkit.getServer().getLogger().info("[InventoryPages] Setting up inventories.");
        for (Player player : Bukkit.getServer().getOnlinePlayers()) {
            try {
                loadInvFromFileIntoHashMap(player);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (getConfig().getBoolean("saving.enabled")) {
            Bukkit.getServer().getLogger().info("[InventoryPages] Setting up inventory saving.");
            startSaving();
        }
        pickupStash = new HashMap<>();
        startPeriodicPermissionCheck();
        Bukkit.getServer().getLogger().info("[InventoryPages] Plugin enabled!");
    }

    // ======================================
    // Disable
    // ======================================
    public void onDisable() {
        if (mySQLConnector != null) {
            mySQLConnector.disconnect();
        }
        for (Player player : Bukkit.getServer().getOnlinePlayers()) {
            String playerUUID = player.getUniqueId().toString();
            if (playerInvs.containsKey(playerUUID)) {
                // update inventories to hashmap and save to file
                updateInvToHashMap(player);
                saveInvFromHashMapToFile(player);
                clearAndRemoveCrashedPlayer(player);
            }
        }
        Bukkit.getServer().getLogger().info("[InventoryPages] Plugin disabled.");
    }

    // ======================================
    // Join shit
    // ======================================
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            org.aidan.inventorypages.CustomInventory inventory = playerInvs.get(playerUUID);
            inventory.calculateMaxPagesBasedOnPermissions();
        }
    }
    // ======================================
    // Permission checks & inventory page updates
    // ======================================
    private void startPeriodicPermissionCheck() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                String playerUUID = player.getUniqueId().toString();
                if (playerInvs.containsKey(playerUUID)) {
                    org.aidan.inventorypages.CustomInventory inventory = playerInvs.get(playerUUID);
                    inventory.calculateMaxPagesBasedOnPermissions();
                }
            }
        }, 0L, 20L * 60 * 1); // Checks every 1 minute
    }
    // ======================================
    // MySQL stuff
    // ======================================
    public MySQLConnector getMySQLConnector() {
        return mySQLConnector;
    }

    // ======================================
    // Update and Save All Inventories to Files
    // ======================================
    public void updateAndSaveAllInventoriesToFiles() {
        if (!Bukkit.getServer().getOnlinePlayers().isEmpty()) {
            for (Player player : Bukkit.getServer().getOnlinePlayers()) {
                String playerUUID = player.getUniqueId().toString();
                if (playerInvs.containsKey(playerUUID)) {
                    updateInvToHashMap(player);
                    saveInventory(player); // Replace existing call
                }
            }
            if (logSavesEnabled) {
                Bukkit.getServer().getLogger().info(logSavesMessage);
            }
        }
    }

    // ======================================
    // Initialize Next Item
    // ======================================
    public void initItems() {

        prevItem = new ItemStack(Material.getMaterial(getConfig().getString("items.prev.id")));
        ItemMeta prevItemMeta = prevItem.getItemMeta();
        prevItemMeta.setDisplayName(colorConv.convertConfig("items.prev.name"));
        prevItemMeta.setLore(colorConv.convertConfigList("items.prev.lore"));
        prevItem.setItemMeta(prevItemMeta);

        prevPos = getConfig().getInt("items.prev.position");

        nextItem = new ItemStack(Material.getMaterial(getConfig().getString("items.next.id")));
        ItemMeta nextItemMeta = nextItem.getItemMeta();
        nextItemMeta.setDisplayName(colorConv.convertConfig("items.next.name"));
        nextItemMeta.setLore(colorConv.convertConfigList("items.next.lore"));
        nextItem.setItemMeta(nextItemMeta);

        nextPos = getConfig().getInt("items.next.position");

        noPageItem = new ItemStack(Material.getMaterial(getConfig().getString("items.noPage.id")));
        ItemMeta noPageItemMeta = noPageItem.getItemMeta();
        noPageItemMeta.setDisplayName(colorConv.convertConfig("items.noPage.name"));
        noPageItemMeta.setLore(colorConv.convertConfigList("items.noPage.lore"));
        noPageItem.setItemMeta(noPageItemMeta);
    }
    public void initCommands() {
        clearCommands = getConfig().getStringList("commands.clear.aliases");
        this.getCommand("pickupstash").setExecutor(this);
        this.getCommand("fillstash").setExecutor(this);
    }
    public void initLanguage() {
        noPermission = colorConv.convertConfig("language.noPermission");

        clear = colorConv.convertConfig("language.clear");
        clearAll = colorConv.convertConfig("language.clearAll");

        itemsMerged = colorConv.convertConfig("language.itemsMerged");
        itemsDropped = colorConv.convertConfig("language.itemsDropped");

        logSavesEnabled = getConfig().getBoolean("logging.saves.enabled");
        logSavesMessage = "[InventoryPages] " + getConfig().getString("logging.saves.message");
    }

    // ======================================
    // PickupStash
    // ======================================
    private List<ItemStack> getPickupStash(String playerUUID) {
        try {
            if (getConfig().getBoolean("mysql.enabled")) {
                return loadStashFromMySQL(playerUUID);
            } else {
                return loadStashFromFile(playerUUID);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
    private void setPickupStash(String playerUUID, List<ItemStack> stashItems) {
        try {
            if (getConfig().getBoolean("mysql.enabled")) {
                saveStashToMySQL(playerUUID, stashItems);
            } else {
                saveStashToFile(playerUUID, stashItems);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private void saveStashToFile(String playerUUID, List<ItemStack> stashItems) throws IOException {
        File stashFile = new File(getDataFolder() + "/stashes/" + playerUUID + ".yml");
        FileConfiguration stashConfig = YamlConfiguration.loadConfiguration(stashFile);

        stashConfig.set("stash", stashItems);
        stashConfig.save(stashFile);
    }
    private List<ItemStack> loadStashFromFile(String playerUUID) throws IOException {
        File stashFile = new File(getDataFolder() + "/stashes/" + playerUUID + ".yml");
        if (!stashFile.exists()) {
            return new ArrayList<>();
        }

        FileConfiguration stashConfig = YamlConfiguration.loadConfiguration(stashFile);
        return (List<ItemStack>) stashConfig.getList("stash");
    }
    private void saveStashToMySQL(String playerUUID, List<ItemStack> stashItems) {
        try {
            String encodedItems = InventoryStringDeSerializer.toBase64(stashItems.toArray(new ItemStack[0]));
            String tableName = this.getConfig().getString("mysql.table_prefix") + "pickupstash"; // Use the correct table name
            String query = "REPLACE INTO " + tableName + " (player_uuid, item_data) VALUES (?, ?);";

            try (PreparedStatement statement = getMySQLConnector().getConnection().prepareStatement(query)) {
                statement.setString(1, playerUUID);
                statement.setString(2, encodedItems);
                statement.executeUpdate();
            }
        } catch (Exception e) {
            this.getLogger().severe("An error occurred while handling the pickup stash for player UUID: " + playerUUID);
            e.printStackTrace();
        }
    }

    private List<ItemStack> loadStashFromMySQL(String playerUUID) {
        List<ItemStack> itemsList = new ArrayList<>();
        try {
            String tableName = this.getConfig().getString("mysql.table_prefix") + "pickupstash";
            String query = "SELECT item_data FROM " + tableName + " WHERE player_uuid = ?;";

            try (PreparedStatement statement = getMySQLConnector().getConnection().prepareStatement(query)) {
                statement.setString(1, playerUUID);
                ResultSet results = statement.executeQuery();

                if (results.next()) {
                    String itemData = results.getString("item_data");
                    ItemStack[] itemsArray = InventoryStringDeSerializer.stacksFromBase64(itemData);
                    itemsList = new ArrayList<>(Arrays.asList(itemsArray)); // Create a modifiable ArrayList
                }
            }
        } catch (Exception e) {
            this.getLogger().severe("An error occurred while handling the pickup stash for player UUID: " + playerUUID);
            e.printStackTrace();
        }
        return itemsList;
    }
    private boolean stashExistsInMySQL(String playerUUID) {
        try {
            String query = "SELECT COUNT(1) FROM stash_table WHERE player_uuid = ?;"; // Replace "stash_table" with your table name

            try (PreparedStatement statement = getMySQLConnector().getConnection().prepareStatement(query)) {
                statement.setString(1, playerUUID);
                ResultSet results = statement.executeQuery();

                if (results.next()) {
                    return results.getInt(1) > 0;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("pickupstash")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                pickupStashCommand(player);
            } else {
                sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            }
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("fillstash")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (player.hasPermission("inventorypages.fillstash")) {
                    List<ItemStack> randomItems = generateRandomItems();
                    setPickupStash(player.getUniqueId().toString(), randomItems);
                    player.sendMessage(ChatColor.GREEN + "Your pickup stash has been filled with random items.");
                } else {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                }
            } else {
                sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            }
            return true;
        }
        return false;
    }
    private List<ItemStack> generateRandomItems() {
        List<ItemStack> items = new ArrayList<>();
        Random random = new Random();
        Material[] materials = Material.values();

        // Generate 5 random items
        for (int i = 0; i < 5; i++) {
            Material material = materials[random.nextInt(materials.length)];
            if (material.isItem()) {
                ItemStack item = new ItemStack(material, random.nextInt(64) + 1);
                items.add(item);
            }
        }
        return items;
    }
    public void startSaving() {
        BukkitScheduler scheduler = getServer().getScheduler();
        scheduler.scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                updateAndSaveAllInventoriesToFiles();
            }
        }, 0L, 20 * getConfig().getInt("saving.interval"));
    }

    public void pickupStashCommand(Player player) {
        List<ItemStack> stashItems = getPickupStash(player.getUniqueId().toString());
        PlayerInventory inventory = player.getInventory();

        Iterator<ItemStack> iterator = stashItems.iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            HashMap<Integer, ItemStack> notAdded = inventory.addItem(item);
            if (!notAdded.isEmpty()) {
                // Not all items could be added, stop the process
                break;
            }
            iterator.remove(); // Remove the item from stash since it's been added to inventory
        }

        setPickupStash(String.valueOf(player.getUniqueId()), stashItems); // Update the stash
        player.sendMessage(ChatColor.GREEN + "Your inventory has been filled with items from your stash.");
    }

    // ======================================
    // Save Inventory From HashMap To File or MySQL
    // ======================================

    public void saveInventory(Player player) {
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            org.aidan.inventorypages.CustomInventory inv = playerInvs.get(playerUUID);

            if (getConfig().getBoolean("mysql.enabled")) {
                inv.saveToMySQL();
            } else {
                saveInvFromHashMapToFile(player);
            }
        }
    }

    public void saveInvFromHashMapToFile(Player player) {
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            File playerFile = new File(getDataFolder() + "/inventories/" + playerUUID.substring(0, 1) + "/" + playerUUID + ".yml");
            FileConfiguration playerData = YamlConfiguration.loadConfiguration(playerFile);
            // save survival items
            for (Entry<Integer, ArrayList<ItemStack>> pageItemEntry : playerInvs.get(playerUUID).getItems().entrySet()) {
                for (int i = 0; i < pageItemEntry.getValue().size(); i++) {
                    if (pageItemEntry.getValue().get(i) != null) {
                        playerData.set("items.main." + pageItemEntry.getKey() + "." + i, InventoryStringDeSerializer.toBase64(pageItemEntry.getValue().get(i)));
                    } else {
                        playerData.set("items.main." + pageItemEntry.getKey() + "." + i, null);
                    }
                }
            }

            // save creative items
            if (playerInvs.get(playerUUID).hasUsedCreative()) {
                for (int i = 0; i < playerInvs.get(playerUUID).getCreativeItems().size(); i++) {
                    if (playerInvs.get(playerUUID).getCreativeItems().get(i) != null) {
                        playerData.set("items.creative.0." + i, InventoryStringDeSerializer.toBase64(playerInvs.get(playerUUID).getCreativeItems().get(i)));
                    } else {
                        playerData.set("items.creative.0." + i, null);
                    }
                }
            }

            // save current page
            playerData.set("page", playerInvs.get(playerUUID).getPage());

            try {
                playerData.save(playerFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // ======================================
    // Load Inventory From File or MySQL Into HashMap
    // ======================================

    public void loadInvFromFileIntoHashMap(Player player) throws IOException {
        clearAndRemoveCrashedPlayer(player);

        int maxPage = 1;
        Boolean foundPerm = false;
        for (int i = 2; i < 101; i++) {
            if (player.hasPermission("inventorypages.pages." + i)) {
                foundPerm = true;
                maxPage = i - 1;
            }
        }

        if (foundPerm) {
            String playerUUID = player.getUniqueId().toString();
            org.aidan.inventorypages.CustomInventory inventory = new org.aidan.inventorypages.CustomInventory(this, player, maxPage, prevItem, prevPos, nextItem, nextPos, noPageItem, itemsMerged, itemsDropped);
            playerInvs.put(playerUUID, inventory);
            addCrashedPlayer(player);
            playerInvs.get(playerUUID).showPage(player.getGameMode());
        }
    }

    public void loadInventory(Player player) throws IOException {
        String playerUUID = player.getUniqueId().toString();
        if (getConfig().getBoolean("mysql.enabled")) {
            // Calculate or retrieve the maxPage value
            int maxPage = getMaxPageForPlayer(player);

            // Create a new CustomInventory instance with the determined maxPage
            org.aidan.inventorypages.CustomInventory inventory = new org.aidan.inventorypages.CustomInventory(this, player, maxPage, prevItem, prevPos, nextItem, nextPos, noPageItem, itemsMerged, itemsDropped);
            inventory.loadFromMySQL();
            playerInvs.put(playerUUID, inventory);
        } else {
            loadInvFromFileIntoHashMap(player);
        }
    }

    private int getMaxPageForPlayer(Player player) {
        int maxPage = 1; // Default value
        for (int i = 2; i <= 100; i++) {
            if (player.hasPermission("inventorypages.pages." + i)) {
                maxPage = i;
            }
        }
        return maxPage;
    }

    // ======================================
    // Update Inventory To HashMap
    // ======================================
    public void updateInvToHashMap(Player player) {
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            playerInvs.get(playerUUID).saveCurrentPage();
        }
    }

    // ======================================
    // Remove Inventory From HashMap
    // ======================================
    public void removeInvFromHashMap(Player player) {
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            playerInvs.remove(playerUUID);
            clearAndRemoveCrashedPlayer(player);
        }
    }

    // ======================================
    // Save Crashed File
    // ======================================
    public void saveCrashedFile() {
        try {
            crashedData.save(crashedFile);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    // ======================================
    // Crashed Players Exist
    // ======================================
    public Boolean crashedPlayersExist() {
        if (crashedData.contains("crashed")) {
            if (crashedData.getConfigurationSection("crashed").getKeys(false).size() > 0) {
                return true;
            }
        }
        return false;
    }

    // ======================================
    // Has Crashed
    // ======================================
    public Boolean hasCrashed(Player player) {
        if (crashedData.contains("crashed." + player.getUniqueId().toString())) {
            return true;
        }
        return false;
    }

    // ======================================
    // Add Crashed Player
    // ======================================
    public void addCrashedPlayer(Player player) {
        crashedData.set("crashed." + player.getUniqueId().toString(), true);
        saveCrashedFile();
    }

    // ======================================
    // Clear and Remove Crashed Player
    // ======================================
    public void clearAndRemoveCrashedPlayer(Player player) {
        if (crashedPlayersExist()) {
            if (hasCrashed(player)) {
                for (int i = 0; i < 27; i++) {
                    player.getInventory().setItem(i + 9, null);
                }
                crashedData.set("crashed." + player.getUniqueId().toString(), null);
                saveCrashedFile();
            }
        }
    }

    // ======================
    // Clear Player Hotbar
    // ======================
    public void clearHotbar(Player player) {
        for (int i = 0; i < 9; i++) {
            player.getInventory().setItem(i, null);
        }
    }

    // ======================================
    // Has Switcher Items
    // ======================================
    public Boolean hasSwitcherItems(Player player) {
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            if (player.getGameMode() != GameMode.CREATIVE) {
                return true;
            }
        }
        return false;
    }

    // ======================================
    // Is A Switcher Item
    // ======================================
    public Boolean isSwitcherItem(ItemStack item, ItemStack switcherItem) {
        if (item != null) {
            if (item.getType() != null) {
                if (item.getType().equals(switcherItem.getType())) {
                    if (item.getItemMeta() != null) {
                        if (item.getItemMeta().getDisplayName() != null) {
                            if (item.getItemMeta().getDisplayName().equals(switcherItem.getItemMeta().getDisplayName())) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    // ======================================
    // Login
    // ======================================
    @EventHandler
    public void playerJoin(PlayerJoinEvent event) throws InterruptedException, IOException {
        Player player = event.getPlayer();
        loadInventory(player);
    }


    // ======================================
    // Logout
    // ======================================
    @EventHandler
    public void playerQuit(PlayerQuitEvent event) throws InterruptedException {
        Player player = event.getPlayer();
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            updateInvToHashMap(player);
            saveInventory(player);
            removeInvFromHashMap(player);
        }
    }


    // ======================================
    // Death
    // ======================================
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            //save items before death
            updateInvToHashMap(player);

            event.setKeepInventory(true);

            GameMode gm = player.getGameMode();

            // Default drop all
            int dropOption = 2;

            // If you have keep unopened, drop only the current page
            if (player.hasPermission("inventorypages.keep.unopened")) {
                dropOption = 1;
            }

            // If you have keep all, don't drop anything
            if (player.hasPermission("inventorypages.keep.all")) {
                dropOption = 0;
            }

            if (dropOption == 1) {
                playerInvs.get(playerUUID).dropPage(gm);
            } else if (dropOption == 2) {
                playerInvs.get(playerUUID).dropAllPages(gm);
            }

            if (!player.hasPermission("inventorypages.keep.hotbar")) {
                dropHotbar(player);
            }
        }
    }

    private void dropHotbar(Player player) {
        PlayerInventory playerInv = player.getInventory();
        for (int i = 0; i <= 8; i++) {
            ItemStack item = playerInv.getItem(i);
            if (item != null) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
                player.getInventory().remove(item);
            }
        }
    }

    // ======================================
    // Respawn
    // ======================================
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            GameMode gm = player.getGameMode();
            playerInvs.get(playerUUID).showPage(gm);
        }
    }

    // ======================================
    // Inventory Click
    // ======================================
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory clickedInv = getClickedInventory(event.getView(), event.getRawSlot());
        if (clickedInv != null) {
            if (clickedInv.getType() == InventoryType.PLAYER) {
                InventoryHolder holder = clickedInv.getHolder();
                if (holder instanceof Player) {
                    Player player = (Player) holder;
                    if (hasSwitcherItems(player)) {
                        ItemStack item = event.getCurrentItem();
                        if (isSwitcherItem(item, prevItem)) {
                            event.setCancelled(true);
                            playerInvs.get(player.getUniqueId().toString()).prevPage();
                        } else if (isSwitcherItem(item, nextItem)) {
                            event.setCancelled(true);
                            playerInvs.get(player.getUniqueId().toString()).nextPage();
                        } else if (isSwitcherItem(item, noPageItem)) {
                            event.setCancelled(true);
                        }
                    }
                }
            }
        }
    }

    // ======================================
    // Get Clicked Inventory
    // ======================================
    public Inventory getClickedInventory(InventoryView view, int slot) {

        int topInvSize = view.getTopInventory().getSize();
        if (view.getTopInventory().getType() == InventoryType.PLAYER) {
            int topInvRemainder = topInvSize % 9;
            if (topInvRemainder != 0) {
                topInvSize = topInvSize - topInvRemainder;
            }
        }

        Inventory clickedInventory;
        if (slot < 0) {
            clickedInventory = null;
        } else if (view.getTopInventory() != null && slot < topInvSize) {
            clickedInventory = view.getTopInventory();
        } else {
            clickedInventory = view.getBottomInventory();
        }
        return clickedInventory;
    }

    // ======================================
    // GameMode Change
    // ======================================
    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            playerInvs.get(playerUUID).saveCurrentPage();
            playerInvs.get(playerUUID).showPage(event.getNewGameMode());
        }
    }

    // ======================
    // Commands
    // ======================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String cmdLine = event.getMessage().toLowerCase();
        // clear
        for (String clearCommand : this.clearCommands) {
            if (cmdLine.startsWith("/" + clearCommand + " ") || cmdLine.equalsIgnoreCase("/" + clearCommand)) {
                Player player = event.getPlayer();
                String playerUUID = player.getUniqueId().toString();

                if (playerInvs.containsKey(playerUUID)) {
                    event.setCancelled(true);
                    if (player.hasPermission("inventorypages.clear")) {
                        GameMode gm = player.getGameMode();
                        if (cmdLine.startsWith("/" + clearCommand + " all ") || cmdLine.equalsIgnoreCase("/" + clearCommand + " all")) {
                            playerInvs.get(playerUUID).clearAllPages(gm);
                            player.sendMessage(clearAll);
                        } else {
                            playerInvs.get(playerUUID).clearPage(gm);
                            player.sendMessage(clear);
                        }
                        clearHotbar(player);
                        playerInvs.get(playerUUID).showPage(gm);
                    } else {
                        player.sendMessage(noPermission);
                    }
                }
            }
        }
    }
}