# E10 WAL archive helper — implementation test

- Scope: local verification of `archive-wal.ps1` before configuring it as the isolated PostgreSQL archive command. No PostgreSQL cluster, database, backup, WAL, or financial record was created or touched by this test.
- Test directory: ignored `work/e10-lab/archiver-test-20261114T150214Z/`.
- Fixture: synthetic text bytes named as one WAL segment; no real WAL contents.
- Results: first copy succeeded and SHA-256 matched; identical replay succeeded; replay with different bytes failed closed and left the original archive hash unchanged; a path-traversal filename was rejected.
- Verdict: **PASS for the archive helper's local fixture contract only.** This does not establish that PostgreSQL invoked the helper successfully or that a recoverable WAL chain exists. Those claims require the registered E10 database experiment.
