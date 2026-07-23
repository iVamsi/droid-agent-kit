-- frame_jank.sql v1
-- FrameTimeline jank rows. Returns empty when the frametimeline data source was not captured.
SELECT
  ts,
  dur,
  jank_tag,
  layer_name
FROM frame_timeline_jank
LIMIT 50;
