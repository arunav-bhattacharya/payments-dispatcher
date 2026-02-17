# Rate-Limited Payment Dispatch System — Implementation Plan

## Problem Statement

500K-1M+ daily payments converge on a single execution window (e.g., 16:00 MST). Using `Workflow.sleep()` per payment creates hundreds of thousands of sleeping workflows that overwhelm the Temporal Postgres DB — causing elevated latency, connection exhaustion, and operational instability.

## Solution Overview

Split the single long-lived workflow into **two short-lived workflows** connected by an **Oracle-based dispatch queue**. This reduces Temporal DB pressure by ~200x (from 500K sleeping workflows to ~500 concurrent short-lived ones).

---

## Phase 1: Core Architecture Design

### 1.1 Two-Phase Workflow Split

- **Phase A (PaymentInitWorkflow):** Validates, enriches, applies rules, persists payment as `SCHEDULED`, saves context to Oracle CLOB, enqueues for dispatch, and COMPLETES immediately (no sleeping).
- **Phase B (PaymentExecWorkflow):** Short-lived workflow started by the dispatcher. Deserializes pre-loaded context, executes the payment, post-processes, sends notifications.

### 1.2 Oracle Dispatch Queue

- Buffer between phases — payments sit in `READY` until their scheduled execution time
- `FOR UPDATE SKIP LOCKED` for contention-free concurrent dispatching
- Built-in retry tracking (`retry_count`) with dead-letter protection
- Four tables: `EXEC_RATE_CONFIG`, `PAYMENT_EXEC_QUEUE`, `PAYMENT_EXEC_CONTEXT`, `DISPATCH_AUDIT_LOG`
- No foreign keys — referential integrity enforced at application level

### 1.3 Dispatcher (DispatcherWorkflow)

- Temporal Schedule fires every 5 seconds
- 5-step cycle: read config (kill switch) → recover stale claims → claim batch → dispatch batch → record results
- `ScheduleOverlapPolicy.SKIP` prevents concurrent dispatcher instances
- `WorkflowOptions.setStartDelay()` for jitter instead of `Workflow.sleep()`

---

## Phase 2: Key Technical Decisions

### 2.1 `startDelay()` over `Workflow.sleep()`

- Payments wait in Oracle `READY` status, not as sleeping Temporal workflows
- Dispatcher starts ~500 workflows per 5-second cycle with `startDelay(random(0, jitterMs))`
- Spreads execution starts to prevent thundering herd

### 2.2 No Foreign Keys

- Avoids FK lock contention under high-throughput batch operations
- Application-level referential integrity is sufficient for this use case

### 2.3 Reusable / Pluggable Framework

- Generic `framework/` layer handles all dispatch infrastructure
- Payment-specific implementations in `payment/` compose generic services
- Adding a new domain (e.g., Invoice) requires no framework changes

### 2.4 Oracle-native `FOR UPDATE SKIP LOCKED`

- Kotlin Exposed DSL only provides `ForUpdateOption.PostgreSQL`
- Raw JDBC via `AgroalDataSource` used for the SELECT + UPDATE claim operation
- All other operations use Exposed DSL

### 2.5 Insert-First Context Persistence

- `insert()` + catch `ORA-00001` + fallback to `update()` instead of Exposed `upsert()` (which generates Oracle MERGE)
- Lower overhead, idempotent on Temporal retries, common path optimized

---

## Phase 3: Context Pre-loading at Dispatch Time

### Problem

Originally, `PaymentExecWorkflowImpl` called `contextActivities.loadContext(paymentId)` as its first activity — a separate Oracle round-trip per execution workflow.

### Solution

- `claimBatch()` JOINs with `PAYMENT_EXEC_CONTEXT` to pre-load context JSON alongside queue items in a single query
- `ClaimedItem` carries `contextJson: String` field
- `DispatcherActivitiesImpl.dispatchSingleItem()` passes `contextJson` as second argument to `WorkflowClient.start()`
- `PaymentExecWorkflow.execute(paymentId, contextJson)` — context arrives as a parameter
- `loadContext` removed from `PaymentContextActivities`

### Why This Works

- Context is immutable Phase A output — no staleness risk
- Eliminates N Oracle round-trips per batch (500 items loaded in one JOIN)
- Removes a failure mode (context load failure)

