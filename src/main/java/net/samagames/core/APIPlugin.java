package net.samagames.core;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.samagames.core.database.DatabaseConnector;
import net.samagames.core.database.RedisServer;
import net.samagames.core.listeners.*;
import net.samagames.persistanceapi.GameServiceManager;
import net.samagames.tools.Reflection;
import net.samagames.tools.npc.NPCManager;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.v1_9_R1.CraftServer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.Jedis;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * This file is a part of the SamaGames project
 * This code is absolutely confidential.
 * (C) Copyright Elydra Network 2015
 * All rights reserved.
 */
public class APIPlugin extends JavaPlugin implements Listener
{

    private static APIPlugin instance;
    private final CopyOnWriteArraySet<String> ipWhiteList = new CopyOnWriteArraySet<>();
    private ApiImplementation api;
    private DatabaseConnector databaseConnector;
    private String serverName;
    private FileConfiguration configuration;
    private boolean allowJoin;
    private boolean disableWhitelist;
    private final String denyJoinReason = ChatColor.RED + "Serveur non initialisé.";
    private boolean serverRegistered;
    private String joinPermission = null;
    private ScheduledExecutorService executor;
    private DebugListener debugListener;

    private NicknamePacketListener nicknamePacketListener;
    private NPCManager npcManager;
    private BukkitTask startTimer;

    private ChatHandleListener chatHandleListener;
    private GlobalJoinListener globalJoinListener;

    private GameServiceManager gameServiceManager;


    public static APIPlugin getInstance()
    {
        return instance;
    }

    public static void log(String message)
    {
        instance.getLogger().info(message);
    }

    public static void log(Level level, String message)
    {
        instance.getLogger().log(level, message);
    }

    public ApiImplementation getAPI()
    {
        return api;
    }

    private void removeCommand(String str) throws NoSuchFieldException, IllegalAccessException
    {
        SimpleCommandMap scm = ((CraftServer)Bukkit.getServer()).getCommandMap();
        Map knownCommands = (Map) Reflection.getValue(scm, true, "knownCommands");
        knownCommands.remove(str);
    }

    public void onEnable()
    {
        instance = this;

        log("#==========[WELCOME TO SAMAGAMES API]==========#");
        log("# SamaGamesAPI is now loading. Please read     #");
        log("# carefully all outputs coming from it.        #");
        log("#==============================================#");

        executor = Executors.newScheduledThreadPool(16);

        log("Loading main configuration...");
        this.saveDefaultConfig();
        configuration = this.getConfig();

        // Chargement de l'IPWhitelist le plus tot possible
        Bukkit.getPluginManager().registerEvents(this, this);

        serverName = configuration.getString("bungeename");

        if (serverName == null)
        {
            log(Level.SEVERE, "Plugin cannot load : ServerName is empty.");
            this.setEnabled(false);
            Bukkit.getServer().shutdown();
            return;
        }

        joinPermission = getConfig().getString("join-permission");
        disableWhitelist = getConfig().getBoolean("disable-whitelist", false);

        File conf = new File(getDataFolder().getAbsoluteFile().getParentFile().getParentFile(), "data.yml");
        this.getLogger().info("Searching data.yml in " + conf.getAbsolutePath());
        if (!conf.exists())
        {
            log(Level.SEVERE, "Cannot find database configuration. Stopping!");
            this.setEnabled(false);
            this.getServer().shutdown();
            return;
        } else
        {
            YamlConfiguration dataYML = YamlConfiguration.loadConfiguration(conf);

            String bungeeIp = dataYML.getString("redis-bungee-ip", "127.0.0.1");
            int bungeePort = dataYML.getInt("redis-bungee-port", 4242);
            String bungeePassword = dataYML.getString("redis-bungee-password", "passw0rd");
            RedisServer bungee = new RedisServer(bungeeIp, bungeePort, bungeePassword);

            String sqlUrl = dataYML.getString("sql-url", "127.0.0.1");
            String sqlUsername = dataYML.getString("sql-username", "root");
            String sqlPassword = dataYML.getString("sql-password", "passw0rd");
            int sqlMinPoolSize = dataYML.getInt("sql-minpoolsize", 1);
            int sqlMaxPoolSize = dataYML.getInt("sql-maxpoolsize", 10);

            gameServiceManager = new GameServiceManager(sqlUrl, sqlUsername, sqlPassword, sqlMinPoolSize, sqlMaxPoolSize);

            databaseConnector = new DatabaseConnector(this, bungee);

        }

        api = new ApiImplementation(this);
        /*
        Loading listeners
		 */

        chatHandleListener = new ChatHandleListener(this);
        //Mute
        api.getPubSub().subscribe("mute.add", chatHandleListener);
        api.getPubSub().subscribe("mute.remove", chatHandleListener);

        Bukkit.getPluginManager().registerEvents(chatHandleListener, this);

        globalJoinListener = new GlobalJoinListener(api);
        Bukkit.getPluginManager().registerEvents(globalJoinListener, this);

        debugListener = new DebugListener();
        api.getJoinManager().registerHandler(debugListener, 0);

        //Invisible fix
        getServer().getPluginManager().registerEvents(new InvisiblePlayerFixListener(this), this);

        api.getPubSub().subscribe("*", debugListener);
        //Nickname
        //TODO nickname
        nicknamePacketListener = new NicknamePacketListener(this);

        npcManager = new NPCManager(api);
        Bukkit.getPluginManager().registerEvents(new TabsColorsListener(this), this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, "WDL|CONTROL");
        Bukkit.getMessenger().registerIncomingPluginChannel(this, "WDL|INIT", (s, player, bytes) -> {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeInt(1);
            out.writeBoolean(false);
            out.writeInt(1);
            out.writeBoolean(false);
            out.writeBoolean(false);
            out.writeBoolean(false);
            out.writeBoolean(false);
            Bukkit.getLogger().info("Blocked WorldDownloader of " + player.getDisplayName());
            player.sendPluginMessage(this, "WDL|CONTROL", out.toByteArray());
        });

        /*
        Loading commands
		 */

        for (String command : this.getDescription().getCommands().keySet())
        {
            try
            {
                Class clazz = Class.forName("net.samagames.core.commands.Command" + StringUtils.capitalize(command));
                Constructor<APIPlugin> ctor = clazz.getConstructor(APIPlugin.class);
                getCommand(command).setExecutor(ctor.newInstance(this));
                log("Loaded command " + command + " successfully. ");
            } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | InvocationTargetException | IllegalAccessException e)
            {
                e.printStackTrace();
            }
        }

