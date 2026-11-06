package pda.day02;

import com.fasterxml.jackson.databind.JsonNode;
import pda.common.Catalog;
import pda.common.Claude;

import java.util.HashSet;
import java.util.Set;

/**
 * Pattern 1: PROMPT CHAINING. A fixed sequence of steps; each step's output feeds the next,
 * with a "gate" (a plain code check) between steps so bad output stops the chain.
 *
 *   [code] gather facts -> [LLM] assess risk as JSON -> [code] GATE: validate -> [LLM] write brief
 *
 * Use it when the task splits cleanly into known steps. Each call does one simple job,
 * which is more accurate than one giant prompt, and you can test each step separately.
 *
 * Run from java/:
 *   mvn -q compile exec:java -Dexec.mainClass=pda.day02.PromptChaining
 *   mvn -q compile exec:java -Dexec.mainClass=pda.day02.PromptChaining -Dexec.args="jackson-databind"
 */
public class PromptChaining {

    public static void main(String[] args) {
        String library = args.length > 0 ? args[0] : "log4j-core";

        // Step 1 (code, no AI): gather the facts deterministically. Cheap, fast, always correct.
        JsonNode affected = Catalog.servicesUsingLibrary(library);
        System.out.println("STEP 1 (code): " + affected.size() + " services use '" + library + "'");
        if (affected.isEmpty()) {
            System.out.println("Nothing to assess.");
            return;
        }

        // Step 2 (LLM): one narrow job, assess each service's risk, as structured JSON.
        JsonNode assessment = Claude.askJson(Claude.MODEL,
                "You are a security and reliability analyst at a bank. For each service, rate the risk of "
                + "staying on its current library version as high, medium or low, considering: known advisories, "
                + "service tier (1 = most critical), and how many services call it.\n"
                + "Return a JSON array, one object per service: "
                + "{\"service\": string, \"version\": string, \"risk\": \"high\"|\"medium\"|\"low\", "
                + "\"reason\": string (max 20 words), \"action\": string (max 15 words)}.",
                "Library: " + library + "\nServices using it:\n" + Claude.pretty(affected), 2000);
        System.out.println("\nSTEP 2 (LLM): risk assessment\n" + Claude.pretty(assessment));

        // GATE (code): never trust a step's output blindly. Check it before the next step uses it.
        String problem = gate(affected, assessment);
        if (problem != null) {
            System.out.println("\nGATE FAILED: " + problem + "\nStopping the chain; fix the prompt or retry.");
            Claude.printUsage();
            return;
        }
        System.out.println("\nGATE (code): passed, every service assessed with a valid risk level");

        // Step 3 (LLM): a different job, write for a different audience, from the validated JSON only.
        String brief = Claude.ask(Claude.MODEL,
                "You write crisp briefs for a bank's CTO. Plain language, no jargon, under 150 words. "
                + "Lead with the decision needed, then the top risks, then the recommended order of work.",
                "Write an upgrade brief for library '" + library + "' from this assessment:\n" + Claude.pretty(assessment),
                800);
        System.out.println("\nSTEP 3 (LLM): CTO brief\n" + brief);
        Claude.printUsage();
    }

    /** Returns null if the assessment is usable, or a description of what is wrong. */
    static String gate(JsonNode affected, JsonNode assessment) {
        if (!assessment.isArray()) return "expected a JSON array";
        Set<String> expected = new HashSet<>(), seen = new HashSet<>();
        for (JsonNode a : affected) expected.add(a.get("service").asText());
        for (JsonNode a : assessment) {
            String risk = a.path("risk").asText();
            if (!Set.of("high", "medium", "low").contains(risk)) return "invalid risk '" + risk + "' for " + a.path("service").asText();
            seen.add(a.path("service").asText());
        }
        if (!seen.equals(expected)) return "services assessed " + seen + " do not match services affected " + expected;
        return null;
    }
}
