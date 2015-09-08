package net.samagames.core.api.permissions;

import net.samagames.api.permissions.permissions.PermissionEntity;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.util.UUID;

/**
 * This file is a part of the SamaGames project
 * This code is absolutely confidential.
 * (C) Copyright Elydra Network 2015
 * All rights reserved.
 */
public class PermissionsManager extends BasicPermissionManager
{

    @Override
    public String getPrefix(PermissionEntity entity)
    {
        String prefix = entity.getProperty("prefix");
        if (prefix == null)
            return "";
        prefix = prefix.replaceAll("&s", " ");
        prefix = ChatColor.translateAlternateColorCodes('&', prefix);
        return prefix;
    }

    @Override
    public String getSuffix(PermissionEntity entity)
    {
        String suffix = entity.getProperty("suffix");
        if (suffix == null)
            return "";
        suffix = suffix.replaceAll("&s", " ");
        suffix = ChatColor.translateAlternateColorCodes('&', suffix);
        return suffix;
    }

    @Override
    public String getDisplay(PermissionEntity entity)
    {
        String display = entity.getProperty("display");
        if (display == null)
            return "";
        return ChatColor.translateAlternateColorCodes('&', display.replaceAll("&s", " "));
    }

    @Override
    public boolean hasPermission(PermissionEntity entity, String permission)
    {
        return entity.hasPermission(permission);
    }

    /**
     * Only works for onlineplayers.
     *
     * @param player     UUID for the player. Must be online
     * @param permission The permission to check
     */
    @Override
    public boolean hasPermission(UUID player, String permission)
    {
        PermissionEntity entity = api.getManager().getUserFromCache(player);
        if (entity == null)
        {
            Bukkit.getLogger().warning("Entity " + player + " is not found in cache.");
            return false;
        }
        return entity.hasPermission(permission);
    }


}
