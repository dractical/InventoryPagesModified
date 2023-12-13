package org.aidan.inventorypages;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

public class ColorConverter {
    public InventoryPages plugin;

    public ColorConverter(InventoryPages plugin) {
        this.plugin = plugin;
    }

    String convert(String toConvert) {
        return ChatColor.translateAlternateColorCodes('&', toConvert);
    }

    List<String> convert(List<String> toConvert) {
        List<String> translatedColors = new ArrayList<String>();
        for (String stringToTranslate : toConvert) {
            translatedColors.add(ChatColor.translateAlternateColorCodes('&', stringToTranslate));
        }
        return translatedColors;
    }

    String convertConfig(String toConvert) {
        return ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString(toConvert));
    }

    List<String> convertConfigList(String toConvert) {
        List<String> translatedColors = new ArrayList<String>();
        for (String stringToTranslate : plugin.getConfig().getStringList(toConvert)) {
            translatedColors.add(ChatColor.translateAlternateColorCodes('&', stringToTranslate));
        }
        return translatedColors;
    }
}