        registerServer();
        allowJoin();
        this.startTimer = getServer().getScheduler().runTaskTimer(this, this::postInit, 20L, 20L);
    }

    public void disable()
    {
        this.setEnabled(false);
    }

    public DebugListener getDebugListener()
    {
        return debugListener;
    }

    public ScheduledExecutorService getExecutor()
    {
        return executor;
    }

    private void postInit()
    {
        this.startTimer.cancel();
        
        try
        {
            log("Removing private commands...");
            removeCommand("me");
            removeCommand("minecraft:me");
            removeCommand("tell");
            removeCommand("minecraft:tell");
            removeCommand("bukkit:help");
            removeCommand("pl");
            removeCommand("plugins");
            log("Removed private commands.");
        }
        catch (ReflectiveOperationException e)
        {
            log("Patching error");
            e.printStackTrace();
        }
    }

    public void onDisable()
    {
        String bungeename = getServerName();
        Jedis rb_jedis = databaseConnector.getBungeeResource();
        rb_jedis.hdel("servers", bungeename);
        rb_jedis.close();
        api.getPubSub().send("servers", "stop " + bungeename);
        nicknamePacketListener.close();
        databaseConnector.killConnection();
        executor.shutdownNow();
        api.onShutdown();
        getServer().shutdown();
    }

    public boolean canConnect(String ip)
    {
        return containsIp(ip);
    }

    public void refreshIps(Set<String> ips)
    {
        ipWhiteList.stream().filter(ip -> !ips.contains(ip)).forEach(ipWhiteList::remove);

        ips.stream().filter(ip -> !ipWhiteList.contains(ip)).forEach(ipWhiteList::add);
    }

    private boolean containsIp(String ip)
    {
        return ipWhiteList.contains(ip);
    }

    private void allowJoin()
    {
        allowJoin = true;
    }

    public String getServerName()
    {
        return serverName;
    }

    private void registerServer()
    {
        if (serverRegistered)
            return;

        log("Trying to register server to the proxy");
        //now done by hydro
        try
        {
            String bungeename = getServerName();

            Jedis rb_jedis = databaseConnector.getBungeeResource();
            rb_jedis.hset("servers", bungeename, this.getServer().getIp() + ":" + this.getServer().getPort());
            rb_jedis.close();


            api.getPubSub().send("servers", "heartbeat " + bungeename + " " + this.getServer().getIp() + " " + this.getServer().getPort());

            getExecutor().scheduleAtFixedRate(() -> {
                try {
                    Jedis jedis = databaseConnector.getBungeeResource();
                    jedis.hset("servers", bungeename, getServer().getIp() + ":" + getServer().getPort());
                    jedis.close();

                    api.getPubSub().send("servers", "heartbeat " + getServerName() + " " + getServer().getIp() + " " + getServer().getPort());
                }catch (Exception e)
                {
                    e.printStackTrace();
                }
            }, 30, 20, TimeUnit.SECONDS);
        } catch (Exception ignore)
        {
            ignore.printStackTrace();
            return;
        }

        serverRegistered = true;
    }


    /*
    Listen for join
	 */

    @EventHandler
    public void onLogin(AsyncPlayerPreLoginEvent event)
    {
        if (!allowJoin)
        {
            event.disallow(Result.KICK_OTHER, ChatColor.RED + denyJoinReason);
            event.setKickMessage(ChatColor.RED + denyJoinReason);

            return;
        }

        if (!ipWhiteList.contains(event.getAddress().getHostAddress()) && !disableWhitelist)
        {
            event.setKickMessage(ChatColor.RED + "Vous n'avez pas la permission de rejoindre ce serveur.");
            Bukkit.getLogger().log(Level.WARNING, "An user tried to connect from IP " + event.getAddress().getHostAddress());
        }

        if (joinPermission != null && !api.getPermissionsManager().hasPermission(event.getUniqueId(), joinPermission))
        {
            event.disallow(Result.KICK_WHITELIST, "Vous n'avez pas la permission de rejoindre ce serveur.");
        }
    }

    public GlobalJoinListener getGlobalJoinListener()
    {
        return globalJoinListener;
    }

    public DatabaseConnector getDatabaseConnector()
    {
        return databaseConnector;
    }

    public NPCManager getNPCManager() {
        return npcManager;
    }

    public GameServiceManager getGameServiceManager() {
        return gameServiceManager;
    }
}
