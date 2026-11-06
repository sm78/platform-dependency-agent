package pda.day01;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.stream.Stream;

/**
 * Day 1: an AI agent with no framework, just the raw tool-use loop, in plain Java.
 *
 * Run from the java/ folder:
 *   mvn -q compile exec:java
 *   mvn -q compile exec:java -Dexec.args="Which services does the payments team own?"
 *
 * What happens (this loop is the heart of every agent framework):
 *   1. We send Claude the question + a description of our tools.
 *   2. Claude either answers, or replies "please run tool X with inputs Y" (stop_reason == "tool_use").
 *   3. OUR code runs the tool (Claude never runs anything itself) and sends back the result.
 *   4. Repeat until Claude answers in plain text.
 *
 * We call the Messages API directly over HTTP so you can see the exact JSON going back and forth.
 * (Anthropic also publishes an official Java SDK, com.anthropic:anthropic-java, which wraps this.)
 */
public class ToolLoop {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final Path ROOT = findProjectRoot();
    private static final Path SERVICES_DIR = ROOT.resolve("data/services");
    private static final Map<String, String> ENV = loadEnv(ROOT.resolve(".env"));
    private static final String MODEL = ENV.getOrDefault("CLAUDE_MODEL", "claude-sonnet-5-5");
    private static final String API_KEY = ENV.get("ANTHROPIC_API_KEY");
    private static final String API_URL = ENV.getOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com") + "/v1/messages";

    private static final String SYSTEM = "You are a platform engineering assistant. Answer questions about the "
            + "company's services using the tools. Never guess service names or versions: look them up. Be concise.";

    // -----------------------------------------------------------------------
    // 1. THE TOOLS: ordinary Java methods. Nothing AI-specific here.
    // -----------------------------------------------------------------------

    /** Every service (optionally only one team's) with its team, runtime and tier. */
    static JsonNode listServices(JsonNode input) throws IOException {
        String team = input.hasNonNull("team") ? input.get("team").asText() : null;
        ArrayNode result = JSON.createArrayNode();
        try (Stream<Path> files = Files.list(SERVICES_DIR).filter(p -> p.toString().endsWith(".json")).sorted()) {
            for (Path f : (Iterable<Path>) files::iterator) {
                JsonNode s = JSON.readTree(f.toFile());
                if (team == null || team.equals(s.get("team").asText())) {
                    ObjectNode row = result.addObject();
                    row.set("name", s.get("name"));
                    row.set("team", s.get("team"));
                    row.set("runtime", s.get("runtime"));
                    row.set("tier", s.get("tier"));
                }
            }
        }
        return result;
    }

