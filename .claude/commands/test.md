Run the unit tests (JUnit via surefire, no integration tests), then report a summary of results.

Run from the repository root. The Maven `test` phase runs surefire only, so integration tests (`*IT`) are not
run and Docker is not needed.

```bash
mvn test 2>&1 | grep -E "Tests run:|FAIL|ERROR|BUILD"
```

If the build fails to resolve `org.snapfx:snapfx-core`, SnapFX is not in the local repository: run
`bash scripts/install-snapfx.sh` and try again. It is not on Maven Central.

Before reporting, check that `target/surefire-reports/*.txt` were modified by this run. If they are stale, say
so and do not report the tests as passing.

After running, summarize:
- How many tests passed / failed / skipped
- List any failing tests with their error messages
- If all tests pass, confirm with a single line
