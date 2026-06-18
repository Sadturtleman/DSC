-- V001: pending_jobs (HDFS Auto-Tiering shared contract)
--
-- 본 마이그레이션은 scoring engine / batch scheduler / completion tracker
-- 가 공유하는 단일 큐 테이블을 정의한다. 계약 문서:
--   docs/interfaces/pending-jobs-schema.md

BEGIN;

------------------------------------------------------------
-- 1. enums
------------------------------------------------------------
DO $$ BEGIN
    CREATE TYPE tier AS ENUM ('HOT', 'WARM', 'COLD');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE job_status AS ENUM (
        'PENDING',
        'DISPATCHED',
        'IN_PROGRESS',
        'COMPLETED',
        'FAILED',
        'CANCELLED'
    );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

------------------------------------------------------------
-- 2. table
------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pending_jobs (
    job_id               BIGSERIAL PRIMARY KEY,
    file_path            TEXT             NOT NULL,
    file_size_bytes      BIGINT           NOT NULL CHECK (file_size_bytes >= 0),
    current_tier         tier             NOT NULL,
    target_tier          tier             NOT NULL,
    priority_score       DOUBLE PRECISION NOT NULL,
    status               job_status       NOT NULL DEFAULT 'PENDING',

    scored_at            TIMESTAMPTZ      NOT NULL,
    dispatched_at        TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,

    retry_count          INT              NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    last_error           TEXT,
    fsimage_snapshot_id  BIGINT,

    CONSTRAINT chk_tier_change CHECK (current_tier <> target_tier)
);

------------------------------------------------------------
-- 3. indexes
------------------------------------------------------------

-- scheduler 의 SKIP LOCKED 픽업을 위한 우선순위 인덱스
CREATE INDEX IF NOT EXISTS idx_pending_jobs_pickup
    ON pending_jobs (priority_score ASC, scored_at ASC)
    WHERE status = 'PENDING';

-- tracker 의 타임아웃 스캔 인덱스
CREATE INDEX IF NOT EXISTS idx_pending_jobs_dispatched_at
    ON pending_jobs (dispatched_at)
    WHERE status IN ('DISPATCHED', 'IN_PROGRESS');

-- 동일 path 활성 job 중복 방지 (계약상 가장 중요한 invariant)
CREATE UNIQUE INDEX IF NOT EXISTS uniq_active_job_per_path
    ON pending_jobs (file_path)
    WHERE status IN ('PENDING', 'DISPATCHED', 'IN_PROGRESS');

COMMIT;
