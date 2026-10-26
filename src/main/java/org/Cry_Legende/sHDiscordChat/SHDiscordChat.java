package org.Cry_Legende.sHDiscordChat;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class SHDiscordChat extends JavaPlugin implements Listener {

    // Sans délai, une requête vers un Discord qui ne répond plus ne rendait jamais la main
    private static final int HTTP_TIMEOUT_MS = 5000;
    // Messages en attente au plus : au-delà (Discord en panne), les nouveaux sont abandonnés au lieu de s'accumuler
    private static final int QUEUE_SIZE = 200;
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private String webhookUrl;
    private String avatarApiUrl;
    private String nameFormat;
    private String startupMessage;
    private String shutdownMessage;
    private static Chat chat;
    private static Permission perms;

    // Envois au webhook : jamais sur le thread principal, un seul thread pour garder l'ordre du chat
    private ThreadPoolExecutor webhookExecutor;
    private final AtomicLong lastDropWarning = new AtomicLong();
    private boolean started;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        webhookExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_SIZE),
            runnable -> {
                Thread thread = new Thread(runnable, "SHDiscordChat-webhook");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, executor) -> warnDropped());
        if (!setupChat() || !setupPermissions()) {
            getLogger().severe("Vault with a chat plugin and permissions plugin is required!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Écouteur enregistré une fois Vault prêt : le chat est asynchrone et pourrait arriver avant (perms null)
        registerChatRelay();
        started = true;
        webhookExecutor.execute(() -> sendServerMessage(startupMessage));
    }

    @Override
    public void onDisable() {
        if (webhookExecutor == null) {
            return;
        }
        // Pas de message d'arrêt si le plugin n'a jamais démarré (Vault absent)
        if (started) {
            webhookExecutor.execute(() -> sendServerMessage(shutdownMessage));
        }
        webhookExecutor.shutdown();
        try {
            // L'envoi reste hors du thread principal, qui laisse seulement quelques secondes aux derniers messages
            if (!webhookExecutor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                webhookExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            webhookExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
        chat = rsp != null ? rsp.getProvider() : null;
        return chat != null;
    }

    private boolean setupPermissions() {
        RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
        perms = rsp != null ? rsp.getProvider() : null;
        return perms != null;
    }

    /**
     * Branche le relais du chat. Avec ChatControl (canaux), on écoute son ChannelPostChatEvent : le message y a déjà
     * passé mute, filtres et anti-spam, alors que le chat Bukkit part avant ces contrôles et relayait les messages
     * bloqués. Sans ChatControl, on écoute le chat Bukkit en dernier, seulement s'il n'a pas été annulé.
     * Écoute par réflexion : pas de dépendance de compilation sur ChatControl.
     */
    @SuppressWarnings("unchecked")
    private void registerChatRelay() {
        PluginManager pm = getServer().getPluginManager();
        Plugin chatControl = pm.getPlugin("ChatControl");
        if (chatControl != null && chatControl.isEnabled()) {
            try {
                Class<? extends Event> type = (Class<? extends Event>) Class.forName(
                    "org.mineacademy.chatcontrol.api.ChannelPostChatEvent", true, chatControl.getClass().getClassLoader());
                Method sender = type.getMethod("getSender");
                Method text = type.getMethod("getMessage");
                Method silent = type.getMethod("isCancelledSilently");
                pm.registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
                    if (!type.isInstance(event)) return;
                    try {
                        // Annulé « en silence » : seul l'auteur voit son message (mute fantôme, anti-spam)
                        if ((boolean) silent.invoke(event)) return;
                        if (sender.invoke(event) instanceof Player player) {
                            relay(player, (String) text.invoke(event));
                        }
                    } catch (ReflectiveOperationException e) {
                        getLogger().log(Level.WARNING, "Lecture d'un message ChatControl impossible", e);
                    }
                }, this, true);
                getLogger().info("Relais du chat branché sur ChatControl (après mute, filtres et anti-spam).");
                return;
            } catch (ReflectiveOperationException | ClassCastException e) {
                getLogger().log(Level.WARNING, "API ChatControl introuvable, relais sur le chat Bukkit", e);
            }
        }
        pm.registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        relay(event.getPlayer(), event.getMessage());
    }

    private void relay(Player player, String message) {
        String[] groups = perms.getPlayerGroups(player);
        String grade = groups.length > 0 ? groups[0] : "NoGroup";
        String formattedGrade = capitalizeFirstLetter(grade);
        String playerName = player.getName();

        String avatarUrl = avatarApiUrl.replace("{player}", playerName);
        String botName = nameFormat.replace("{grade}", formattedGrade).replace("{pseudo}", playerName);

        webhookExecutor.execute(() -> sendMessageToDiscord(botName, message, avatarUrl));
    }


    private String capitalizeFirstLetter(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.substring(0, 1).toUpperCase() + input.substring(1).toLowerCase();
    }

    private void sendMessageToDiscord(String username, String content, String avatarUrl) {
        JSONObject json = new JSONObject();
        json.put("username", username);
        json.put("content", content);
        json.put("avatar_url", avatarUrl);
        // Texte d'un joueur : aucune mention active (@everyone, @here, rôles, membres) ne doit notifier le Discord
        json.put("allowed_mentions", new JSONObject().put("parse", new JSONArray()));
        postWebhook(json, "message");
    }

    private void sendServerMessage(String message) {
        JSONObject json = new JSONObject();
        json.put("content", message);
        postWebhook(json, "server message");
    }

    /** Envoi au webhook (thread du webhook uniquement). Une seconde tentative si Discord limite le débit (429). */
    private void postWebhook(JSONObject json, String what) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                connection.setConnectTimeout(HTTP_TIMEOUT_MS);
                connection.setReadTimeout(HTTP_TIMEOUT_MS);
                connection.setDoOutput(true);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == 429 && attempt == 0) {
                    Thread.sleep(retryAfterMillis(connection));
                    continue;
                }
                if (responseCode != 204) {
                    getLogger().warning("Failed to send " + what + " to Discord, response code: " + responseCode);
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // L'URL du webhook contient son jeton : jamais dans les logs (ni pile d'erreur, ni message brut)
                String detail = String.valueOf(e.getMessage()).replace(webhookUrl, "<webhook>");
                getLogger().warning("Failed to send " + what + " to Discord: " + e.getClass().getSimpleName() + " " + detail);
                return;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    /** Délai demandé par Discord (en-tête Retry-After, en secondes), borné entre 0,5 et 5 s. */
    private static long retryAfterMillis(HttpURLConnection connection) {
        try {
            double seconds = Double.parseDouble(connection.getHeaderField("Retry-After"));
            return Math.max(500L, Math.min(5000L, (long) (seconds * 1000)));
        } catch (RuntimeException e) {
            return 1000L;
        }
    }

    private void warnDropped() {
        long now = System.currentTimeMillis();
        long last = lastDropWarning.get();
        if (now - last > 60_000L && lastDropWarning.compareAndSet(last, now)) {
            getLogger().warning("Discord ne suit plus : messages du chat abandonnés (file d'envoi pleine).");
        }
    }
}
