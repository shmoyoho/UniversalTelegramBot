# 📱 TELEGRAM BOT FOR MINECRAFT - SETUP GUIDE

## 🎯 WHAT IS THIS?
A plugin that allows you to manage your Minecraft server through Telegram. Players can send commands to a Telegram group, and the bot will execute them on the server.

## 🚀 QUICK INSTALLATION (5 MINUTES)
- Step 1: Create a bot in Telegram
- Open Telegram
- Find @BotFather
- Send /newbot
- Choose a bot name (e.g., MyServerBot)
- Get the token (save it!)
  
```
Token looks like this: 1234567890:ABCdefGHIjklMNOpqrsTUVwxyz
```
1. Step 2: Add bot to group
1. Create a new Telegram group
1. Add your bot to the group
1. Make the bot an administrator
- Get the group ID:
- Add @getidsbot to the group
- Send /start
- Copy the group ID (starts with -100)

## Step 3: Install plugin on server
- Download UniversalTelegramBot.jar file
- Place it in your server's plugins/ folder
- Restart the server

## Step 4: Configure settings
After first launch, the file plugins/TelegramBot/config.yml will appear

Edit it:

```
telegram:
  enabled: true
  bot-token: "PASTE_YOUR_TOKEN_HERE"
  group-chat-id: "PASTE_YOUR_GROUP_ID_HERE"
```

## Step 5: Reload the plugin
In-game, execute:

```
/telegrambot reload
```
## ⚙️ IN-GAME MANAGEMENT COMMANDS
Basic commands:

```
/telegrambot status          - Check bot status
/telegrambot reload          - Reload configuration
/telegrambot test            - Send test message
/telegrambot list            - Show all commands
/telegrambot debug           - Toggle debug mode
```

Permission management:

```
/telegrambot addperm <ID> <permission>     - Grant permission to user
/telegrambot removeperm <ID> <permission>  - Remove permission
/telegrambot listperms                     - List all permissions
```
Example of granting permission:
```
/telegrambot addperm (id)123456789 telegrambot.restart
```
## 💬 TELEGRAM COMMANDS
For all players (if configured):
```
/help          - Show command list
/fly <nick>    - Grant 1 hour of fly
/money <nick>  - Give money
/kit <nick>    - Give kit
/online        - Check online players
```
For admins (require permissions):
```
/kick <nick>   - Kick player
/ban <nick>    - Ban player
/restart       - Restart server
/give <nick>   - Give items
```

## 🔧 CONFIGURING COMMANDS
Command example:
```
commands:
  fly:
    command: "lp user %player% permission settemp essentials.fly true 60m"
    cooldown: 86400                    # 24 hours in seconds
    eho: "Fly for 1 hour | "           # Description in /help
    message: "✅ Fly granted!"          # Success message
    error-message: "❌ Error"           # Error message
    permission: ""                     # Required permission (empty = for everyone)
    run-as-console: true               # Execute as console
 ```

 Command parameters:
- command - command to execute on server
- cooldown - cooldown between uses (seconds)
- eho - description in /help menu
- message - bot response on success
- error-message - response on error
- permission - required permission (leave empty for everyone)
- run-as-console - execute as console (better to keep true)

PLACEHOLDERS FOR COMMANDS
Use in ```command``` field:
```
%player%    - Player nickname from Telegram command
%args%      - All arguments after command
%user%      - Telegram username
%user_id%   - Telegram user ID
```
Example:
```
command: "eco give %player% 1000"      # Will give 1000 money to player
command: "msg %player% Hello from %user%"  # Will send message
```

## 🔐 PERMISSION SYSTEM
1. How to get user ID:
- Ask user to send any message to group
- Check server logs - their ID will be there
- Or use @getidsbot

2. Granting permissions:
```
/telegrambot addperm 123456789 telegrambot.restart
/telegrambot addperm 123456789 telegrambot.ban
```
3. Standard permissions:
- ```telegrambot.restart``` - restart permission
- ```telegrambot.ban``` - ban permission
- ```telegrambot.kick``` - kick permission
- ```telegrambot.admin``` - all permissions (for operators)

## 🛠️ TROUBLESHOOTING
1. Bot not responding:
- Check token: /telegrambot status
- Check group ID
- Ensure bot is group administrator
- Enable debug: /telegrambot debug
2. Commands not executing:
- Check server logs
- Ensure required plugins are installed
- Check command syntax in config

Console errors:
```
[TelegramBot] ERROR: ... - check config.yml
[TelegramBot] WARNING: ... - usually not critical
```
Useful diagnostic commands:
```
/telegrambot execute say Test command  # Test execution
/telegrambot debug                     # Enable detailed logs
/telegrambot reload                    # Reload settings
```
## 🎉 USEFUL TIPS
1. For beginners:
- Start with one command /fly
- Test in a small group
- Gradually add new commands
2. For administrators:
- Create separate group for commands
- Assign responsible people for permissions
- Regularly check logs
3. For players:
- Use format: /command player_nickname
- Respect cooldowns
- Use /help for command list

Done! Now players can manage the server through Telegram. Start with simple commands and gradually expand functionality. 🎮
