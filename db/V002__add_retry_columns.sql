-- 타임아웃 재시도 로직 지원을 위한 컬럼 추가

ALTER TABLE pending_jobs
    ADD COLUMN IF NOT EXISTS retry_count    INT       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_failed_at TIMESTAMP;

-- retry 대상 조회 최적화 인덱스
CREATE INDEX IF NOT EXISTS idx_pending_jobs_retry
    ON pending_jobs (retry_count, scored_at)
    WHERE status = 'PENDING';

COMMENT ON COLUMN pending_jobs.retry_count    IS '재시도 횟수. CompletionTracker 타임아웃 시 증가.';
COMMENT ON COLUMN pending_jobs.last_failed_at IS '마지막 타임아웃 발생 시각. 지수 백오프 기준.';