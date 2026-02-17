# Rate-Limited Payment Dispatch System

A two-phase, rate-limited payment processing system built on **Quarkus**, **Temporal**, and **Oracle**. Designed to handle 500K–1M+ daily payments that converge on a single execution window (e.g., 16:00 MST) without overwhelming the Temporal Postgres database.

## Problem

When hundreds of thousands of payments are scheduled for the same execution time, using `Workflow.sleep()` per payment creates that many sleeping workflows in Temporal. This saturates the Temporal Postgres DB (`db.r8g.8xlarge` Aurora) with workflow state, timers, and history rows — causing elevated latency, connection exhaustion, and operational instability.

## Solution

Split the single long-lived workflow into **two short-lived workflows** connected by an **Oracle-based dispatch queue**:

- **Phase A** — Validate, enrich, apply rules, persist payment as SCHEDULED, save context, enqueue, and **complete immediately** (no sleep).
- **Phase B** — A Temporal Schedule fires a dispatcher every N seconds. The dispatcher claims batches from Oracle using `FOR UPDATE SKIP LOCKED`, then starts execution workflows with `startDelay()` jitter.

This reduces Temporal DB pressure by **~200x** (from 500K sleeping workflows to ~500 concurrent short-lived ones).

---

## Architecture

**Phase A (Payment Initialization)**

- Validates, enriches, and applies rules to the payment
- Persists the payment in the payments DB with `SCHEDULED` status
- Serializes all accumulated results as a JSON CLOB context
- Enqueues the payment in the dispatch queue with `READY` status
- Workflow completes immediately — no sleeping workflows

**Oracle Dispatch Queue**

- Buffer between phases — payments sit in `READY` until their scheduled execution time
- Concurrent dispatchers via `FOR UPDATE SKIP LOCKED`
- Built-in retry tracking with dead-letter protection
- Stores Phase A context as JSON CLOB (no work repeated in Phase B)

**Phase B (Dispatch & Execution)**

- Temporal Schedule fires `DispatcherWorkflow` every 5 seconds
- Each cycle: read config (kill switch) → recover stale claims → claim batch → dispatch → record results
- Batch claim JOINs with context table to **pre-load JSON CLOB** — no separate Oracle round-trip per execution
- Exec workflows started with `startDelay()` jitter to prevent thundering herd
- Exec workflows are pure business logic with zero dispatch framework awareness — they receive `contextJson` as a parameter, deserialize it, and execute. The dispatcher manages queue status externally
- Payment transitions: `SCHEDULED` → `ACCEPTED` → `PROCESSING`

**Observability**

- Insert-only audit log in Oracle (dispatches, failures, stale recoveries)
- Prometheus metrics via Micrometer (batch claimed, workflow starts, failures, stale recoveries, dead letters, cycle duration)

```mermaid
graph TB
    subgraph "Phase A — Payment Initialization"
        API["REST API / Kafka"] -->|PaymentRequest| INIT_WF["PaymentInitWorkflow"]
        INIT_WF --> VALIDATE["Validate Payment"]
        VALIDATE --> ENRICH["Enrich Payment"]
        ENRICH --> RULES["Apply Rules"]
        RULES --> PERSIST["Persist SCHEDULED Payment"]
        PERSIST --> BUILD_CTX["Build Context"]
        BUILD_CTX --> SAVE_CTX["Save Context (CLOB)"]
        SAVE_CTX -->|"ScheduleLifecycle"| ENQUEUE["Enqueue (READY)"]
        ENQUEUE --> COMPLETE_A["Workflow Completes"]
    end

    subgraph "Oracle Dispatch Queue"
        ENQUEUE --> ORACLE_Q[("PAYMENT_EXEC_QUEUE\n(status: READY)")]
        SAVE_CTX --> ORACLE_CTX[("PAYMENT_EXEC_CONTEXT\n(JSON CLOB)")]
    end

    subgraph "Phase B — Dispatch & Execution"
        SCHEDULE["Temporal Schedule\n(every 5s)"] -->|fires| DISP_WF["DispatcherWorkflow"]
        DISP_WF --> READ_CFG["Read Config"]
        READ_CFG --> STALE["Recover Stale Claims"]
        STALE --> CLAIM["Claim Batch\n(FOR UPDATE SKIP LOCKED\n+ pre-load context)"]
        CLAIM --> ORACLE_Q
        CLAIM --> ORACLE_CTX
        CLAIM --> DISPATCH["Dispatch Batch\n(pass contextJson)"]
        DISPATCH -->|"startDelay(jitter)\n+ contextJson param"| EXEC_WF["PaymentExecWorkflow\n(pure business logic)"]
        EXEC_WF --> EXECUTE["Execute → PostProcess → Notify"]
        EXECUTE --> DONE["Workflow completes\n(Temporal manages lifecycle)"]
    end

    subgraph "Observability"
        DISP_WF --> AUDIT[("DISPATCH_AUDIT_LOG")]
        DISP_WF --> METRICS["Prometheus Metrics\n(/q/metrics)"]
    end

    style ORACLE_Q fill:#f9e2af,stroke:#e6a817
    style ORACLE_CTX fill:#f9e2af,stroke:#e6a817
    style AUDIT fill:#f9e2af,stroke:#e6a817
    style SCHEDULE fill:#89b4fa,stroke:#1e66f5
    style COMPLETE_A fill:#a6e3a1,stroke:#40a02b
```

