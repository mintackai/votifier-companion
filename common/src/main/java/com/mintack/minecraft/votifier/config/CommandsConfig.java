package com.mintack.minecraft.votifier.config;

import java.util.HashMap;

/**
 * Primary command names and aliases stored in {@code config/votifier_python_companion/commands.json}.
 */
public class CommandsConfig {
    private static CommandsConfig instance;
    private HashMap<String, Command> commands = new HashMap<>();

    public static CommandsConfig getInstance() {
        if (instance == null) {
            instance = new CommandsConfig();
        }
        return instance;
    }

    static void setInstance(CommandsConfig instance) {
        CommandsConfig.instance = instance;
    }

    private CommandsConfig() {
        addDefaults(commands);
    }

    private static void addDefaults(HashMap<String, Command> target) {
        target.put("reward", new Command("reward"));
    }

    public Command getCommand(String command) {
        return commands.get(command);
    }

    /**
     * Drops entries left null or unnamed so built-in defaults can repopulate them.
     */
    void removeNullEntries() {
        if (commands == null) {
            commands = new HashMap<>();
        }
        commands.values().removeIf(command -> command == null || command.primaryCommand == null || command.primaryCommand.isBlank());
    }

    /**
     * Checks whether the loaded config is missing any built-in command entries.
     *
     * @return {@code true} if at least one default command key is missing
     */
    boolean hasMissingCommands() {
        HashMap<String, Command> defaults = new HashMap<>();
        addDefaults(defaults);
        return !commands.keySet().containsAll(defaults.keySet());
    }

    /**
     * Inserts missing default command entries while preserving existing admin overrides.
     */
    void addMissingCommands() {
        HashMap<String, Command> defaults = new HashMap<>();
        addDefaults(defaults);
        defaults.forEach(commands::putIfAbsent);
    }

    /**
     * Command registration entry specifying primary literal, aliases, and enable toggle.
     */
    public static class Command {
        private String primaryCommand;
        private String[] aliases = {};
        private boolean commandEnabled = true;

        // Used by Gson so omitted fields preserve defaults.
        private Command() {
        }

        Command(String primaryCommand, String... aliases) {
            this.primaryCommand = primaryCommand;
            this.aliases = aliases;
        }

        public String[] getAliases() {
            return aliases == null ? new String[0] : aliases.clone();
        }

        public String getPrimaryCommand() {
            return primaryCommand;
        }

        public boolean isEnabled() {
            return commandEnabled;
        }
    }
}
