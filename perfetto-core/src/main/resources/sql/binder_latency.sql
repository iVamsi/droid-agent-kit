-- binder_latency.sql v1
-- Average binder transaction latency by AIDL name. Defensive against missing binder data.
SELECT
  name,
  COUNT(*) AS transactions,
  AVG(dur) AS avg_dur_ns,
  MAX(dur) AS max_dur_ns
FROM slice
WHERE category = 'binder'
GROUP BY name
ORDER BY avg_dur_ns DESC
LIMIT 10;
