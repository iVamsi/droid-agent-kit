-- compose_recomposition.sql v1
-- Recomposition counts from Compose composition tracing.
--
-- Composition tracing is opt-in on the app side: it needs the
-- androidx.compose.runtime:runtime-tracing dependency, and without it Compose emits no
-- per-composable slices at all. This query therefore returns no rows on an ordinary trace,
-- which the analysis reports as "not measured" rather than as "zero recompositions".
--
-- runtime-tracing names each slice after the composable plus its source location, e.g.
-- "ProductRow (ProductList.kt:42)". The GLOB below selects exactly that shape so ordinary
-- app trace sections on the same thread are not miscounted as recompositions.
SELECT
  s.name AS composable_name,
  COUNT(*) AS recomposition_count,
  SUM(s.dur) AS total_dur_ns
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
WHERE t.name = 'main'
  AND s.name GLOB '* (*.kt:*)'
GROUP BY s.name
ORDER BY recomposition_count DESC
LIMIT 25;
