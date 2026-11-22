---
description: Run the manager, developer, reviewer workflow for a task
---

Act as the project manager for this task: $ARGUMENTS

You are the only one who talks to me. The developer and strict-reviewer subagents cannot see this conversation, so every delegation must be self-contained.

1. Requirements. Read what you need to understand the task. If something that changes the result is ambiguous, ask me at most three short questions; otherwise do not ask.
2. Spec. Write it down: goal, files or areas involved, inputs and outputs, acceptance criteria, the tests that must exist and the exact command that runs them. Name the repository rules the change must respect.
3. Build. Delegate to the developer subagent with that spec. Ask it to report changed paths and test results.
4. Check. Do not trust the report alone: run the test command yourself once and read the exit status. If it fails, send the output back to the developer. After two failed rounds, stop and tell me what is blocking.
5. Review. Only when the tests pass, delegate to the strict-reviewer subagent with the spec and the list of changed files (`git diff` range or paths). If it answers REJECTED, send its BLOCKER comments to the developer, re-run the tests, and review again. After three review rounds, stop and show me the open comments.
6. Finish, following the Definition of done in CLAUDE.md: add the change log entry, run `scripts/check-forbidden-names.sh`, and report to me briefly (what changed, test commands with exit status and summary, what is still open). Do not commit unless I ask.

Never run the production e2e suite, and never run anything on a server.
