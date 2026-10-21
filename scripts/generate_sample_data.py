"""Generate a synthetic, fully offline "company" of ~30 microservices.

Output (deterministic, safe to re-run):
  data/services/<name>.json   one manifest per service
  data/schemas/<event>/v<N>.json   versioned event schemas (JSON Schema)
  data/catalog.json           index of all services (used by the MCP servers later)

The data is deliberately messy, like a real platform:
  - mixed runtimes (Java 11/17/21, Python 3.9-3.12, Node 18/20)
  - outdated and vulnerable libraries (log4j 2.14.1, Spring Boot 2.7, requests 2.25)
  - services that call each other (sync dependencies)
  - services that publish/consume events, some on old schema versions
"""
import json
import random
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "data"
random.seed(42)

TEAMS = ["payments", "accounts", "lending", "cards", "fraud", "platform", "customer", "notifications"]

# name, team, language, runtime
SERVICES = [
    ("api-gateway", "platform", "java", "21"),
    ("auth-service", "platform", "java", "17"),
    ("customer-profile", "customer", "java", "11"),
    ("kyc-service", "customer", "python", "3.9"),
    ("account-service", "accounts", "java", "17"),
    ("ledger-service", "accounts", "java", "11"),
    ("statement-service", "accounts", "python", "3.11"),
    ("payment-orchestrator", "payments", "java", "17"),
    ("wire-engine", "payments", "java", "11"),
    ("rtr-connector", "payments", "java", "21"),
    ("interac-adapter", "payments", "java", "11"),
    ("iso20022-mapper", "payments", "java", "17"),
    ("fx-rates", "payments", "python", "3.10"),
    ("card-issuing", "cards", "java", "17"),
    ("card-authorization", "cards", "java", "11"),
    ("card-disputes", "cards", "node", "18"),
    ("loan-origination", "lending", "java", "17"),
    ("credit-decisioning", "lending", "python", "3.9"),
    ("loan-servicing", "lending", "java", "11"),
    ("fraud-scoring", "fraud", "python", "3.12"),
    ("aml-screening", "fraud", "python", "3.10"),
    ("case-management", "fraud", "node", "20"),
    ("notification-hub", "notifications", "node", "20"),
    ("email-sender", "notifications", "python", "3.11"),
    ("sms-sender", "notifications", "node", "18"),
    ("audit-log", "platform", "java", "21"),
    ("feature-flags", "platform", "node", "20"),
    ("reporting-service", "accounts", "python", "3.9"),
    ("mobile-bff", "customer", "node", "20"),
    ("web-bff", "customer", "node", "18"),
]

LIBS = {
    "java": [
        ("org.springframework.boot:spring-boot-starter", ["2.7.18", "3.2.5", "3.3.4"]),
        ("org.apache.logging.log4j:log4j-core", ["2.14.1", "2.17.2", "2.23.1"]),
        ("com.fasterxml.jackson.core:jackson-databind", ["2.12.3", "2.15.2", "2.17.1"]),
        ("org.apache.kafka:kafka-clients", ["2.8.1", "3.5.1", "3.7.0"]),
        ("org.postgresql:postgresql", ["42.2.20", "42.6.0", "42.7.3"]),
        ("io.micrometer:micrometer-core", ["1.9.17", "1.12.5"]),
    ],
    "python": [
        ("requests", ["2.25.1", "2.31.0", "2.32.3"]),
        ("pydantic", ["1.10.13", "2.7.1", "2.8.2"]),
        ("fastapi", ["0.95.2", "0.110.0", "0.115.0"]),
        ("sqlalchemy", ["1.4.52", "2.0.30"]),
        ("confluent-kafka", ["1.9.2", "2.4.0"]),
        ("numpy", ["1.24.4", "1.26.4"]),
    ],
    "node": [
        ("express", ["4.17.1", "4.19.2"]),
        ("axios", ["0.21.1", "1.6.8", "1.7.2"]),
        ("kafkajs", ["1.16.0", "2.2.4"]),
        ("lodash", ["4.17.20", "4.17.21"]),
        ("jsonwebtoken", ["8.5.1", "9.0.2"]),
    ],
}

# Known-bad versions so the agent has something real to find.
ADVISORIES = {
    "org.apache.logging.log4j:log4j-core@2.14.1": "CVE-2021-44228 (Log4Shell), critical",
    "com.fasterxml.jackson.core:jackson-databind@2.12.3": "multiple deserialization CVEs, high",
    "requests@2.25.1": "CVE-2023-32681 (proxy auth leak), medium",
    "axios@0.21.1": "CVE-2021-3749 (ReDoS), high",
    "jsonwebtoken@8.5.1": "CVE-2022-23529 and related, high",
    "lodash@4.17.20": "CVE-2021-23337 (command injection), high",
}

# Sync call graph: service -> services it calls
CALLS = {
    "api-gateway": ["auth-service", "mobile-bff", "web-bff"],
    "mobile-bff": ["customer-profile", "account-service", "payment-orchestrator", "card-issuing", "feature-flags"],
    "web-bff": ["customer-profile", "account-service", "payment-orchestrator", "loan-origination", "feature-flags"],
    "customer-profile": ["kyc-service", "audit-log"],
    "account-service": ["ledger-service", "customer-profile", "audit-log"],
    "statement-service": ["ledger-service", "account-service"],
    "payment-orchestrator": ["account-service", "fraud-scoring", "aml-screening", "wire-engine", "rtr-connector", "interac-adapter", "fx-rates"],
    "wire-engine": ["iso20022-mapper", "ledger-service", "audit-log"],
    "rtr-connector": ["iso20022-mapper", "ledger-service"],
    "interac-adapter": ["ledger-service"],
    "card-authorization": ["card-issuing", "fraud-scoring", "ledger-service"],
    "card-disputes": ["card-authorization", "case-management"],
    "loan-origination": ["credit-decisioning", "kyc-service", "customer-profile"],
    "loan-servicing": ["ledger-service", "account-service"],
    "credit-decisioning": ["customer-profile"],
    "case-management": ["customer-profile", "audit-log"],
    "aml-screening": ["customer-profile", "case-management"],
    "reporting-service": ["ledger-service", "account-service", "loan-servicing"],
}

