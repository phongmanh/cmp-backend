---
description: Security review of the current change against the backend rules
---

Review the uncommitted changes (`git diff` plus `git diff --cached`) as a security reviewer for a Ktor backend. Do not change any code — report only.

Go through this list and, for each item, say whether it passes, fails, or does not apply. Quote the file and line for anything that fails.

**Secrets**
1. Any credential, token, key, or connection string written into code, yaml, or a test fixture?
2. Anything sensitive added outside `.gitignore`?

**Authentication and authorization**
3. Does every new or changed route sit inside `authenticate("auth-jwt")`? If a route is public, is that clearly intentional?
4. Is the caller identity taken from the JWT principal rather than from a body, query parameter, or header?
5. Is there an ownership or role check on every route that reads or writes a record belonging to a specific user?

**Input**
6. Is every incoming field validated for length, range, format, and allowed values?
7. Any SQL built by string concatenation, or user text passed into `exec()`?
8. Any unbounded list response, or a `limit` parameter without a maximum?

**Output**
9. Does any response DTO leak a password hash, internal identifier, audit column, or another user's data?
10. Does any error response include a stack trace, SQL text, or a library detail?

**Logging**
11. Is a token, password, authorization header, or full request body written to the log?
12. Is personal data masked?

**Reliability**
13. Is every database call wrapped in `dbQuery { }`?
14. Does any multi-table write run outside a transaction?
15. Any `!!`, `runBlocking`, or `GlobalScope` in the change?

**Rate limiting**
16. If the change adds a login, registration, password reset, or message-sending endpoint, is it rate limited?

Finish with a short verdict: safe to merge, safe after fixes (list them in priority order), or do not merge.
