# M7 Implementation Plan: Issues #134, #135, #136, #138, #139 + Integration Tests

**Branch:** `feature/issues-134-135-136-138-139-m7-implementation`  
**Base:** `feature/issues-127-128-129-131-132-133-m6-implementation`  
**Date:** 2026-04-20  
**Status: ALL ITEMS COMPLETE**

---

## Completed

| Issue | Title | Evidence |
|-------|-------|----------|
| #134 | Queue SSE updates | `QueueSseService.java` with emitter registry; `/stream` endpoint in `QueueController`; `WaitingRoom.js` uses `EventSource`; both schedulers call `broadcast()` |
| #135 | Transfer velocity + anti-scalping | `checkVelocity()` in `TransferService` with Redis sorted-set; `TransferVelocityExceededException`; CRITICAL-tier block with audit event; config in `application.properties` |
| #136 | Refund auto-approval + fraud score | `RiskScoringService` injected into `RefundService`; tier check before threshold gate; `REFUND_HELD_FRAUD_RISK` audit event for HIGH/CRITICAL users |
| #138 | Step-up and fraud audit visibility | `AdminAuditController` at `GET /api/admin/audit`; `AuditLogRepository.findWithFilters()`; audit trail panel in `AdminFraudPage.js`; `V26__add_audit_log_fraud_index.sql` |
| #139 | Frontend purchase cap enforcement cues | Cap badge (`Limit: N per person`) added to event list cards in `Events.js`; CSS class `event-card-status--cap`; two new tests in `Events.test.js` (17/17 passing) |
| — | Queue → hold → order → ticket E2E test | `QueueToTicketIntegrationTest.java` — 2 tests: full happy-path + unadmitted-user block (2/2 passing) |