# Events: name -> {versions: {n: fields}, producer, consumers: {service: version}}
EVENTS = {
    "payment.initiated": {
        "producer": "payment-orchestrator",
        "versions": {
            1: {"payment_id": "string", "amount": "number", "currency": "string", "debtor_account": "string", "creditor_account": "string"},
            2: {"payment_id": "string", "amount": "number", "currency": "string", "debtor_account": "string", "creditor_account": "string", "rail": "string", "end_to_end_id": "string"},
        },
        "consumers": {"fraud-scoring": 2, "aml-screening": 1, "audit-log": 2, "notification-hub": 1, "reporting-service": 1},
    },
    "payment.settled": {
        "producer": "ledger-service",
        "versions": {1: {"payment_id": "string", "settled_at": "string", "amount": "number", "currency": "string"}},
        "consumers": {"payment-orchestrator": 1, "statement-service": 1, "notification-hub": 1},
    },
    "account.opened": {
        "producer": "account-service",
        "versions": {
            1: {"account_id": "string", "customer_id": "string", "product": "string"},
            2: {"account_id": "string", "customer_id": "string", "product": "string", "branch": "string", "opened_channel": "string"},
            3: {"account_id": "string", "customer_id": "string", "product_code": "string", "opened_channel": "string"},
        },
        "consumers": {"notification-hub": 3, "reporting-service": 1, "audit-log": 3, "fraud-scoring": 2},
    },
    "card.authorized": {
        "producer": "card-authorization",
        "versions": {1: {"auth_id": "string", "card_token": "string", "amount": "number", "merchant": "string", "mcc": "string"}},
        "consumers": {"fraud-scoring": 1, "ledger-service": 1, "notification-hub": 1},
    },
    "customer.kyc_completed": {
        "producer": "kyc-service",
        "versions": {
            1: {"customer_id": "string", "status": "string"},
            2: {"customer_id": "string", "status": "string", "risk_rating": "string", "verified_at": "string"},
        },
        "consumers": {"customer-profile": 2, "aml-screening": 1, "loan-origination": 1},
    },
    "loan.approved": {
        "producer": "credit-decisioning",
        "versions": {1: {"application_id": "string", "customer_id": "string", "amount": "number", "rate": "number", "term_months": "integer"}},
        "consumers": {"loan-origination": 1, "notification-hub": 1, "reporting-service": 1},
    },
    "fraud.alert_raised": {
        "producer": "fraud-scoring",
        "versions": {1: {"alert_id": "string", "subject_id": "string", "score": "number", "reason_codes": "array"}},
        "consumers": {"case-management": 1, "notification-hub": 1, "card-authorization": 1},
    },
}


def pick_libs(language: str) -> list[dict]:
    libs = []
    for name, versions in LIBS[language]:
        if random.random() < 0.8:
            v = random.choice(versions)
            entry = {"name": name, "version": v}
            adv = ADVISORIES.get(f"{name}@{v}")
            if adv:
                entry["known_advisory"] = adv
            libs.append(entry)
    return libs


def main() -> None:
    if ROOT.exists():
        shutil.rmtree(ROOT)
    (ROOT / "services").mkdir(parents=True)
    (ROOT / "schemas").mkdir(parents=True)

    catalog = []
    for name, team, language, runtime in SERVICES:
        publishes = [e for e, d in EVENTS.items() if d["producer"] == name]
        consumes = [{"event": e, "schema_version": d["consumers"][name]}
                    for e, d in EVENTS.items() if name in d["consumers"]]
        manifest = {
            "name": name,
            "team": team,
            "owner": f"{team}-team@example.com",
            "language": language,
            "runtime": {"java": "Java", "python": "Python", "node": "Node.js"}[language] + " " + runtime,
            "repo": f"https://git.example.com/{team}/{name}",
            "tier": 1 if name in {"payment-orchestrator", "ledger-service", "card-authorization", "auth-service", "api-gateway"} else random.choice([2, 2, 3]),
            "calls": CALLS.get(name, []),
            "publishes": publishes,
            "consumes": consumes,
            "libraries": pick_libs(language),
        }
        (ROOT / "services" / f"{name}.json").write_text(json.dumps(manifest, indent=2) + "\n")
        catalog.append({k: manifest[k] for k in ("name", "team", "language", "runtime", "tier")})

    for event, d in EVENTS.items():
        folder = ROOT / "schemas" / event
        folder.mkdir()
        latest = max(d["versions"])
        for v, fields in d["versions"].items():
            schema = {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "title": event,
                "version": v,
                "status": "current" if v == latest else "deprecated",
                "producer": d["producer"],
                "type": "object",
                "properties": {f: {"type": t} for f, t in fields.items()},
                "required": list(fields),
            }
            (folder / f"v{v}.json").write_text(json.dumps(schema, indent=2) + "\n")

    (ROOT / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
    print(f"Wrote {len(catalog)} services and {len(EVENTS)} event schemas to {ROOT}")


if __name__ == "__main__":
    main()
