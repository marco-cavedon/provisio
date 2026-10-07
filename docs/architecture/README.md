# Architecture

Diagrams follow the [C4 model](https://c4model.com/) and are written in Mermaid.

## System context

```mermaid
flowchart LR
    planner([Procurement planner])
    supplier([Supplier])
    provisio[["Provisio<br/>Agentic replenishment"]]
    erp[(ERP<br/>simulated)]
    llm[LLM provider<br/>Gemini]
    emb[Embedding provider<br/>Voyage AI]

    planner -- "reviews and approves<br/>order proposals" --> provisio
    supplier -- "delay notifications<br/>(email, simulated)" --> provisio
    provisio -- "issues purchase orders" --> erp
    provisio -- "agent reasoning<br/>and tool calls" --> llm
    provisio -- "document and<br/>query embeddings" --> emb
```

## Containers

```mermaid
flowchart TB
    planner([Procurement planner])

    subgraph provisio [Provisio]
        fe[Frontend<br/>React]
        app[Orchestrator<br/>Spring Boot<br/>BFF + agent tools]
        fc[Forecasting service<br/>Python, FastAPI]
        subgraph camunda [Camunda 8 Self-Managed]
            engine[Orchestration cluster<br/>BPMN, DMN, user tasks]
            conn[Connectors runtime<br/>AI Agent connector]
        end
        subgraph mongo [MongoDB Community]
            db[(mongod<br/>single-node replica set)]
            search[mongot<br/>Search / Vector Search]
        end
    end

    llm[LLM provider]
    emb[Embedding provider]
    erp[(ERP<br/>simulated)]

    planner --> fe
    fe -- "REST" --> app
    app -- "deploys models, starts processes,<br/>completes user tasks" --> engine
    engine -- "tool jobs" --> app
    engine --> conn
    conn -- "chat completions" --> llm
    app -- "forecast requests<br/>(bulk, contract-first)" --> fc
    app -- "stock, forecasts, documents,<br/>vector queries" --> db
    db <--> search
    app -- "embeddings" --> emb
    app -- "purchase orders" --> erp
```

The orchestrator is the only writer to MongoDB. The forecasting service is a stateless function with no
database access; the nightly forecast batch is started by a Camunda timer and executed by an orchestrator
worker — see [ADR 0006](../adr/0006-precomputed-forecast-with-on-demand-recompute.md).