---

## Payment Lifecycle — End-to-End Sequence Diagram

```mermaid
sequenceDiagram
    participant Client as REST API / Kafka
    participant IW as PaymentInitWorkflow
    participant IA as InitActivities
    participant CA as ContextActivities
    participant Oracle as Oracle (Queue + Context)
    participant PDB as Payments DB
    participant T as Temporal Server
    participant EW as PaymentExecWorkflow<br/>(pure business logic)
    participant EA as ExecActivities

    rect rgb(235, 245, 255)
    Note over Client,CA: Phase A — Payment Initialization

    Client->>IW: initializePayment(paymentId, requestJson)
    IW->>IA: validate → enrich → applyRules
    IW->>IA: persistScheduledPayment
    IA->>PDB: INSERT payment (status = SCHEDULED)
    IW->>IA: buildContext + determineExecTime
    IW->>IW: ScheduleLifecycle.schedule(contextJson, execTime)
    IW->>CA: saveContextAndEnqueue(itemType, contextJson, execTime, workflowId)
    CA->>Oracle: INSERT context CLOB + INSERT queue (READY)
    Note over IW: Workflow completes immediately
    end

    rect rgb(255, 245, 235)
    Note over T: Dispatcher (every 5s) — claims batch + pre-loads context
    T->>Oracle: claimBatch (FOR UPDATE SKIP LOCKED + JOIN context)
    Oracle-->>T: ClaimedBatch (items + contextJson)
    T->>T: dispatchBatch: WorkflowClient.start(id, contextJson, startDelay=jitter)
    T->>Oracle: UPDATE status → DISPATCHED
    end

    rect rgb(235, 255, 235)
    Note over T,EW: Phase B — Payment Execution (after startDelay)

    T->>EW: execute(paymentId, contextJson)

    EW->>EW: deserialize contextJson
    EW->>EA: executePayment (SCHEDULED → ACCEPTED)
    EW->>EA: postProcess
    EW->>EA: sendNotifications (ACCEPTED → PROCESSING)

    Note over EW: Workflow completes (Temporal manages lifecycle)
    end
```

---

## Dispatch Cycle — Sequence Diagram

The dispatcher workflow runs as a short-lived Temporal workflow triggered by a schedule every 5 seconds. Each cycle is a self-contained unit of work: read config, self-heal stale claims, claim a batch, dispatch, and record results. If any step fails, the entire cycle fails gracefully and the next scheduled cycle picks up the work.

```mermaid
sequenceDiagram
    participant S as Temporal Schedule
    participant DW as DispatcherWorkflow
    participant DA as DispatcherActivities
    participant OQ as Oracle Queue
    participant OC as Oracle Config
    participant T as Temporal Server
    participant EW as PaymentExecWorkflow

    S->>DW: dispatch("PAYMENT")

    Note over DW,DA: Step 1 — Read Config
    DW->>DA: readDispatchConfig("PAYMENT")
    DA->>OC: SELECT * FROM EXEC_RATE_CONFIG
    OC-->>DA: DispatchConfig (batchSize=500, jitter=4000ms, ...)
    DA-->>DW: config

    alt config.enabled == false
        DW-->>S: return (kill switch active)
    end

    Note over DW,DA: Step 2 — Recover Stale Claims
    DW->>DA: recoverStaleClaims(config)
    DA->>OQ: SELECT CLAIMED rows older than 2 mins
    OQ-->>DA: List<StaleClaim>
    loop For each stale claim
        DA->>T: describe(execWorkflowId)
        alt WorkflowNotFoundException
            DA->>OQ: UPDATE status → READY (retry_count++)
        else Workflow exists
            Note over DA: Skip (conservative)
        end
    end
    DA-->>DW: recoveredCount

    Note over DW,DA: Step 3 — Claim Batch (with pre-loaded context)
    DW->>DA: claimBatch(config)
    DA->>OQ: SELECT ... FOR UPDATE SKIP LOCKED (up to 500 rows)
    DA->>OQ: UPDATE locked rows → CLAIMED (dispatch_batch_id)
    DA->>OQ: JOIN with PAYMENT_EXEC_CONTEXT (pre-load contextJson)
    OQ-->>DA: ClaimedBatch (N items + contextJson each)
    DA-->>DW: batch

    alt batch.items.isEmpty()
        DW-->>S: return (nothing to dispatch)
    end

    Note over DW,DA: Step 4 — Dispatch Batch (context passed as param)
    DW->>DA: dispatchBatch(batch, config)
    loop For each item in batch
        DA->>DA: jitterDelay = random(0, 4000ms)
        DA->>DA: workflowId = "exec-payment-{paymentId}"
        DA->>T: WorkflowClient.start(workflowId, paymentId, contextJson, startDelay=jitterDelay)
        alt Started successfully
            DA->>OQ: UPDATE status → DISPATCHED
        else WorkflowExecutionAlreadyStarted
            Note over DA: Treat as success (dedup)
            DA->>OQ: UPDATE status → DISPATCHED
        else Start failed
            DA->>OQ: UPDATE status → READY (retry_count++)
        end
    end
    DA-->>DW: List<DispatchResult>

    Note over DW,DA: Step 5 — Record Results
    DW->>DA: recordResults(batchId, results, config)
    DA->>OQ: INSERT audit log (BATCH_COMPLETE summary)

    DW-->>S: done (workflow completes)

    Note over T,EW: After startDelay — Pure business logic executes
    T->>EW: execute(paymentId, contextJson)
    EW->>EW: deserialize contextJson + execute business logic
    Note over EW: Workflow completes (stale recovery handles failures)
```

