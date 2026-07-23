-- main_thread_slices.sql v1
-- Longest slices on the main thread. Defensive: returns empty when thread/slice tables are absent.
SELECT
  s.ts AS ts,
  s.dur AS dur_ns,
  s.name AS slice_name
FROM slice s
JOIN thread t ON s.utid = t.id
WHERE t.name = 'main'
ORDER BY s.dur DESC
LIMIT 20;