---

## Phase 4: Composition via Lifecycle Helpers

### Problem

`PaymentExecWorkflowImpl` mixed business logic with dispatch infrastructure (queue status transitions, context cleanup). Similarly, `PaymentInitWorkflowImpl` mixed business logic with scheduling infrastructure (context persistence, enqueueing).

### Solution: Composable Lifecycle Helpers

Instead of inheritance (template method), use composition:

**Phase A — `ScheduleLifecycle` (composable helper):**
```
PaymentInitWorkflowImpl
├── initActivities.validate/enrich/applyRules/persist/buildContext/determineExecTime
└── scheduleLifecycle.schedule(contextJson, scheduledExecTime)
    └── SchedulableContextActivities.saveContextAndEnqueue(itemType, contextJson, execTime, workflowId)
```

**Phase B — Pure business logic (no helper needed):**
```
PaymentExecWorkflowImpl (zero framework awareness)
├── Deserialize contextJson
├── execActivities.executePayment(context)
├── execActivities.postProcess(context)
└── execActivities.sendNotifications(context)
```

The exec workflow has no dispatch framework imports. The dispatcher manages queue status externally. The unified claim query automatically picks up stale CLAIMED items if the dispatcher crashes.

### Changes Made

- **`ScheduleLifecycle`** (NEW) — composable helper in `framework/workflow/`, holds `SchedulableContextActivities` stub, captures workflow ID automatically
- **`SchedulableContextActivities`** (NEW) — framework-level `@ActivityInterface` for generic context save + enqueue
- **`PaymentContextActivitiesImpl`** — implements `SchedulableContextActivities` (was `PaymentContextActivities`), receives `contextJson` + `itemType` as parameters
- **`PaymentContextActivities`** — DELETED (replaced by framework-level `SchedulableContextActivities`)
- **`PaymentExecWorkflowImpl`** — pure business logic, zero framework awareness, no dispatch imports
- **`PaymentInitWorkflowImpl`** — composes `ScheduleLifecycle`, single `schedule()` call at end
- **`DispatchableWorkflow`** — DELETED (replaced by composition approach)
- **`DispatcherActivities`** — removed `completeItem`/`failItem` (5 methods, down from 7)
- **`DispatcherActivitiesImpl`** — registered on `dispatch-worker` only (was `dispatch-worker` + `payment-exec-worker`)

---

## Phase 5: Payment Status Lifecycle

### Requirement

- Payment states: `SCHEDULED` → `ACCEPTED` → `PROCESSING`
- `SCHEDULED` — persisted in Phase A after validation/enrichment/rules
- `ACCEPTED` — after post-schedule validation in Phase B (`executePayment`)
- `PROCESSING` — after all parties notified (`sendNotifications`)

### Changes Made

- Created `PaymentStatus.kt` enum
- Added `persistScheduledPayment()` activity in Phase A
- Removed all fee calculation references (`calculateFees`, `FeeCalculation`, `FeeComponent`, `feeCalculation` field)

---

## Phase 6: Code Cleanup

### `DispatchQueueRepository`

- Replaced `import org.jetbrains.exposed.sql.*` with specific imports: `SortOrder`, `SqlExpressionBuilder.eq`, `.inList`, `.lessEq`, `.plus`, `and`, `insert`, `selectAll`, `transaction`, `update`
- Removed unused `getStatus()` and `countByStatus()` methods

### `DispatcherActivitiesImpl`

- Added `import ClaimedItem`, replaced FQN `com.payment.dispatcher.framework.model.ClaimedItem` with short name

### `PaymentInitActivitiesImpl`

- Replaced `import com.payment.dispatcher.payment.model.*` with 6 specific imports (removed unused `PaymentStatus`, `FxRateSnapshot`)

---

## Four Deduplication Layers

| Layer | Mechanism | Prevents |
|-------|-----------|----------|
| Schedule SKIP | `ScheduleOverlapPolicy.SKIP` | Concurrent dispatcher workflows |
| SKIP LOCKED | `SELECT ... FOR UPDATE SKIP LOCKED` | Two dispatchers claiming same row |
| Deterministic ID | `exec-{itemType}-{itemId}` | Duplicate exec workflow starts |
| Status Guards | `WHERE queue_status = 'CLAIMED'` | Out-of-order status transitions |

