package pda.day02;

import com.fasterxml.jackson.databind.JsonNode;
import pda.common.Catalog;
import pda.common.Claude;

import java.util.List;

/**
 * Pattern 2: ROUTING. A cheap first call classifies the request; code then sends it to a
 * specialised handler, each with its own prompt, its own data and possibly its own model.
 *
 *   [small LLM] classify -> switch(route) -> security | schema | ownership (small model) | out of scope (no LLM)
 *
 * Use it when requests fall into distinct kinds that are best handled differently. Benefits:
 * each handler's prompt stays focused, easy questions go to a cheaper model, and requests you
 * don't support never reach an expensive call.
 *
 * Run from java/:
 *   mvn -q compile exec:java -Dexec.mainClass=pda.day02.Routing
 *   mvn -q compile exec:java -Dexec.mainClass=pda.day02.Routing -Dexec.args="Who owns the wire engine?"
 */
public class Routing {

    static final List<String> DEMO_QUESTIONS = List.of(
            "Which tier-1 services have critical vulnerabilities?",
            "Who still consumes the old version of account.opened?",
            "Which team owns fx-rates?",
            "Can you book me a meeting room for Friday?");

    public static void main(String[] args) {
        List<String> questions = args.length > 0 ? List.of(String.join(" ", args)) : DEMO_QUESTIONS;
        for (String q : questions) {
            System.out.println("\n==================================================\nQUESTION: " + q);
            System.out.println("ANSWER: " + handle(q));
        }
        Claude.printUsage();
    }

    static String handle(String question) {
        // Step 1: classify with the SMALL model. Classification is easy, so don't pay for the big one.
        JsonNode decision = Claude.askJson(Claude.SMALL_MODEL,
                "Classify a platform engineer's question into exactly one route:\n"
                + "- security: vulnerabilities, CVEs, advisories, outdated or risky libraries\n"
                + "- schema: event schemas, schema versions, producers and consumers of events\n"
                + "- ownership: which team owns a service, service lists, runtimes, tiers\n"
                + "- out_of_scope: anything else\n"
                + "Return {\"route\": string, \"reason\": string (max 12 words)}.",
                question, 200);
        String route = decision.path("route").asText("out_of_scope");
        System.out.println("ROUTE: " + route + "  (" + decision.path("reason").asText() + ")");

        // Step 2: code, not the model, decides what happens next.
        return switch (route) {
            case "security" -> Claude.ask(Claude.MODEL,
                    "You are a security engineer. Answer using only the advisory data given. "
                    + "Prioritise tier-1 services and critical advisories. Be concise.",
                    "Advisory data:\n" + Claude.pretty(Catalog.advisories()) + "\n\nQuestion: " + question, 600);
            case "schema" -> Claude.ask(Claude.MODEL,
                    "You are the schema registry owner. Answer using only the schema and consumer data given. "
                    + "Name services and versions exactly. Be concise.",
                    "Schemas and consumers:\n" + Claude.pretty(Catalog.schemas()) + "\n\nQuestion: " + question, 600);
            case "ownership" -> Claude.ask(Claude.SMALL_MODEL,      // simple lookup: small model is enough
                    "Answer using only the service catalog given. One or two sentences.",
                    "Catalog:\n" + Claude.pretty(Catalog.summary()) + "\n\nQuestion: " + question, 300);
            default -> "Sorry, I only answer questions about services, security advisories and event schemas.";
        };
    }
}