---

## Payment Status — State Diagram

The payment has its own business-level status lifecycle, tracked in the payments database. This is separate from the dispatch queue status and represents the payment's progress through the business process.

```mermaid
stateDiagram-v2
    [*] --> SCHEDULED : Phase A persists payment\n(after validate, enrich, apply rules)

    SCHEDULED --> ACCEPTED : Phase B validates in\npost-schedule flow\n(executePayment)

    ACCEPTED --> PROCESSING : All parties notified\n(sendNotifications)

    PROCESSING --> [*]

    note right of SCHEDULED
        Payment is persisted in the
        payments DB. Waiting for its
        scheduled execution time.
        Context saved as CLOB.
        Enqueued in dispatch queue.
    end note

    note right of ACCEPTED
        Post-schedule validation passed.
        Payment execution (debit/credit/
        settlement) completed.
        Confirmed ready for processing.
    end note

    note right of PROCESSING
        All parties (payer, payee,
        integration partners) have
        been notified. Terminal
        business state.
    end note
```

---

## Queue Status — State Diagram

The dispatch queue has its own infrastructure-level status lifecycle for managing the dispatch process. This is an internal mechanism and is separate from the payment's business status above.

```mermaid
stateDiagram-v2
    [*] --> READY : Phase A enqueues payment

    READY --> CLAIMED : Dispatcher claims batch\n(FOR UPDATE SKIP LOCKED)

    CLAIMED --> DISPATCHED : Exec workflow started\n(WorkflowClient.start)
    CLAIMED --> READY : Dispatch failed\n(retry_count++)
    CLAIMED --> READY : Stale recovery\n(claimed_at > threshold\n& workflow not found)

    DISPATCHED --> COMPLETED : Execution succeeded\n(context deleted)
    DISPATCHED --> FAILED : Execution failed\n(retry_count++)

    FAILED --> DEAD_LETTER : retry_count >= maxRetries\n(terminal — manual action)

    COMPLETED --> [*]
    DEAD_LETTER --> [*]

    note right of READY
        Default initial state.
        Eligible for next dispatch cycle.
    end note

    note right of CLAIMED
        Locked by a dispatcher instance.
        Cleared by stale recovery if
        dispatcher crashes.
    end note

    note right of DISPATCHED
        Exec workflow confirmed running
        in Temporal. Deterministic
        workflow ID prevents duplicates.
    end note

    note right of DEAD_LETTER
        Terminal state. Retries exhausted.
        Requires manual investigation.
        Alert: dispatch.dead.letter > 0
    end note
```

---

## Combined Status Flow — Payment & Queue

This diagram shows how the payment status and queue status flow together across both phases, from initial request through to completed processing.

```mermaid
graph LR
    subgraph "Phase A (Init Workflow)"
        A1["Validate"] --> A2["Enrich"]
        A2 --> A3["Apply Rules"]
        A3 --> A4["Persist Payment\n(SCHEDULED)"]
        A4 --> A5["Save Context\n+ Enqueue (READY)"]
    end

    subgraph "Dispatch Queue"
        A5 --> Q1["READY"]
        Q1 --> Q2["CLAIMED\n(context pre-loaded)"]
        Q2 --> Q3["DISPATCHED\n(contextJson passed)"]
    end

    subgraph "Phase B (Exec Workflow)"
        Q3 --> B1["Execute Payment\n(SCHEDULED → ACCEPTED)"]
        B1 --> B2["Post-Process"]
        B2 --> B3["Send Notifications\n(ACCEPTED → PROCESSING)"]
        B3 --> Q4["COMPLETED"]
    end

    style A4 fill:#89b4fa,stroke:#1e66f5
    style B1 fill:#a6e3a1,stroke:#40a02b
    style B3 fill:#a6e3a1,stroke:#40a02b
    style Q1 fill:#f9e2af,stroke:#e6a817
    style Q2 fill:#f9e2af,stroke:#e6a817
    style Q3 fill:#f9e2af,stroke:#e6a817
    style Q4 fill:#f9e2af,stroke:#e6a817
```

