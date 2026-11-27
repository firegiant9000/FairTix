-- Lock table for ShedLock (SchedulerLockConfig): with more than one application instance,
-- each @Scheduled job takes a row here so only one instance runs it per tick. One row per
-- job name; lock_until is when the lock lapses if the holder dies mid-run. Times are written
-- by the database clock (usingDbTime), in UTC, so instance clock skew does not matter.
-- Standard ShedLock DDL for PostgreSQL. No JPA entity maps it; Hibernate validate ignores it.
--
-- Operators: to release a stuck lock, UPDATE shedlock SET lock_until = timezone('utc', now())
-- WHERE name = '<job>'. Never DELETE rows: a running instance only updates rows it has seen,
-- so after a DELETE that job stops on every instance until they restart.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
