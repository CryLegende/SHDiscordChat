package org.Cry_Legende.sHDiscordChat;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SHDiscordChat extends JavaPlugin implements Listener {

    private String webhookUrl;
    private String avatarApiUrl;
    private String nameFormat;
    private String startupMessage;
    private String shutdownMessage;
    private static Chat chat;
    private static Permission perms;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (!setupChat() || !setupPermissions()) {
            getLogger().severe("Vault with a chat plugin and permissions plugin is required!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        sendServerMessage(startupMessage);
    }

    @Override
    public void onDisable() {
        sendServerMessage(shutdownMessage);
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        webhookUrl = config.getString("discord.webhook-url");
        avatarApiUrl = config.getString("api.avatar-url");
        nameFormat = config.getString("discord.name-format");
        startupMessage = config.getString("discord.startup-message");
        shutdownMessage = config.getString("discord.shutdown-message");
    }

    private boolean setupChat() {
        RegisteredServiceProvider<Chat> rsp = getServer().getServicesManager().getRegistration(Chat.class);
        chat = rsp.getProvider();
        return chat != null;
    }

    private boolean setupPermissions() {
        RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
        perms = rsp.getProvider();
        return perms != null;
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player player = event.getPlayer();
        String message = event.getMessage();
        String[] groups = perms.getPlayerGroups(player);
        String grade = groups.length > 0 ? groups[0] : "NoGroup";
        String formattedGrade = capitalizeFirstLetter(grade);
        String playerName = player.getName();

        String avatarUrl = avatarApiUrl.replace("{player}", playerName);
        String botName = nameFormat.replace("{grade}", formattedGrade).replace("{pseudo}", playerName);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            sendMessageToDiscord(botName, message, avatarUrl);
        });
    }


    private String capitalizeFirstLetter(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.substring(0, 1).toUpperCase() + input.substring(1).toLowerCase();
    }

    private void sendMessageToDiscord(String username, String content, String avatarUrl) {
        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            JSONObject json = new JSONObject();
            json.put("username", username);
            json.put("content", content);
            json.put("avatar_url", avatarUrl);

            OutputStream os = connection.getOutputStream();
            os.write(json.toString().getBytes());
            os.flush();
            os.close();

            int responseCode = connection.getResponseCode();
            if (responseCode != 204) {
                getLogger().warning("Failed to send message to Discord, response code: " + responseCode);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendServerMessage(String message) {
        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            JSONObject json = new JSONObject();
            json.put("content", message);

            OutputStream os = connection.getOutputStream();
            os.write(json.toString().getBytes());
            os.flush();
            os.close();

            int responseCode = connection.getResponseCode();
            if (responseCode != 204) {
                getLogger().warning("Failed to send server message to Discord, response code: " + responseCode);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