---

## Failure Recovery

| Failure | Recovery |
|---------|----------|
| Dispatcher crashes after claiming batch | Stale recovery (next cycle detects `claimed_at > threshold`, checks Temporal, resets to READY) |
| `WorkflowClient.start()` fails | Reset to READY immediately (`retry_count++`) |
| Exec workflow fails (exception) | Temporal records failure; stale recovery detects DISPATCHED item with no running workflow → reset to READY |
| Context pre-load fails during claimBatch | Batch claim fails entirely, items remain CLAIMED → stale recovery |

---

## Technology Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Kotlin | 2.1.0 | Language (JVM 21) |
| Quarkus | 3.29.4 | Application framework |
| Temporal SDK | 1.31.0 | Workflow orchestration |
| Kotlin Exposed | 0.61.0 | Type-safe SQL DSL (Oracle) |
| Oracle | — | Dispatch queue, context, config, audit |
| Agroal | (Quarkus) | Oracle connection pooling (5-20) |
| Micrometer + Prometheus | (Quarkus) | Metrics export |
| Gradle | 8.11.1 | Build system |

---

## File Inventory (Final State)

```
payment-dispatch/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── .gitignore
├── README.md
├── plan.md                                      ← this file
│
└── src/main/
    ├── resources/
    │   ├── application.yaml
    │   └── db/migration/V1__create_dispatch_tables.sql
    │
    └── kotlin/com/payment/dispatcher/
        ├── config/
        │   ├── AppConfig.kt                     (+ temporal, workers config)
        │   ├── TemporalConfig.kt                CDI producer: WorkflowServiceStubs, WorkflowClient
        │   ├── WorkerConfig.kt                  Manual worker creation + registration
        │   └── DispatchScheduleInitializer.kt
        │
        ├── framework/
        │   ├── model/
        │   │   ├── QueueStatus.kt
        │   │   ├── DispatchConfig.kt
        │   │   ├── ClaimedBatch.kt              (+ contextJson field)
        │   │   └── DispatchResult.kt
        │   ├── repository/
        │   │   ├── tables/ (4 Exposed tables)
        │   │   ├── DispatchQueueRepository.kt    (specific imports, no unused methods)
        │   │   ├── DispatchConfigRepository.kt
        │   │   └── DispatchAuditRepository.kt
        │   ├── context/
        │   │   ├── ExecutionContextService.kt
        │   │   └── ExposedContextService.kt
        │   ├── activity/
        │   │   ├── DispatcherActivities.kt       (5 methods: dispatch only)
        │   │   ├── DispatcherActivitiesImpl.kt   (worker: dispatch only)
        │   │   └── SchedulableContextActivities.kt  Framework interface for context save + enqueue
        │   ├── workflow/
        │   │   ├── ScheduleLifecycle.kt          Composable helper for Phase A scheduling
        │   │   ├── DispatcherWorkflow.kt
        │   │   └── DispatcherWorkflowImpl.kt
        │   ├── schedule/
        │   │   └── DispatchScheduleSetup.kt
        │   ├── config/
        │   │   └── ExposedDatabaseConfig.kt
        │   └── metrics/
        │       └── DispatchMetrics.kt
        │
        └── payment/
            ├── model/
            │   ├── PaymentRequest.kt
            │   ├── PaymentExecContext.kt          (no fee fields)
            │   └── PaymentStatus.kt               SCHEDULED/ACCEPTED/PROCESSING
            ├── context/
            │   └── PaymentContextActivitiesImpl.kt (implements SchedulableContextActivities)
            ├── init/
            │   ├── PaymentInitWorkflow.kt
            │   ├── PaymentInitWorkflowImpl.kt    (composes ScheduleLifecycle)
            │   ├── PaymentInitActivities.kt
            │   └── PaymentInitActivitiesImpl.kt  (specific imports)
            └── exec/
                ├── PaymentExecWorkflow.kt        (execute(paymentId, contextJson))
                ├── PaymentExecWorkflowImpl.kt    (pure business logic, zero framework awareness)
                ├── PaymentExecActivities.kt
                └── PaymentExecActivitiesImpl.kt
```