---

## Phase A — Payment Initialization State Diagram

Phase A runs as a short-lived Temporal workflow. It validates, enriches, applies rules, persists the payment as `SCHEDULED`, builds the accumulated context, saves it to Oracle, and enqueues the payment for dispatch. The workflow completes immediately — no sleeping.

```mermaid
stateDiagram-v2
    [*] --> Validating : PaymentInitWorkflow started

    Validating --> Enriching : Validation passed
    Validating --> Failed_Init : Validation failed

    Enriching --> ApplyingRules : Enrichment complete
    Enriching --> Failed_Init : Enrichment error

    ApplyingRules --> PersistingPayment : Rules applied
    ApplyingRules --> Failed_Init : Rule rejection

    PersistingPayment --> BuildingContext : Payment persisted (SCHEDULED)
    PersistingPayment --> Failed_Init : Persist error

    BuildingContext --> SavingContext : Context assembled
    SavingContext --> Enqueueing : Context saved to CLOB
    SavingContext --> Failed_Init : Save failed (no enqueue)

    Enqueueing --> Completed_A : Enqueued (READY)
    Enqueueing --> OrphanedContext : Enqueue failed\n(context exists, harmless)

    Completed_A --> [*] : Workflow completes immediately

    note right of PersistingPayment
        Payment is persisted in the
        payments DB with SCHEDULED status.
        First durable business state.
    end note

    note right of SavingContext
        Order matters:
        1. Save context FIRST
        2. Then enqueue
        If save fails → nothing enqueued (safe)
        If enqueue fails → orphaned context (TTL cleanup)
    end note

    note right of Completed_A
        No Workflow.sleep()
        Payment waits in Oracle
        READY status until
        dispatcher picks it up
    end note
```

---

## Deduplication Protection Layers

The system implements four layers of duplicate dispatch prevention to ensure every payment is executed exactly once, even under concurrent dispatchers, network failures, and Temporal retries.

```mermaid
graph TB
    subgraph "Layer 1 — Temporal Schedule"
        L1["ScheduleOverlapPolicy.SKIP\nPrevents concurrent dispatcher cycles"]
    end

    subgraph "Layer 2 — Oracle Locking"
        L2["FOR UPDATE SKIP LOCKED\nEach dispatcher claims disjoint batch\nNo two dispatchers claim same item"]
    end

    subgraph "Layer 3 — Deterministic Workflow ID"
        L3["workflowId = exec-{type}-{itemId}\nTemporal rejects duplicate starts\n(WorkflowExecutionAlreadyStarted)"]
    end

    subgraph "Layer 4 — Status Guards"
        L4["Queue status transitions are\nconditional (WHERE status = X)\nPrevents out-of-order transitions"]
    end

    L1 --> L2
    L2 --> L3
    L3 --> L4

    style L1 fill:#89b4fa,stroke:#1e66f5
    style L2 fill:#f9e2af,stroke:#e6a817
    style L3 fill:#a6e3a1,stroke:#40a02b
    style L4 fill:#f5c2e7,stroke:#ea76cb
```

| Layer | Mechanism | Prevents |
|-------|-----------|----------|
| **Schedule SKIP** | `ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP` | Concurrent dispatcher workflows |
| **SKIP LOCKED** | `SELECT ... FOR UPDATE SKIP LOCKED` | Two dispatchers claiming same row |
| **Deterministic ID** | `exec-{itemType}-{itemId}` | Duplicate exec workflow starts |
| **Status Guards** | `WHERE queue_status = 'CLAIMED'` | Out-of-order status transitions |

---

## Oracle Schema

