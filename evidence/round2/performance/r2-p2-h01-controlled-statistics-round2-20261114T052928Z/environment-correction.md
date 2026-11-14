# environment.json formatting correction

`databaseSettings` in `environment.json` was written by concatenating `pg_settings.setting` and `pg_settings.unit`
without a separator, so values with an `8kB` unit are ambiguous (for example `shared_buffers=163848kB` means
16384 × 8 kB). No measured value is affected. The same cluster's settings, read with `current_setting()` after the
run (they were not changed in between), are:

effective_cache_size=4GB; fsync=on; jit=on; max_connections=80; port=55436; random_page_cost=4; server_version=18.7;
shared_buffers=128MB; synchronous_commit=on; work_mem=4MB

The harness now records `current_setting(name)` for later runs.
