package pda.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Plain-Java queries over data/. No AI here. In a WORKFLOW, your code gathers the facts
 * deterministically and hands them to the model in the prompt; in an AGENT (Day 1), the
 * model fetches facts itself through tools.
 */
public final class Catalog {

    private static final Path DATA = Claude.ROOT.resolve("data");
    private static final Map<String, JsonNode> SERVICES = load();

    private Catalog() {}

    public static Map<String, JsonNode> services() { return SERVICES; }

    public static JsonNode service(String name) { return SERVICES.get(name); }

    /** One line per service: name, team, runtime, tier. Small enough to put in any prompt. */
    public static ArrayNode summary() {
        ArrayNode out = Claude.JSON.createArrayNode();
        for (JsonNode s : SERVICES.values()) {
            out.addObject().put("name", s.get("name").asText()).put("team", s.get("team").asText())
                    .put("runtime", s.get("runtime").asText()).put("tier", s.get("tier").asInt());
        }
        return out;
    }

    /** Services that call the given service (who is hurt if it breaks). */
    public static List<String> callersOf(String name) {
        List<String> callers = new ArrayList<>();
        for (JsonNode s : SERVICES.values()) {
            for (JsonNode c : s.get("calls")) if (c.asText().equals(name)) callers.add(s.get("name").asText());
        }
        return callers;
    }

    /** Every service using a library whose name contains `fragment`, with version, advisory and callers. */
    public static ArrayNode servicesUsingLibrary(String fragment) {
        ArrayNode out = Claude.JSON.createArrayNode();
        for (JsonNode s : SERVICES.values()) {
            for (JsonNode lib : s.get("libraries")) {
                if (lib.get("name").asText().contains(fragment)) {
                    ObjectNode row = out.addObject();
                    row.put("service", s.get("name").asText());
                    row.put("team", s.get("team").asText());
                    row.put("tier", s.get("tier").asInt());
                    row.put("runtime", s.get("runtime").asText());
                    row.put("library", lib.get("name").asText());
                    row.put("version", lib.get("version").asText());
                    if (lib.has("known_advisory")) row.put("known_advisory", lib.get("known_advisory").asText());
                    row.putPOJO("called_by", callersOf(s.get("name").asText()));
                }
            }
        }
        return out;
    }

    /** Every library with a known advisory, across all services. */
    public static ArrayNode advisories() {
        ArrayNode out = Claude.JSON.createArrayNode();
        for (JsonNode s : SERVICES.values()) {
            for (JsonNode lib : s.get("libraries")) {
                if (lib.has("known_advisory")) {
                    out.addObject().put("service", s.get("name").asText()).put("tier", s.get("tier").asInt())
                            .put("library", lib.get("name").asText()).put("version", lib.get("version").asText())
                            .put("advisory", lib.get("known_advisory").asText());
                }
            }
        }
        return out;
    }

    /** Every event schema version, plus which services consume which version. */
    public static ObjectNode schemas() {
        ObjectNode out = Claude.JSON.createObjectNode();
        try (Stream<Path> dirs = Files.list(DATA.resolve("schemas")).sorted()) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                ObjectNode event = out.putObject(dir.getFileName().toString());
                ArrayNode versions = event.putArray("versions");
                try (Stream<Path> files = Files.list(dir).sorted()) {
                    for (Path f : (Iterable<Path>) files::iterator) versions.add(Claude.JSON.readTree(f.toFile()));
                }
                ObjectNode consumers = event.putObject("consumers");
                for (JsonNode s : SERVICES.values()) {
                    for (JsonNode c : s.get("consumes")) {
                        if (c.get("event").asText().equals(dir.getFileName().toString())) {
                            consumers.put(s.get("name").asText(), "v" + c.get("schema_version").asInt());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read schemas", e);
        }
        return out;
    }

    private static Map<String, JsonNode> load() {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(DATA.resolve("services")).filter(p -> p.toString().endsWith(".json")).sorted()) {
            for (Path f : (Iterable<Path>) files::iterator) {
                JsonNode s = Claude.JSON.readTree(f.toFile());
                map.put(s.get("name").asText(), s);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read data/services", e);
        }
        return map;
    }
}
