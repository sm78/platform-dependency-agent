package pda.day02;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import pda.common.Catalog;
import pda.common.Claude;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Pattern 3: ORCHESTRATOR-WORKERS. An "orchestrator" LLM looks at the goal and decides the
 * subtasks itself; "worker" LLM calls do one subtask each (here in parallel); a final call
 * combines their results.
 *
 *   [LLM orchestrator] plan subtasks -> [code] validate -> [LLM workers x N, in parallel] -> [LLM] synthesise
 *
 * Difference from chaining: there, YOU fixed the steps in code. Here the MODEL decides how many
 * subtasks there are and what each should focus on, because you can't know in advance.
 * Code still controls the flow: it validates the plan, runs the workers and caps their number.
 *
 * Run from java/:
 *   mvn -q compile exec:java -Dexec.mainClass=pda.day02.OrchestratorWorkers
 *   mvn -q compile exec:java -Dexec.mainClass=pda.day02.OrchestratorWorkers -Dexec.args="Remove every library with a known advisory from tier-1 services"
 */
public class OrchestratorWorkers {

    static final int MAX_WORKERS = 12;   // safety limit: the model must not be able to fan out without bound

    record Task(String service, String focus) {}

    public static void main(String[] args) throws Exception {
        String goal = args.length > 0 ? String.join(" ", args) : "Plan the upgrade of every Java 11 service to Java 21";

        // 1. ORCHESTRATOR: sees only the summary catalog and decides which services need work, and on what.
        JsonNode plan = Claude.askJson(Claude.MODEL,
                "You are a platform architect breaking a goal into independent subtasks, one per affected service. "
                + "For each, state what the analyst should focus on for that service. Include only services the goal "
                + "actually affects. Return {\"tasks\": [{\"service\": string, \"focus\": string (max 20 words)}]}.",
                "Goal: " + goal + "\n\nService catalog:\n" + Claude.pretty(Catalog.summary()), 2000);

        // 2. CODE validates the plan: drop invented services, cap the fan-out.
        List<Task> tasks = new ArrayList<>();
        for (JsonNode t : plan.path("tasks")) {
            String name = t.path("service").asText();
            if (Catalog.service(name) == null) {
                System.out.println("  (dropped unknown service from plan: " + name + ")");
            } else if (tasks.size() < MAX_WORKERS) {
                tasks.add(new Task(name, t.path("focus").asText()));
            }
        }
        System.out.println("ORCHESTRATOR planned " + tasks.size() + " subtasks:");
        tasks.forEach(t -> System.out.println("  - " + t.service() + ": " + t.focus()));

        // 3. WORKERS: each gets the FULL manifest of one service, so its context is small and focused. Run in parallel.
        ArrayNode findings = Claude.JSON.createArrayNode();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (Task t : tasks) futures.add(pool.submit(() -> work(goal, t)));
            for (Future<JsonNode> f : futures) findings.add(f.get());
        } finally {
            pool.shutdown();
        }
        System.out.println("\nWORKERS returned:\n" + Claude.pretty(findings));

        // 4. SYNTHESISER: combines worker results into one plan, using the call graph to order the work.
        String result = Claude.ask(Claude.MODEL,
                "You are a platform engineering lead. Combine per-service findings into one plan: group services "
                + "into waves (least risky and fewest dependants first, tier-1 last unless blocked), list blockers, "
                + "and give a one-line effort estimate per wave. Markdown, under 250 words.",
                "Goal: " + goal + "\n\nFindings:\n" + Claude.pretty(findings), 1500);
        System.out.println("\nSYNTHESISED PLAN\n" + result);
        Claude.printUsage();
    }

    static JsonNode work(String goal, Task task) {
        JsonNode manifest = Catalog.service(task.service());
        return Claude.askJson(Claude.MODEL,
                "You are a senior engineer analysing ONE service for a larger goal. Be specific to this service's "
                + "libraries and dependencies. Return {\"service\": string, \"effort\": \"S\"|\"M\"|\"L\", "
                + "\"blockers\": [string], \"steps\": [string] (max 4)}.",
                "Goal: " + goal + "\nYour focus: " + task.focus()
                + "\nCalled by: " + Catalog.callersOf(task.service())
                + "\n\nService manifest:\n" + Claude.pretty(manifest), 1000);
    }
}
