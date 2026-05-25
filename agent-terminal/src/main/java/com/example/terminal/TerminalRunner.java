package com.example.terminal;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

/**
 * Interactive read-eval-print loop. Prints a welcome banner, then reads prompts from stdin and
 * streams each answer from the agent server in real time — much like the Claude Code terminal.
 *
 * <p>Built-in commands: {@code /help}, {@code /model [name]}, and {@code /exit} (also {@code /quit},
 * {@code exit}, {@code quit}, or Ctrl-D).
 */
@Component
public class TerminalRunner implements CommandLineRunner {

    private static final String RESET = "[0m";
    private static final String BOLD = "[1m";
    private static final String CYAN = "[36m";
    private static final String GREEN = "[32m";
    private static final String YELLOW = "[33m";
    private static final String GRAY = "[90m";

    private final AgentClient client;
    private final TerminalProperties properties;

    private String model;

    public TerminalRunner(AgentClient client, TerminalProperties properties) {
        this.client = client;
        this.properties = properties;
        this.model = blankToNull(properties.model());
    }

    @Override
    public void run(String... args) throws Exception {
        printBanner();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        while (true) {
            System.out.print("\n" + CYAN + BOLD + "❯ " + RESET);
            System.out.flush();

            String line = in.readLine();
            if (line == null) {
                break; // EOF / Ctrl-D
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (isExitCommand(line)) {
                break;
            }
            if (line.equals("/help")) {
                printHelp();
                continue;
            }
            if (line.equals("/model") || line.startsWith("/model ")) {
                handleModelCommand(line);
                continue;
            }
            ask(line);
        }

        System.out.println("\n" + GRAY + "Goodbye." + RESET);
    }

    private void ask(String prompt) {
        System.out.println();
        StreamPrinter printer = new StreamPrinter();
        try {
            client.streamChat(prompt, model, printer);
        } catch (ConnectException e) {
            System.out.println(YELLOW + "Cannot reach the agent server at " + properties.serverUrl()
                    + ".\nStart it with:  mvn -pl mcp-integration spring-boot:run" + RESET);
        } catch (IOException e) {
            System.out.println(YELLOW + "Connection error: " + e.getMessage() + RESET);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println(YELLOW + "Interrupted." + RESET);
        }
        printer.finishLine();
    }

    /** Renders streamed events, tracking whether output is mid-line so separators look right. */
    private static final class StreamPrinter implements AgentClient.Handler {
        private boolean atLineStart = true;

        @Override
        public void onToken(String text) {
            if (text.isEmpty()) {
                return;
            }
            System.out.print(text);
            System.out.flush();
            atLineStart = text.endsWith("\n");
        }

        @Override
        public void onToolCall(String name, String args) {
            newlineIfNeeded();
            System.out.println(GRAY + "  ⚙ " + name + "(" + truncate(args) + ")" + RESET);
            atLineStart = true;
        }

        @Override
        public void onError(String message) {
            newlineIfNeeded();
            System.out.println(YELLOW + "  ! " + message + RESET);
            atLineStart = true;
        }

        void finishLine() {
            if (!atLineStart) {
                System.out.println();
            }
        }

        private void newlineIfNeeded() {
            if (!atLineStart) {
                System.out.println();
                atLineStart = true;
            }
        }

        private static String truncate(String s) {
            String oneLine = s == null ? "" : s.replaceAll("\\s+", " ").strip();
            return oneLine.length() > 120 ? oneLine.substring(0, 117) + "..." : oneLine;
        }
    }

    private void handleModelCommand(String line) {
        String arg = line.length() > "/model".length() ? line.substring("/model".length()).strip() : "";
        if (arg.isEmpty()) {
            System.out.println(GRAY + "Model: " + (model == null ? "(server default)" : model) + RESET);
        } else {
            model = arg;
            System.out.println(GRAY + "Model set to " + model + RESET);
        }
    }

    private boolean isExitCommand(String line) {
        return line.equals("/exit") || line.equals("/quit") || line.equals("exit") || line.equals("quit");
    }

    private void printBanner() {
        System.out.println();
        System.out.println(CYAN + BOLD + "  ┌─────────────────────────────────────────┐" + RESET);
        System.out.println(CYAN + BOLD + "  │   Agent Terminal                          │" + RESET);
        System.out.println(CYAN + BOLD + "  └─────────────────────────────────────────┘" + RESET);
        System.out.println(GRAY + "  Connected to " + properties.serverUrl() + RESET);
        System.out.println(GRAY + "  Model: " + (model == null ? "(server default)" : model) + RESET);
        System.out.println(GRAY + "  Type a prompt and press Enter. " + RESET);
        System.out.println(GRAY + "  Commands: " + GREEN + "/help" + GRAY + "  " + GREEN + "/model [name]"
                + GRAY + "  " + GREEN + "/exit" + RESET);
    }

    private void printHelp() {
        System.out.println();
        System.out.println(BOLD + "Commands" + RESET);
        System.out.println("  " + GREEN + "/help" + RESET + "          Show this help");
        System.out.println("  " + GREEN + "/model" + RESET + "         Show the current model");
        System.out.println("  " + GREEN + "/model <name>" + RESET + "  Use a specific model for new prompts");
        System.out.println("  " + GREEN + "/exit" + RESET + "          Quit (also /quit, exit, quit, Ctrl-D)");
        System.out.println();
        System.out.println(GRAY + "Anything else is sent to the agent; the answer streams in live." + RESET);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
