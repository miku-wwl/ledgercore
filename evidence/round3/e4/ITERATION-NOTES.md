# E4 Iteration Notes

These are resolved development findings, not the final acceptance run. The final clean run is recorded in `LOCAL-VALIDATION.md`.

- A test run against a missing database failed during Spring context startup. The isolated database was then created under the intended test owner, and the fresh full run passed.
- Spring Boot 4's Jackson 3 setup did not expose the Jackson 2 `ObjectMapper` bean initially assumed by the event store. The event store now uses the available Jackson 3 `JsonMapper`.
- A PostgreSQL trigger initially rejected a valid first approval metadata update. The trigger was narrowed to permit the one `SUBMITTED -> APPROVED` transition while the intent facts remain immutable.
- A PostgreSQL retry test found that the attempt counter's nondecreasing rule also blocked authorized dead-letter recovery. V8 now permits attempt reset only when moving `DEAD -> PENDING`; event facts stay immutable.
- A pre-existing migration compatibility assertion counted the newly seeded `CLEARING_PAYABLE` system account in a historical customer-balance fingerprint. The new system account is excluded from that historical comparison; V1–V7 are unchanged.
- The old organization browser journey expected a payroll maker to transfer directly from a company account under the prior Policy A behavior. Round 3 Policy B removes corporate accounts from generic transfer sources; backend tests exercise the approved payment path, and the browser regression now verifies the company account is not offered as a generic source.
