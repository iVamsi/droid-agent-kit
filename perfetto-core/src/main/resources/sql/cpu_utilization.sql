-- cpu_utilization.sql v1
-- Per-process CPU time from the sched_slice table. Returns empty on traces without sched data.
SELECT
  process_name,
  CAST(SUM(dur) AS REAL) / 1000000000.0 AS cpu_seconds
FROM sched_slice
GROUP BY process_name
ORDER BY cpu_seconds DESC
LIMIT 10;