```mermaid
erDiagram
    EXEC_RATE_CONFIG {
        VARCHAR2 item_type PK "PAYMENT, INVOICE, etc."
        NUMBER enabled "Kill switch (0/1)"
        NUMBER batch_size "Items per cycle"
        NUMBER dispatch_interval_secs "Schedule interval"
        NUMBER jitter_window_ms "startDelay variance"
        NUMBER pre_start_buffer_mins "How far ahead to query"
        NUMBER max_dispatch_retries "Retries before DEAD_LETTER"
        NUMBER stale_claim_threshold_mins "Stale claim detection"
        NUMBER max_stale_recovery_per_cycle "Recovery cap"
        VARCHAR2 exec_workflow_type "e.g. PaymentExecWorkflow"
        VARCHAR2 exec_task_queue "e.g. payment-exec-task-queue"
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    PAYMENT_EXEC_QUEUE {
        VARCHAR2 payment_id PK "Unique item ID"
        VARCHAR2 item_type "Type discriminator"
        VARCHAR2 queue_status "READY/CLAIMED/DISPATCHED/..."
        TIMESTAMP scheduled_exec_time "When to dispatch"
        VARCHAR2 init_workflow_id "Phase A workflow ID"
        VARCHAR2 dispatch_batch_id "BATCH-xxxx"
        VARCHAR2 exec_workflow_id "Phase B workflow ID"
        TIMESTAMP claimed_at
        TIMESTAMP dispatched_at
        TIMESTAMP completed_at
        NUMBER retry_count "Incremented on failure"
        VARCHAR2 last_error "Error from last failure"
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    PAYMENT_EXEC_CONTEXT {
        VARCHAR2 payment_id PK "Same as queue"
        VARCHAR2 item_type "Type discriminator"
        CLOB context_json "Phase A results (JSON)"
        NUMBER context_version "Schema version"
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    DISPATCH_AUDIT_LOG {
        NUMBER audit_id PK "Auto-increment"
        VARCHAR2 batch_id "BATCH-xxxx or STALE-RECOVERY"
        VARCHAR2 item_type "Type discriminator"
        VARCHAR2 payment_id "Item ID"
        VARCHAR2 action "DISPATCHED/FAILED/STALE_RECOVERY/..."
        VARCHAR2 status_before
        VARCHAR2 status_after
        VARCHAR2 detail "Free-form context"
        TIMESTAMP created_at
    }

    EXEC_RATE_CONFIG ||--o{ PAYMENT_EXEC_QUEUE : "item_type (logical)"
    PAYMENT_EXEC_QUEUE ||--o| PAYMENT_EXEC_CONTEXT : "payment_id (logical)"
    PAYMENT_EXEC_QUEUE ||--o{ DISPATCH_AUDIT_LOG : "payment_id (logical)"
```

> **Note:** No foreign keys are used. Referential integrity is enforced at the application level. This avoids FK lock contention under high-throughput batch operations.

### Key Indexes

| Index | Columns | Purpose |
|-------|---------|---------|
| `idx_peq_dispatch` | `(item_type, queue_status, scheduled_exec_time, retry_count)` | `claimBatch` — high-frequency dispatcher query |
| `idx_peq_stale_claims` | `(item_type, queue_status, claimed_at)` | Stale recovery query |
| `idx_peq_batch` | `(dispatch_batch_id)` | Post-claim batch lookup |

---

## Project Structure

