package me.galyakyxnya.telegrambot;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class UniversalTelegramBot extends JavaPlugin {

    private FileConfiguration config;

    // Telegram бот
    private String botToken;
    private String groupChatId;
    private boolean telegramBotEnabled;
    private boolean debugMode;
    private int checkInterval;
    private TelegramBotThread botThread;

    // Система команд
    private Map<String, BotCommand> commands = new HashMap<>();
    private Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private Map<String, Set<String>> userPermissions = new ConcurrentHashMap<>();

    // Файлы
    private File usedFile;
    private File permissionsFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();

        telegramBotEnabled = config.getBoolean("telegram.enabled", true);
        debugMode = config.getBoolean("telegram.debug", false);
        checkInterval = config.getInt("telegram.check-interval", 3);
        botToken = config.getString("telegram.bot-token", "").trim();
        groupChatId = config.getString("telegram.group-chat-id", "").trim();

        setupFiles();
        loadCommands();
        loadPermissions();

        if (telegramBotEnabled && !botToken.isEmpty()) {
            if (checkBotConnection()) {
                botThread = new TelegramBotThread();
                botThread.start();
                logInfo("Telegram бот запущен");

                // Отправляем приветственное сообщение
                String welcomeMsg = config.getString("messages.welcome",
                        "🤖 Бот активирован!\n" +
                                "Доступные команды:\n" +
                                "/help - список команд");
                sendTelegramMessage(groupChatId, welcomeMsg);
            } else {
                logWarning("Не удалось подключиться к Telegram боту. Проверьте токен.");
            }
        }

        startCleanupTimer();

        logInfo("══════════════════════════════════");
        logInfo("     Universal Telegram Bot       ");
        logInfo("══════════════════════════════════");
        logInfo("Telegram бот: " + (telegramBotEnabled ? "Включен" : "Выключен"));
        logInfo("Загружено команд: " + commands.size());
        logInfo("Интервал проверки: " + checkInterval + " сек");
        logInfo("Debug режим: " + (debugMode ? "Включен" : "Выключен"));
    }

    @Override
    public void onDisable() {
        if (botThread != null) {
            botThread.stopBot();
            try {
                botThread.join(3000);
            } catch (InterruptedException e) {
                logWarning("Ошибка при остановке бота: " + e.getMessage());
            }
        }

        saveCooldowns();
        savePermissions();
        logInfo("Бот отключен");
    }

    private void setupFiles() {
        String basePath = config.getString("files.base-path", "plugins/TelegramBot");
        new File(basePath).mkdirs();

        usedFile = new File(basePath, "cooldowns.txt");
        permissionsFile = new File(basePath, "permissions.txt");

        try {
            if (!usedFile.exists()) usedFile.createNewFile();
            if (!permissionsFile.exists()) permissionsFile.createNewFile();

            loadCooldowns();
        } catch (IOException e) {
            logSevere("Ошибка создания файлов: " + e.getMessage());
        }
    }

    private void loadCommands() {
        commands.clear();

        if (!config.isConfigurationSection("commands")) {
            logWarning("Секция 'commands' не найдена в конфиге!");
            return;
        }

        for (String cmdName : config.getConfigurationSection("commands").getKeys(false)) {
            String path = "commands." + cmdName;

            BotCommand cmd = new BotCommand();
            cmd.name = cmdName;
            cmd.command = config.getString(path + ".command", "");
            cmd.cooldown = config.getLong(path + ".cooldown", 86400);
            cmd.permission = config.getString(path + ".permission", "");
            cmd.message = config.getString(path + ".message", "✅ Команда выполнена!");
            cmd.errorMessage = config.getString(path + ".error-message", "❌ Ошибка выполнения команды");
            cmd.runAsConsole = config.getBoolean(path + ".run-as-console", true);
            cmd.usePlayerAsSender = config.getBoolean(path + ".use-player-as-sender", false);
            cmd.description = config.getString(path + ".eho", ""); // Загружаем описание

            commands.put(cmdName.toLowerCase(), cmd);
            logInfo("Загружена команда: /" + cmdName + " -> " + cmd.command);
        }
    }

    private void loadPermissions() {
        try {
            if (!permissionsFile.exists()) return;

            List<String> lines = Files.readAllLines(permissionsFile.toPath());
            for (String line : lines) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String userId = parts[0];
                    String[] perms = parts[1].split(",");
                    userPermissions.put(userId, new HashSet<>(Arrays.asList(perms)));
                }
            }

            logInfo("Загружено разрешений для " + userPermissions.size() + " пользователей");
        } catch (Exception e) {
            logWarning("Ошибка загрузки разрешений: " + e.getMessage());
        }
    }

    private void savePermissions() {
        try (PrintWriter pw = new PrintWriter(permissionsFile)) {
            for (Map.Entry<String, Set<String>> entry : userPermissions.entrySet()) {
                String perms = String.join(",", entry.getValue());
                pw.println(entry.getKey() + ":" + perms);
            }
        } catch (IOException e) {
            logWarning("Ошибка сохранения разрешений: " + e.getMessage());
        }
    }

    private boolean checkBotConnection() {
        try {
            URL url = new URL("https://api.telegram.org/bot" + botToken + "/getMe");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ========== Telegram бот ==========

    private class TelegramBotThread extends Thread {
        private volatile boolean running = true;
        private int lastUpdateId = 0;

        @Override
        public void run() {
            logInfo("Telegram бот запущен в отдельном потоке");

            while (running) {
                try {
                    checkTelegramUpdates();
                    Thread.sleep(checkInterval * 1000L);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    String errorMsg = e.getMessage();
                    if (debugMode && (errorMsg == null || !errorMsg.contains("Read timed out"))) {
                        logWarning("Ошибка в Telegram боте: " + errorMsg);
                    }
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            }

            logInfo("Telegram бот остановлен");
        }

        public void stopBot() {
            running = false;
            this.interrupt();
        }

        private void checkTelegramUpdates() throws IOException {
            URL url = new URL("https://api.telegram.org/bot" + botToken + "/getUpdates");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            String requestBody = String.format(
                    "{\"offset\": %d, \"timeout\": 30}",
                    lastUpdateId + 1
            );

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes());
                os.flush();
            }

            if (conn.getResponseCode() == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    processTelegramUpdates(response.toString());
                }
            }
        }

        private void processTelegramUpdates(String jsonResponse) {
            try {
                if (!jsonResponse.contains("\"ok\":true")) return;

                String[] updates = jsonResponse.split("\"update_id\":");

                for (int i = 1; i < updates.length; i++) {
                    try {
                        String update = updates[i];

                        // Получаем update_id
                        String updateIdStr = update.substring(0, update.indexOf(',')).trim();
                        int updateId = Integer.parseInt(updateIdStr);
                        lastUpdateId = Math.max(lastUpdateId, updateId);

                        // Проверяем что сообщение из нужной группы
                        if (!update.contains("\"chat\":{\"id\":" + groupChatId)) {
                            continue;
                        }

                        // Извлекаем текст сообщения
                        int textStart = update.indexOf("\"text\":\"");
                        if (textStart == -1) continue;

                        textStart += 8;
                        int textEnd = update.indexOf("\"", textStart);
                        if (textEnd == -1) continue;

                        String text = update.substring(textStart, textEnd);

                        // Извлекаем ID пользователя
                        String userId = "unknown";
                        int idStart = update.indexOf("\"from\":{\"id\":");
                        if (idStart != -1) {
                            idStart += 13;
                            int idEnd = update.indexOf(",", idStart);
                            if (idEnd != -1) {
                                userId = update.substring(idStart, idEnd).trim();
                            }
                        }

                        // Извлекаем имя пользователя
                        String username = "Пользователь";
                        int userStart = update.indexOf("\"username\":\"");
                        if (userStart != -1) {
                            userStart += 12;
                            int userEnd = update.indexOf("\"", userStart);
                            if (userEnd != -1) {
                                username = "@" + update.substring(userStart, userEnd);
                            }
                        } else {
                            int nameStart = update.indexOf("\"first_name\":\"");
                            if (nameStart != -1) {
                                nameStart += 15;
                                int nameEnd = update.indexOf("\"", nameStart);
                                if (nameEnd != -1) {
                                    username = update.substring(nameStart, nameEnd);
                                }
                            }
                        }

                        // Получаем message_id для ответа
                        String messageId = "0";
                        int msgIdStart = update.indexOf("\"message_id\":");
                        if (msgIdStart != -1) {
                            msgIdStart += 13;
                            int msgIdEnd = update.indexOf(",", msgIdStart);
                            if (msgIdEnd != -1) {
                                messageId = update.substring(msgIdStart, msgIdEnd).trim();
                            }
                        }

                        // Обрабатываем команду
                        if (text.startsWith("/")) {
                            processTelegramCommand(text, userId, username, messageId);
                        }

                    } catch (Exception e) {
                        if (debugMode) {
                            logWarning("Ошибка обработки обновления: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                if (debugMode) {
                    logWarning("Ошибка парсинга JSON: " + e.getMessage());
                }
            }
        }
    }

    private void processTelegramCommand(String text, String userId, String username, String messageId) {
        // Обработка команды help
        if (text.equalsIgnoreCase("/help") || text.equalsIgnoreCase("/start")) {
            sendHelpMessage(messageId, username);
            return;
        }

        // Разбираем команду
        String[] parts = text.substring(1).split("\\s+", 2);
        String commandName = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        // Проверяем команду
        BotCommand cmd = commands.get(commandName);
        if (cmd == null) {
            String unknownMsg = config.getString("messages.unknown-command",
                    "❌ Неизвестная команда. Используйте /help для списка команд");
            sendTelegramReply(messageId, unknownMsg);
            return;
        }

        // Проверяем разрешения
        if (!cmd.permission.isEmpty()) {
            Set<String> userPerms = userPermissions.get(userId);
            if (userPerms == null || !userPerms.contains(cmd.permission)) {
                sendTelegramReply(messageId, "❌ У вас нет прав для использования этой команды");
                return;
            }
        }

        // Проверяем наличие аргумента если нужно
        if (args.isEmpty() && cmd.command.contains("%player%")) {
            sendTelegramReply(messageId, "❌ Укажите ник игрока: /" + commandName + " ник_игрока");
            return;
        }

        // Проверяем кулдаун
        String cooldownKey = userId + ":" + commandName;
        if (isOnCooldown(cooldownKey, cmd.cooldown)) {
            long timeLeft = getCooldownLeft(cooldownKey, cmd.cooldown);
            String timeStr = formatCooldown(timeLeft);

            String cooldownMsg = config.getString("messages.cooldown",
                    "⏳ %user%, вы уже использовали эту команду.\nСледующее использование через: %time%");
            cooldownMsg = cooldownMsg.replace("%user%", username).replace("%time%", timeStr);

            sendTelegramReply(messageId, cooldownMsg);
            return;
        }

        // Выполняем команду
        executeMinecraftCommand(cmd, args, userId, username, messageId);
    }

    private void sendHelpMessage(String messageId, String username) {
        StringBuilder help = new StringBuilder();

        String header = config.getString("messages.help-header", "📋 Доступные команды:\n\n");
        help.append(header);

        for (BotCommand cmd : commands.values()) {
            String cooldownStr = formatCooldown(cmd.cooldown);
            String descriptionPart = cmd.description.isEmpty() ? "" : cmd.description + " | ";

            String line = config.getString("messages.command-format", "• /%cmd% - %eho%Кулдаун: %cooldown%\n")
                    .replace("%cmd%", cmd.name)
                    .replace("%cooldown%", cooldownStr)
                    .replace("%eho%", descriptionPart);

            help.append(line);
        }

        String footer = config.getString("messages.help-footer", "\n💡 Просто напишите /команда в чат");
        help.append(footer);

        sendTelegramReply(messageId, help.toString());
    }

    private void executeMinecraftCommand(BotCommand cmd, String args, String userId,
                                         String username, String messageId) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    // Подготавливаем команду
                    String finalCommand = cmd.command
                            .replace("%player%", args)
                            .replace("%args%", args)
                            .replace("%user%", username)
                            .replace("%user_id%", userId);

                    logInfo("Выполняю команду от Telegram: " + finalCommand);

                    boolean success = false;

                    if (cmd.usePlayerAsSender && !args.isEmpty()) {
                        // Пытаемся выполнить команду от имени игрока
                        Player player = Bukkit.getPlayerExact(args);
                        if (player != null && player.isOnline()) {
                            success = player.performCommand(finalCommand);
                        } else {
                            success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                        }
                    } else if (cmd.runAsConsole) {
                        // Выполняем от имени консоли
                        success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                    } else {
                        // Выполняем от имени плагина
                        success = getServer().dispatchCommand(getServer().getConsoleSender(), finalCommand);
                    }

                    // Обновляем кулдаун если успешно
                    if (success) {
                        String cooldownKey = userId + ":" + cmd.name;
                        updateCooldown(cooldownKey);
                    }

                    // Отправляем ответ
                    String response = success ? cmd.message : cmd.errorMessage;
                    response = response.replace("%player%", args).replace("%user%", username);
                    sendTelegramReply(messageId, response);

                    // Логируем
                    String logMsg = String.format("[%s] %s (ID:%s) -> /%s %s -> %s",
                            new SimpleDateFormat("HH:mm:ss").format(new Date()),
                            username, userId, cmd.name, args, success ? "Успех" : "Ошибка");
                    logInfo(logMsg);

                } catch (Exception e) {
                    logWarning("Ошибка выполнения команды: " + e.getMessage());
                    sendTelegramReply(messageId, "❌ Внутренняя ошибка при выполнении команды");
                }
            }
        }.runTask(this);
    }

    // ========== Кулдаун система ==========

    private void loadCooldowns() {
        try {
            if (!usedFile.exists()) return;

            List<String> lines = Files.readAllLines(usedFile.toPath());
            for (String line : lines) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    try {
                        cooldowns.put(parts[0], Long.parseLong(parts[1]));
                    } catch (NumberFormatException e) {
                        // Пропускаем некорректные строки
                    }
                }
            }

            logInfo("Загружено " + cooldowns.size() + " кулдаунов");
        } catch (Exception e) {
            logWarning("Ошибка загрузки кулдаунов: " + e.getMessage());
        }
    }

    private void saveCooldowns() {
        try (PrintWriter pw = new PrintWriter(usedFile)) {
            for (Map.Entry<String, Long> entry : cooldowns.entrySet()) {
                pw.println(entry.getKey() + ":" + entry.getValue());
            }
        } catch (IOException e) {
            logWarning("Ошибка сохранения кулдаунов: " + e.getMessage());
        }
    }

    private boolean isOnCooldown(String key, long cooldownSeconds) {
        Long lastTime = cooldowns.get(key);
        if (lastTime == null) return false;

        long timePassed = (System.currentTimeMillis() - lastTime) / 1000;
        return timePassed < cooldownSeconds;
    }

    private long getCooldownLeft(String key, long cooldownSeconds) {
        Long lastTime = cooldowns.get(key);
        if (lastTime == null) return 0;

        long timePassed = (System.currentTimeMillis() - lastTime) / 1000;
        long timeLeft = cooldownSeconds - timePassed;
        return Math.max(0, timeLeft);
    }

    private void updateCooldown(String key) {
        cooldowns.put(key, System.currentTimeMillis());
    }

    private String formatCooldown(long seconds) {
        if (seconds <= 0) return "сейчас";

        long days = TimeUnit.SECONDS.toDays(seconds);
        long hours = TimeUnit.SECONDS.toHours(seconds) % 24;
        long minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60;

        if (days > 0) {
            return String.format("%dд %dч", days, hours);
        } else if (hours > 0) {
            return String.format("%dч %dм", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dм", minutes);
        } else {
            return String.format("%dс", seconds);
        }
    }

    private void startCleanupTimer() {
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupOldCooldowns();
            }
        }.runTaskTimer(this, 36000L, 36000L);
    }

    private void cleanupOldCooldowns() {
        long cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        int removed = 0;

        Iterator<Map.Entry<String, Long>> it = cooldowns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() < cutoffTime) {
                it.remove();
                removed++;
            }
        }

        if (removed > 0) {
            saveCooldowns();
            logInfo("Очищено " + removed + " старых кулдаунов");
        }
    }

    // ========== Отправка сообщений ==========

    private void sendTelegramMessage(String chatId, String text) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL("https://api.telegram.org/bot" + botToken + "/sendMessage");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);

                    String escapedText = text.replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n");

                    String requestBody = String.format(
                            "{\"chat_id\": \"%s\", \"text\": \"%s\", \"parse_mode\": \"HTML\"}",
                            chatId, escapedText
                    );

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(requestBody.getBytes());
                        os.flush();
                    }

                    conn.getResponseCode();
                } catch (Exception e) {
                    logWarning("Ошибка отправки Telegram сообщения: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(this);
    }

    private void sendTelegramReply(String messageId, String text) {
        sendTelegramMessage(groupChatId, text);
    }

    // ========== Команды плагина ==========

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("telegrambot")) {
            if (args.length == 0) {
                showStatus(sender);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "reload":
                    if (!sender.hasPermission("telegrambot.admin")) {
                        sender.sendMessage("§cНет прав на перезагрузку");
                        return true;
                    }
                    reloadConfig();
                    config = getConfig();
                    loadCommands();
                    sender.sendMessage("§aКонфиг перезагружен! Загружено команд: " + commands.size());
                    break;

                case "status":
                    showStatus(sender);
                    break;

                case "debug":
                    if (!sender.hasPermission("telegrambot.admin")) {
                        sender.sendMessage("§cНет прав");
                        return true;
                    }
                    debugMode = !debugMode;
                    sender.sendMessage("§eDebug режим: " + (debugMode ? "§aВключен" : "§cВыключен"));
                    break;

                case "test":
                    if (!sender.hasPermission("telegrambot.admin")) {
                        sender.sendMessage("§cНет прав");
                        return true;
                    }
                    if (telegramBotEnabled) {
                        sender.sendMessage("§aОтправляю тестовое сообщение в Telegram...");
                        sendTelegramMessage(groupChatId, "✅ Тестовое сообщение от сервера Minecraft!");
                        sender.sendMessage("§aСообщение отправлено");
                    } else {
                        sender.sendMessage("§cTelegram бот выключен");
                    }
                    break;

                case "list":
                    sender.sendMessage("§6╔══════════════════════════════════╗");
                    sender.sendMessage("§6║      Загруженные команды        §6║");
                    sender.sendMessage("§6╠══════════════════════════════════╣");
                    for (BotCommand botCmd : commands.values()) {
                        String cooldownStr = formatCooldown(botCmd.cooldown);
                        String execType = botCmd.usePlayerAsSender ? "игрок" :
                                botCmd.runAsConsole ? "консоль" : "плагин";
                        String perm = botCmd.permission.isEmpty() ? "нет" : botCmd.permission;
                        sender.sendMessage(String.format("§e/%s §7-> §f%s",
                                botCmd.name, botCmd.command));
                        sender.sendMessage(String.format("  §7Кулдаун: §f%s §7| Исполнитель: §f%s §7| Права: §f%s",
                                cooldownStr, execType, perm));
                    }
                    sender.sendMessage("§6╚══════════════════════════════════╝");
                    break;

                case "execute":
                    if (!sender.hasPermission("telegrambot.admin")) {
                        sender.sendMessage("§cНет прав");
                        return true;
                    }
                    if (args.length < 2) {
                        sender.sendMessage("§cИспользование: /telegrambot execute <команда>");
                        return true;
                    }
                    String command = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    sender.sendMessage("§aВыполняю команду: " + command);
                    boolean result = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    sender.sendMessage(result ? "§aКоманда выполнена успешно" : "§cОшибка выполнения команды");
                    break;

                case "addperm":
                    if (!sender.hasPermission("telegrambot.admin")) {
                        sender.sendMessage("§cНет прав");
                        return true;
                    }
                    if (args.length < 3) {
                        sender.sendMessage("§cИспользование: /telegrambot addperm <user_id> <permission>");
                        sender.sendMessage("§7Пример: /telegrambot addperm 123456789 telegrambot.restart");
                        return true;
                    }
                    String targetUserId = args[1];
                    String permission = args[2];

                    Set<String> perms = userPermissions.getOrDefault(targetUserId, new HashSet<>());
                    perms.add(permission);
                    userPermissions.put(targetUserId, perms);
                    savePermissions();

                    sender.sendMessage("§aРазрешение " + permission + " выдано пользователю ID: " + targetUserId);
                    break;

                case "removeperm":
                    if (!sender.hasPermission("telegrambot.admin")) {
                        sender.sendMessage("§cНет прав");
                        return true;
                    }
                    if (args.length < 3) {
                        sender.sendMessage("§cИспользование: /telegrambot removeperm <user_id> <permission>");
                        return true;
                    }
                    String removeUserId = args[1];
                    String removePermission = args[2];

                    Set<String> userPerms = userPermissions.get(removeUserId);
                    if (userPerms != null) {
                        userPerms.remove(removePermission);
                        if (userPerms.isEmpty()) {
                            userPermissions.remove(removeUserId);
                        }
                        savePermissions();
                        sender.sendMessage("§aРазрешение " + removePermission + " удалено у пользователя ID: " + removeUserId);
                    } else {
                        sender.sendMessage("§cУ пользователя нет разрешений");
                    }
                    break;

                case "listperms":
                    if (!sender.hasPermission("telegrambot.admin")) {
                        sender.sendMessage("§cНет прав");
                        return true;
                    }
                    sender.sendMessage("§6╔══════════════════════════════════╗");
                    sender.sendMessage("§6║        Пользовательские права   §6║");
                    sender.sendMessage("§6╠══════════════════════════════════╣");
                    for (Map.Entry<String, Set<String>> entry : userPermissions.entrySet()) {
                        sender.sendMessage("§eID: §f" + entry.getKey());
                        sender.sendMessage("§7Права: §f" + String.join(", ", entry.getValue()));
                    }
                    sender.sendMessage("§6╚══════════════════════════════════╝");
                    break;

                default:
                    sender.sendMessage("§cНеизвестная подкоманда. Доступно: reload, status, debug, test, list, execute, addperm, removeperm, listperms");
                    break;
            }
            return true;
        }
        return false;
    }

    private void showStatus(CommandSender sender) {
        sender.sendMessage("§6╔══════════════════════════════════╗");
        sender.sendMessage("§6║     Universal Telegram Bot      §6║");
        sender.sendMessage("§6╠══════════════════════════════════╣");
        sender.sendMessage("§eTelegram бот: §f" + (telegramBotEnabled ? "§aВключен" : "§cВыключен"));
        sender.sendMessage("§eDebug режим: §f" + (debugMode ? "§aВключен" : "§cВыключен"));
        sender.sendMessage("§eЗагружено команд: §f" + commands.size());
        sender.sendMessage("§eАктивных кулдаунов: §f" + cooldowns.size());
        sender.sendMessage("§eПользователей с правами: §f" + userPermissions.size());
        sender.sendMessage("§eИнтервал проверки: §f" + checkInterval + " сек");
        sender.sendMessage("§6╚══════════════════════════════════╝");
        sender.sendMessage("§7Используйте: §f/telegrambot reload|status|debug|test|list|execute|addperm|removeperm|listperms");
    }

    // ========== Утилиты логгирования ==========

    private void logInfo(String message) {
        getLogger().info(message);
    }

    private void logWarning(String message) {
        getLogger().warning(message);
    }

    private void logSevere(String message) {
        getLogger().severe(message);
    }

    // ========== Класс команды ==========

    private static class BotCommand {
        String name;
        String command;
        long cooldown;
        String permission;
        String message;
        String errorMessage;
        boolean runAsConsole = true;
        boolean usePlayerAsSender = false;
        String description = "";
    }
}