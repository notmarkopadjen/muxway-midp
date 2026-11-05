package muxway;

/**
 * The command row of the other clients, as a list: Claude Code's slash
 * commands and a shell's starters, the strings the web's commands.ts
 * has. A pick is sent with Enter. MIDP 2.0 build only: Ui.COMMANDS is
 * false in the 1.0 build and ProGuard drops this class.
 */
final class Commands {
    static final String[] CLAUDE = {
        "/clear", "/exit", "/compact", "/rename", "/model", "/resume", "/usage", "/context", "/help", "/status",
        "/cost", "/todos", "/plan", "/review", "/memory", "/init", "/config", "/permissions", "/mcp", "/agents",
        "/hooks", "/doctor", "/fast", "/export",
    };

    static final String[] SHELL = {
        "exit", "htop", "claude", "claude -c", "claude --resume", "codex", "agy", "gemini", "git status", "git diff",
        "git log --oneline -20", "git pull", "git push", "git stash", "git stash pop", "make", "make test", "npm test",
        "npm run build", "docker ps", "tmux ls", "ls", "ls -la", "pwd", "cd ..", "clear", "history", "df -h", "free -h",
    };

    private static final String[] SHELLS = {"bash", "zsh", "fish", "sh", "dash", "ash", "ksh", "tcsh", "csh"};

    private Commands() {
    }

    /** The list for the program in the pane, or the lens it is viewed with; null when it has none. */
    static String[] of(String command, boolean claudeLens) {
        if (command.equals("claude") || claudeLens) {
            return CLAUDE;
        }
        for (int i = 0; i < SHELLS.length; i++) {
            if (command.equals(SHELLS[i])) {
                return SHELL;
            }
        }
        return null;
    }
}