```
payment-dispatch/
├── build.gradle.kts                          # Gradle build (Quarkus + Temporal + Exposed)
├── settings.gradle.kts                       # Project settings
├── gradle.properties                         # Version pinning
│
└── src/main/
    ├── resources/
    │   ├── application.yaml                  # Quarkus config (Temporal, Oracle, metrics)
    │   └── db/migration/
    │       └── V1__create_dispatch_tables.sql # Oracle DDL (4 tables, indexes, seed data)
    │
    └── kotlin/com/payment/dispatcher/
        │
        ├── config/
        │   ├── AppConfig.kt                  # @ConfigMapping for dispatch settings
        │   ├── TemporalConfig.kt             # Temporal client + worker configuration
        │   ├── WorkerConfig.kt               # Worker registration and task queue setup
        │   └── DispatchScheduleInitializer.kt # Creates Temporal Schedule on startup
        │
        ├── framework/                        # ── Generic Dispatch Infrastructure ──
        │   ├── model/
        │   │   ├── QueueStatus.kt            # READY/CLAIMED/DISPATCHED/COMPLETED/FAILED/DEAD_LETTER
        │   │   ├── DispatchConfig.kt         # Runtime config loaded from EXEC_RATE_CONFIG
        │   │   ├── ClaimedBatch.kt           # Batch claim result (batchId + items)
        │   │   └── DispatchResult.kt         # Per-item dispatch result
        │   │
        │   ├── repository/
        │   │   ├── tables/
        │   │   │   ├── ExecRateConfigTable.kt    # Exposed table: EXEC_RATE_CONFIG
        │   │   │   ├── ExecQueueTable.kt         # Exposed table: PAYMENT_EXEC_QUEUE
        │   │   │   ├── ExecContextTable.kt       # Exposed table: PAYMENT_EXEC_CONTEXT
        │   │   │   └── DispatchAuditLogTable.kt  # Exposed table: DISPATCH_AUDIT_LOG
        │   │   │
        │   │   ├── DispatchQueueRepository.kt    # Core queue ops (claim, status, stale recovery)
        │   │   ├── DispatchConfigRepository.kt   # Config reader
        │   │   └── DispatchAuditRepository.kt    # Insert-only audit log
        │   │
        │   ├── context/
        │   │   ├── ExecutionContextService.kt    # Generic context interface
        │   │   └── ExposedContextService.kt      # Exposed + Jackson implementation
        │   │
        │   ├── activity/
        │   │   ├── DispatcherActivities.kt       # @ActivityInterface (5 methods)
        │   │   ├── DispatcherActivitiesImpl.kt   # Core dispatch logic
        │   │   └── SchedulableContextActivities.kt # @ActivityInterface for context + enqueue
        │   │
        │   ├── workflow/
        │   │   ├── ScheduleLifecycle.kt           # Composable lifecycle helper
        │   │   ├── DispatcherWorkflow.kt         # @WorkflowInterface
        │   │   └── DispatcherWorkflowImpl.kt     # 5-step dispatch cycle
        │   │
        │   ├── schedule/
        │   │   └── DispatchScheduleSetup.kt      # Temporal Schedule creation
        │   │
        │   ├── config/
        │   │   └── ExposedDatabaseConfig.kt      # Exposed ↔ Agroal bridge
        │   │
        │   └── metrics/
        │       └── DispatchMetrics.kt            # Micrometer counters + timers
        │
        └── payment/                          # ── Payment-Specific Implementation ──
            ├── model/
            │   ├── PaymentRequest.kt             # Inbound DTO
            │   ├── PaymentExecContext.kt          # Phase A accumulated context
            │   └── PaymentStatus.kt              # SCHEDULED / ACCEPTED / PROCESSING
            │
            ├── context/
            │   └── PaymentContextActivitiesImpl.kt # Implements SchedulableContextActivities
            │
            ├── init/                             # Phase A
            │   ├── PaymentInitWorkflow.kt        # @WorkflowInterface
            │   ├── PaymentInitWorkflowImpl.kt    # Validate → Enrich → Rules → Persist → Enqueue
            │   ├── PaymentInitActivities.kt      # @ActivityInterface
            │   └── PaymentInitActivitiesImpl.kt  # Business logic stubs
            │
            └── exec/                             # Phase B
                ├── PaymentExecWorkflow.kt        # @WorkflowInterface
                ├── PaymentExecWorkflowImpl.kt    # Pure business logic (zero framework awareness)
                ├── PaymentExecActivities.kt      # @ActivityInterface
                └── PaymentExecActivitiesImpl.kt  # Business logic stubs
```

---

## Key Design Decisions

### `startDelay()` Instead of `Workflow.sleep()`

```mermaid
graph LR
    subgraph "Before — Workflow.sleep()"
        A1["500K Workflows Created"] --> A2["500K Sleeping\n(in Temporal DB)"] --> A3["All wake at 16:00\n(thundering herd)"]
    end

    subgraph "After — startDelay()"
        B1["Dispatcher claims\n500 items/cycle"] --> B2["WorkflowClient.start()\nwith startDelay(jitter)"] --> B3["~500 workflows\nstaggered over\njitter window"]
    end

    style A2 fill:#f38ba8,stroke:#d20f39
    style B3 fill:#a6e3a1,stroke:#40a02b
```

- **No sleeping workflows** — payments wait in Oracle `READY` status, not in Temporal
- **Controlled throughput** — dispatcher starts ~500 workflows per 5-second cycle
- **Jitter** — `startDelay(random(0, 4000ms))` spreads execution starts, preventing thundering herd
- **~200x reduction** in Temporal DB pressure

### Oracle `FOR UPDATE SKIP LOCKED`

Raw JDBC is used for the batch claim operation because Kotlin Exposed DSL only provides `ForUpdateOption.PostgreSQL` — there is no Oracle-specific variant.

```sql
SELECT payment_id FROM PAYMENT_EXEC_QUEUE
WHERE item_type = ?
  AND queue_status = 'READY'
  AND scheduled_exec_time <= ?
  AND retry_count < ?
ORDER BY scheduled_exec_time ASC
FETCH FIRST ? ROWS ONLY
FOR UPDATE SKIP LOCKED
```

- **Contention-free** — multiple dispatcher instances can run concurrently
- **Non-blocking** — `SKIP LOCKED` skips rows locked by other transactions
- **Atomic** — SELECT + UPDATE within the same JDBC transaction

### Insert-First Context Persistence

Instead of Exposed's `upsert()` (which generates Oracle `MERGE`), context saves use `insert()` with duplicate-key fallback:

```kotlin
try {
    ExecContextTable.insert { ... }
} catch (e: ExposedSQLException) {
    if (isUniqueViolation) {
        ExecContextTable.update({ ... }) { ... }
    } else throw e
}
```

- **Lower overhead** — avoids MERGE on every save
- **Idempotent** — safe on Temporal activity retries
- **Common path optimized** — first insert is a simple INSERT (majority case)