    /** What one service depends on: services it calls, libraries, and events. */
    static JsonNode getDependencies(JsonNode input) throws IOException {
        String service = input.path("service").asText();
        Path path = SERVICES_DIR.resolve(service + ".json");
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Unknown service '" + service + "'. Call list_services to see valid names.");
        }
        JsonNode s = JSON.readTree(path.toFile());
        ObjectNode result = JSON.createObjectNode();
        result.set("service", s.get("name"));
        result.set("calls", s.get("calls"));
        result.set("libraries", s.get("libraries"));          // entries may include "known_advisory"
        result.set("publishes_events", s.get("publishes"));
        result.set("consumes_events", s.get("consumes"));
        return result;
    }

    // -----------------------------------------------------------------------
    // 2. THE TOOL DESCRIPTIONS: what Claude sees. Claude decides which tool to call
    //    purely from these names, descriptions and input schemas (JSON Schema),
    //    so write them like documentation for a new colleague.
    // -----------------------------------------------------------------------
    private static final String TOOLS_JSON = """
        [
          {
            "name": "list_services",
            "description": "List the services in the company's service catalog, with owning team, runtime (language and version) and criticality tier (1 = most critical). Use it to discover valid service names or to find services by team.",
            "input_schema": {
              "type": "object",
              "properties": {
                "team": {"type": "string", "description": "Optional team filter, e.g. 'payments', 'cards', 'platform'."}
              },
              "required": []
            }
          },
          {
            "name": "get_dependencies",
            "description": "Get one service's dependencies: the services it calls synchronously, its third-party libraries with versions (a 'known_advisory' field means a known vulnerability), and the events it publishes and consumes with schema versions.",
            "input_schema": {
              "type": "object",
              "properties": {
                "service": {"type": "string", "description": "Exact service name, e.g. 'payment-orchestrator'."}
              },
              "required": ["service"]
            }
          }
        ]
        """;

    /** A tool implementation: takes Claude's JSON input, returns a JSON result. */
    @FunctionalInterface
    interface Tool { JsonNode run(JsonNode input) throws Exception; }

    /** Maps the name Claude asks for -> the Java method we run. */
    private static final Map<String, Tool> HANDLERS = Map.of(
            "list_services", ToolLoop::listServices,
            "get_dependencies", ToolLoop::getDependencies);

    /** Run the tool Claude asked for. Errors go BACK to Claude (is_error=true) so it can recover. */
    static ToolResult runTool(String name, JsonNode input) {
        try {
            Tool tool = HANDLERS.get(name);
            if (tool == null) throw new IllegalArgumentException("No such tool: " + name);
            return new ToolResult(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tool.run(input)), false);
        } catch (Exception e) {
            return new ToolResult("Error: " + e.getMessage(), true);
        }
    }

    record ToolResult(String content, boolean isError) {}

    // -----------------------------------------------------------------------
    // 3. THE AGENT LOOP
    // -----------------------------------------------------------------------
    static String ask(String question, int maxTurns) throws Exception {
        ArrayNode tools = (ArrayNode) JSON.readTree(TOOLS_JSON);
        ArrayNode messages = JSON.createArrayNode();      // the whole conversation, resent every turn
        messages.addObject().put("role", "user").put("content", question);
        long totalIn = 0, totalOut = 0;

        for (int turn = 1; turn <= maxTurns; turn++) {
            ObjectNode request = JSON.createObjectNode();
            request.put("model", MODEL);
            request.put("max_tokens", 1024);
            request.put("system", SYSTEM);
            request.set("tools", tools);
            request.set("messages", messages);

            JsonNode response = callClaude(request);
            String stopReason = response.path("stop_reason").asText();
            long in = response.path("usage").path("input_tokens").asLong();
            long out = response.path("usage").path("output_tokens").asLong();
            totalIn += in;
            totalOut += out;
            System.out.printf("%n--- Turn %d: stop_reason=%s (tokens in=%d, out=%d)%n", turn, stopReason, in, out);

            // Claude's reply (text and/or tool requests) must go into the history as-is.
            JsonNode content = response.get("content");
            messages.addObject().put("role", "assistant").set("content", content);

            if (!"tool_use".equals(stopReason)) {           // no more tools wanted: this is the answer
                StringBuilder answer = new StringBuilder();
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) answer.append(block.path("text").asText());
                }
                System.out.printf("%nTotal tokens: %d in, %d out%n", totalIn, totalOut);
                return answer.toString();
            }

            // Claude may ask for SEVERAL tools in one turn (parallel tool use): run them all.
            ArrayNode toolResults = JSON.createArrayNode();
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("text".equals(type) && !block.path("text").asText().isBlank()) {
                    System.out.println("Claude says: " + block.path("text").asText().strip());
                } else if ("tool_use".equals(type)) {
                    String name = block.path("name").asText();
                    JsonNode input = block.path("input");
                    System.out.println("Claude calls: " + name + "(" + input + ")");
                    ToolResult result = runTool(name, input);
                    String preview = result.content().length() < 300 ? result.content() : result.content().substring(0, 300) + " ...";
                    System.out.println("  -> " + (result.isError() ? "ERROR " : "") + preview);

                    ObjectNode tr = toolResults.addObject();
                    tr.put("type", "tool_result");
                    tr.put("tool_use_id", block.path("id").asText());   // ties this result to the request it answers
                    tr.put("content", result.content());
                    tr.put("is_error", result.isError());
                }
            }

            // Tool results go back as a USER message, then we loop.
            messages.addObject().put("role", "user").set("content", toolResults);
        }
        return "Stopped: reached maxTurns without a final answer.";
    }

    /** One HTTP POST to the Messages API. */
    static JsonNode callClaude(ObjectNode body) throws IOException, InterruptedException {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY not found in .env or the environment");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL))
                .header("x-api-key", API_KEY)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Claude API returned HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return JSON.readTree(resp.body());
    }

    // -----------------------------------------------------------------------
    // Plumbing: find the project folder and read .env (environment variables win).
    // -----------------------------------------------------------------------
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
        System.getenv().forEach(env::put);
        return env;
    }

    public static void main(String[] args) throws Exception {
        String q = args.length > 0 ? String.join(" ", args)
                : "What does payment-orchestrator depend on, and which of those dependencies "
                  + "(including the services it calls) have libraries with known security advisories?";
        System.out.println("Question: " + q);
        String answer = ask(q, 10);
        System.out.println("\nAnswer:\n" + answer);
    }
}