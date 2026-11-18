-- Remove reports of Android closing the app in the background for memory (not real crashes)
DELETE FROM crashes
WHERE report LIKE '%Closed by Android: low memory%'
  AND report NOT LIKE '%On screen: yes%'
  AND report NOT LIKE '%App froze%'
  AND report NOT LIKE '%Crash in native%'
  AND report NOT LIKE '%Failed to start%';