### Context Pre-loading at Dispatch Time

Instead of having each execution workflow load its context from Oracle as its first activity, context is **pre-loaded during the batch claim** and passed as a JSON string parameter to the exec workflow:

```mermaid
graph LR
    subgraph "Before — Load in Exec Workflow"
        C1["Dispatcher starts\nexec workflow"] --> C2["Exec workflow calls\nloadContext activity"] --> C3["Activity queries\nOracle CLOB"] --> C4["Deserialize\n+ Execute"]
    end

    subgraph "After — Pre-load at Dispatch"
        D1["Dispatcher claims batch\n(JOIN with context table)"] --> D2["Context JSON\nalready in memory"] --> D3["Pass as workflow\nparameter"] --> D4["Deserialize locally\n+ Execute"]
    end

    style C2 fill:#f38ba8,stroke:#d20f39
    style C3 fill:#f38ba8,stroke:#d20f39
    style D1 fill:#a6e3a1,stroke:#40a02b
    style D3 fill:#a6e3a1,stroke:#40a02b
```

- **Eliminates N Oracle round-trips** — context for all 500 items in a batch is loaded in a single JOIN query during `claimBatch`
- **Simpler exec workflow** — no `loadContext` activity, no Oracle dependency at execution time
- **One fewer failure mode** — if context load failed before, the exec workflow would be stuck in CLAIMED; now the context is guaranteed available at workflow start
- **Context is immutable** — Phase A output doesn't change after enqueueing, so pre-loading introduces no staleness risk

### Composition via Lifecycle Helpers

The framework layer is generic and reusable. Payment-specific implementations compose framework helpers — no inheritance required. Exec workflows are pure business logic with zero framework awareness:

```mermaid
graph TB
    subgraph "Framework (Generic)"
        SL["ScheduleLifecycle\n(composable helper)"]
        SCA["SchedulableContextActivities\n(@ActivityInterface)"]
        ECS["ExposedContextService&lt;T&gt;"]
        DQR["DispatchQueueRepository"]
    end

    subgraph "Payment Domain (Specific)"
        PIW["PaymentInitWorkflowImpl\n(uses ScheduleLifecycle)"]
        PEW["PaymentExecWorkflowImpl\n(pure business logic —\nzero framework dependency)"]
        PCA["PaymentContextActivitiesImpl\n(implements SchedulableContextActivities)"]
    end

    PIW -->|"composes"| SL
    PCA -->|"implements"| SCA
    PCA -->|"composes"| ECS
    PCA -->|"composes"| DQR

    style SL fill:#89b4fa,stroke:#1e66f5
    style SCA fill:#89b4fa,stroke:#1e66f5
    style ECS fill:#89b4fa,stroke:#1e66f5
    style DQR fill:#89b4fa,stroke:#1e66f5
    style PIW fill:#a6e3a1,stroke:#40a02b
    style PEW fill:#a6e3a1,stroke:#40a02b
    style PCA fill:#a6e3a1,stroke:#40a02b
```

To add a new domain (e.g., invoices):
1. Insert config row in `EXEC_RATE_CONFIG` (`item_type='INVOICE'`)
2. Create `InvoiceExecContext`, `InvoiceInitWorkflow` (compose `ScheduleLifecycle` in init workflow)
3. Create `InvoiceExecWorkflowImpl` — pure business logic, receives `contextJson`, no framework dependency
4. Create `InvoiceContextActivitiesImpl` implementing `SchedulableContextActivities`
5. No changes to the framework layer

---

## Failure Recovery

```mermaid
flowchart TD
    F1["Dispatcher crashes\nafter claiming batch"] -->|"Items stuck in CLAIMED"| R1["Stale Recovery\n(next cycle detects\nclaimed_at > 2 min)"]
    R1 -->|"Check Temporal:\nworkflow not found"| R1A["Reset to READY\n(retry_count++)"]
    R1 -->|"Check Temporal:\nworkflow exists"| R1B["Skip\n(conservative)"]

    F2["WorkflowClient.start()\nfails for an item"] --> R2["Reset to READY\nimmediately\n(retry_count++)"]

    F3["Exec workflow fails\n(Temporal records failure)"] --> R3A["Stale recovery detects\nDISPATCHED item with\nno running workflow\n→ reset to READY"]

    F4["Context pre-load fails\n(during claimBatch)"] -->|"Batch claim fails entirely\nnext cycle retries"| R2B["Items remain CLAIMED\n→ stale recovery"]
    R2B --> R1

    style R1A fill:#a6e3a1,stroke:#40a02b
    style R2 fill:#a6e3a1,stroke:#40a02b
    style R3A fill:#a6e3a1,stroke:#40a02b
    style R1B fill:#89b4fa,stroke:#1e66f5
```

---

## Temporal Workers

