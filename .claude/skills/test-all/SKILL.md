---
name: test-all
description: "Run the desktop client's unit tests and its integration tests against the real backend in Docker, verify the test reports are fresh, then report a summary of all results."
---

Run unit tests (surefire) and integration tests (failsafe, Testcontainers), then report a summary of results.

Run from the repository root.

```bash
mvn verify 2>&1 | grep -E "Tests run:|FAIL|ERROR|BUILD"
```

## Prerequisites

- **Docker must be running.** The integration tests start three containers: the dynamic-form backend, the
  oauth2-server auth server, and `postgres:16-alpine`. The first run pulls all three.
- **SnapFX must be in the local repository.** If the build cannot resolve `org.snapfx:snapfx-core`, run
  `bash scripts/install-snapfx.sh`. It is not on Maven Central.
- **Host port 9000 must be free.** The auth server binds it on a fixed port, because the `iss` claim has to
  match the issuer the backend expects. A locally running auth server will collide.
- **`ghcr.io/markoniemi/dynamic-form` must be pullable.** It is a private package unless that has been changed,
  so the integration tests need `docker login ghcr.io` with a `read:packages` token. Verify with:
  `curl -s "https://ghcr.io/token?scope=repository%3Amarkoniemi%2Fdynamic-form%3Apull&service=ghcr.io"` —
  a token in the response means it is public, `UNAUTHORIZED` means a login is required.

Startup is slow by design (the backend waits on `/actuator/health`, with a 2 minute timeout), so a `verify` run
taking minutes is normal, not a hang.

## Reporting

Before reporting, check that `target/surefire-reports/*.txt` and `target/failsafe-reports/*.txt` were modified
by this run. If they are stale, say so and do not report the tests as passing.

After running, summarize:
- How many unit and integration tests passed / failed / skipped
- List any failing tests with their error messages
- If a failure is a container that would not start, quote the container log rather than only the Java stack
  trace — the cause is usually in the backend's output
- If all tests pass, confirm with a single line
