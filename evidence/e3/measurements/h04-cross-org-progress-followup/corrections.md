# H04 evidence correction note

The H04 experiment itself completed successfully and its raw PostgreSQL output is preserved in `commands-and-results.log`. The first version of the runner wrote an incorrectly formatted `capturedAtUtc` value because the PowerShell expandable here-string evaluated `$(Get-Date)` before the chained method calls. The original `environment.json` was therefore not valid ISO-8601 metadata.

The timestamp below is reconstructed from the output directory creation time, which the runner created immediately before writing environment metadata and invoking psql. The runner now formats a UTC timestamp before expanding the JSON here-string. No workload SQL, database rows, query plans, or measured values were changed or rerun.

Corrected `capturedAtUtc`: `2026-11-14T03:18:41.6723731Z`.

The runner correction is committed separately after the preregistration and after the one-time experiment; it does not change the measured application source baseline (`230e5873ba7f0d1c7df546eb1e81622fa12a8cc0`). The corrected runner also chooses a fresh timestamped output directory by default, so a replay on a new disposable database will not collide with the committed evidence. The SQL still refuses to duplicate H04 fixture rows in a database that already contains them.
