---
description: Run the manager, developer, reviewer workflow for a task
---

Act as the project manager for this task: $ARGUMENTS

You are the only one who talks to me. The developer and strict-reviewer subagents cannot see this conversation, so every delegation must be self-contained: paste the spec, the documents to read, the decisions that bind it and the exact commands.

0. Start. Run `git branch --show-current` and `git status --short`. If HEAD is detached, or the branch is not the one the task names, stop and tell me. Do not start on a tree with changes that are not the task's, unless the task says what to do with them.
1. Requirements. Read what you need to understand the task. If the task states its decisions, do not ask about them. If something else that changes the result is ambiguous, ask me at most three short questions; otherwise do not ask.
2. Spec. Write it down: goal, files or areas involved, inputs and outputs, acceptance criteria, the tests that must exist and the exact command that runs them. Name the repository rules the change must respect and the decisions (D-numbers) that bind it. If the task gives a size limit, tell the developer.
3. Build. Delegate to the developer subagent with that spec. Pass `model: opus` for it when the spec touches a migration, SQL functions or triggers, erasure, concurrency or the history module; otherwise leave the default. Ask it to report changed paths, test results and every assumption. The developer runs only the targeted tests for what it touched (`-Dtest=<Class>`, and `-Dtests.random.scale=0.1` for a random test); it never runs the quick or the full tier, and never the e2e suite.
4. Check. Do not trust the report alone. When the developer reports done, you run the quick tier once (see "Test tiers" below) and read its exit status, its Tests/Errors/Skipped lines and its wall time. If it fails, send the output to the developer, who fixes it with targeted tests; then you run the quick tier once more. After two failed rounds, stop and tell me what is blocking.
   You run the full tier only when the task changes test infrastructure (base test classes, test configuration or properties, the pom's test setup, the container or the database setup) or random-test code (`*Random*` classes). Otherwise the quick tier is the check.
   Frontend-only tasks: the developer runs the related-files run for what it touched (`npx vitest related <changed files> --run`); you run `npm test` once in `frontend/`. Local Playwright (`cd e2e && npm run e2e:local`) only when the task touches a UI flow the e2e suite covers. Never the production project.
5. Review. Only when the tests pass: the reviewer cannot run git, so first write the diff of this task's paths to a file (`git add -N <new paths>` for new files, then `git diff -- <paths> > <scratchpad>/review-<task>.diff`), check it is not empty, and delegate to the strict-reviewer subagent with the spec, the diff file's path, the list of changed files and your run summary (tier, command, exit status, Tests/Errors/Skipped counts, wall time). The reviewer does not run tests. If it answers REJECTED, send its BLOCKER comments to the developer, re-run the tests the change needs (targeted, then the quick tier once), rewrite the diff file and review again. After three review rounds, stop and show me the open comments.
6. Finish, following the Definition of done in CLAUDE.md: add the change log entry, run `scripts/check-forbidden-names.sh` (the working tree before a commit, `--log <base>..HEAD` after it), and report to me briefly: what changed, the test commands with exit status and the whole summary (with the tier and wall time), the decisions you made (each marked "(manager)" with its D-number), assumptions, what is still open. Quote the new change log entry.
7. Commit only when the task says I ask for it, or when I ask later. Stage exactly the task's paths (never `git add -A` or `git add .`), use the commit subjects the task names, do not push.

Test tiers (backend, run from `backend/`):

- quick (manager, once per task, forked): `./mvnw -B clean test -Dtests.random.scale=0.1`
- targeted (developer): `./mvnw -B test -Dtest=<Class>[,<Class>...] [-Dtests.random.scale=0.1] [-Dtests.forks=1]`
- full (CI, and the manager only as stated in step 4): `./mvnw -B clean verify`
- frontend (`frontend/`): related files `npx vitest related <files> --run`; full `npm test`.

The numbers, the forking setup and the measured wall times are in README.md, "Backend tests".

Never run the production e2e suite, and never run anything on a server.
