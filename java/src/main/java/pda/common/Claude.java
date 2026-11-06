package pda.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A tiny Claude client shared from Day 2 on: one method to send a prompt and get text back,
 * one to get JSON back, and token counters so every program can report what it cost.
 * (Day 1's ToolLoop has the same plumbing inline, on purpose, so it stays self-contained.)
 */
public final class Claude {

    public static final ObjectMapper JSON = new ObjectMapper();
    public static final Path ROOT = findProjectRoot();
    private static final Map<String, String> ENV = loadEnv(ROOT.resolve(".env"));

    /** The capable model: for reasoning, planning, writing. */
    public static final String MODEL = ENV.getOrDefault("CLAUDE_MODEL", "claude-sonnet-5-5");
    /** The small, fast, cheap model: for classifying and simple lookups. */
    public static final String SMALL_MODEL = ENV.getOrDefault("CLAUDE_SMALL_MODEL", "claude-haiku-5-5");

    private static final String API_KEY = ENV.get("ANTHROPIC_API_KEY");
    private static final String API_URL = ENV.getOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com") + "/v1/messages";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final AtomicLong CALLS = new AtomicLong(), TOKENS_IN = new AtomicLong(), TOKENS_OUT = new AtomicLong();

    private Claude() {}

    /** One model call: system prompt + one user message in, text out. Thread-safe. */
    public static String ask(String model, String system, String user, int maxTokens) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        body.putArray("messages").addObject().put("role", "user").put("content", user);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL))
                    .header("x-api-key", requireKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Claude API returned HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode r = JSON.readTree(resp.body());
            CALLS.incrementAndGet();
            TOKENS_IN.addAndGet(r.path("usage").path("input_tokens").asLong());
            TOKENS_OUT.addAndGet(r.path("usage").path("output_tokens").asLong());
            StringBuilder text = new StringBuilder();
            for (JsonNode block : r.path("content")) {
                if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
            }
            return text.toString().strip();
        } catch (IOException e) {
            throw new IllegalStateException("Call to Claude failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    /**
     * Same as ask(), but the reply must be JSON. Models sometimes wrap JSON in ```json fences,
     * so we strip those before parsing. If it still isn't JSON we fail loudly: in a workflow,
     * bad output from one step must not flow silently into the next.
     */
    public static JsonNode askJson(String model, String system, String user, int maxTokens) {
        String text = ask(model, system + "\n\nRespond with JSON only: no prose, no code fences.", user, maxTokens);
        String cleaned = text.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)\\s*```$", "").strip();
        try {
            return JSON.readTree(cleaned);
        } catch (IOException e) {
            throw new IllegalStateException("Model did not return valid JSON:\n" + text, e);
        }
    }

    public static String pretty(JsonNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            return node.toString();
        }
    }

    public static void printUsage() {
        System.out.printf("%n[usage] %d model calls, %d tokens in, %d tokens out%n",
                CALLS.get(), TOKENS_IN.get(), TOKENS_OUT.get());
    }

    private static String requireKey() {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY not found in .env or the environment");
        }
        return API_KEY;
    }

    private static Path findProjectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("data/services"))) dir = dir.getParent();
        if (dir == null) throw new IllegalStateException("Run this from inside the platform-dependency-agent folder");
        return dir;
    }

    private static Map<String, String> loadEnv(Path file) {
        Map<String, String> env = new HashMap<>();
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    line = line.strip();
                    int eq = line.indexOf('=');
                    if (line.isEmpty() || line.startsWith("#") || eq < 1) continue;
                    String value = line.substring(eq + 1).strip();
                    if (!value.isEmpty()) env.put(line.substring(0, eq).strip(), value);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
        System.getenv().forEach(env::put);   // real environment variables win over .env
        return env;
    }
}