Three dedicated workers with isolated task queues:

| Worker | Task Queue | Workflows | Activities | Concurrency |
|--------|-----------|-----------|------------|-------------|
| **dispatch-worker** | `dispatch-task-queue` | DispatcherWorkflow | DispatcherActivities | 5 WF / 10 Act |
| **payment-init-worker** | `payment-init-task-queue` | PaymentInitWorkflow | PaymentInitActivities, SchedulableContextActivities | 100 WF / 200 Act |
| **payment-exec-worker** | `payment-exec-task-queue` | PaymentExecWorkflow | PaymentExecActivities | 100 WF / 200 Act |

---

## Metrics & Alerting

Exported via Micrometer to Prometheus at `/q/metrics`.

### Counters

| Metric | Description |
|--------|-------------|
| `dispatch.batch.claimed` | Total items claimed across all batches |
| `dispatch.workflow.started` | Exec workflows successfully started |
| `dispatch.workflow.start.failures` | Exec workflow start failures |
| `dispatch.stale.recovered` | Stale claims recovered to READY |
| `dispatch.dead.letter` | Items moved to DEAD_LETTER |

### Timers

| Metric | Description |
|--------|-------------|
| `dispatch.cycle.duration` | Full dispatch cycle duration (seconds) |

### Recommended Alerts

| Alert | Condition | Severity |
|-------|-----------|----------|
| Queue depth high | `dispatch.queue.depth{status="READY"} > 10000` for 5m | Warning |
| Dead letters appearing | `rate(dispatch.dead.letter[5m]) > 0` for 1m | Critical |
| Stale recovery elevated | `rate(dispatch.stale.recovered[5m]) > 1` for 5m | Warning |
| Dispatch failures spike | `rate(dispatch.workflow.start.failures[5m]) > 5` for 2m | Critical |

---

## Runtime Configuration

All dispatch parameters are stored in `EXEC_RATE_CONFIG` and read each cycle — **no redeploy needed**.

### Tuning batch size

```sql
UPDATE EXEC_RATE_CONFIG SET batch_size = 1000 WHERE item_type = 'PAYMENT';
-- Takes effect on next dispatch cycle (within 5 seconds)
```

### Kill switch

```sql
UPDATE EXEC_RATE_CONFIG SET enabled = 0 WHERE item_type = 'PAYMENT';
-- Dispatcher reads config, sees enabled=0, returns immediately
-- Items remain in READY status (not lost)
```

### Adjusting jitter window

```sql
UPDATE EXEC_RATE_CONFIG SET jitter_window_ms = 8000 WHERE item_type = 'PAYMENT';
-- Exec workflows will now spread starts over 0-8 seconds instead of 0-4 seconds
```

---

## Capacity Math

| Metric | Before (sleep-based) | After (dispatch queue) |
|--------|---------------------|----------------------|
| Sleeping workflows | 500,000 | 0 |
| Concurrent workflows | 500,000 | ~500 per cycle |
| Temporal DB rows (active) | ~2,000,000 | ~10,000 |
| DB pressure factor | 1x | ~0.005x (200x reduction) |
| Dispatch latency | All at once (thundering herd) | Staggered over jitter window |

---

## Tech Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| **Kotlin** | 2.1.0 | Language (JVM 21) |
| **Quarkus** | 3.29.4 | Application framework (CDI, REST, health, config) |
| **Temporal SDK** | 1.31.0 | Workflow orchestration |
| **Kotlin Exposed** | 0.61.0 | Type-safe SQL DSL (Oracle) |
| **Oracle** | — | Dispatch queue, context persistence, config, audit |
| **Agroal** | (Quarkus-managed) | Oracle connection pooling (5–20 connections) |
| **Micrometer + Prometheus** | (Quarkus-managed) | Metrics export |
| **Jackson** | (Quarkus-managed) | JSON serialization (Kotlin module) |

---

## Getting Started

### Prerequisites

- JDK 21+
- Oracle database (or Oracle XE for local dev)
- Temporal Server (local or remote)
- Gradle 8.11.1

### Build

```bash
./gradlew build
```

### Run Database Migration

Execute the DDL script against your Oracle instance:

```bash
sqlplus dispatch_user/dispatch_pass@//localhost:1521/XEPDB1 @src/main/resources/db/migration/V1__create_dispatch_tables.sql
```

### Run

```bash
./gradlew quarkusDev
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `TEMPORAL_TARGET` | `localhost:7233` | Temporal gRPC endpoint |
| `TEMPORAL_NAMESPACE` | `default` | Temporal namespace |
| `ORACLE_USER` | `dispatch_user` | Oracle username |
| `ORACLE_PASSWORD` | `dispatch_pass` | Oracle password |
| `ORACLE_JDBC_URL` | `jdbc:oracle:thin:@//localhost:1521/XEPDB1` | Oracle JDBC URL |
