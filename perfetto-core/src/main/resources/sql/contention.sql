-- contention.sql v1
-- Lock/contention waits. Defensive: returns empty when no contention data source was captured.
SELECT
  name,
  COUNT(*) AS waits,
  AVG(dur) AS avg_wait_ns
FROM slice
WHERE name LIKE '%lock%' OR name LIKE '%Contention%'
GROUP BY name
ORDER BY waits DESC
LIMIT 10;
