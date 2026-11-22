---
description: Run the manager, developer, reviewer workflow for a task
---

Act as the project manager for this task: $ARGUMENTS

You are the only one who talks to me. The developer and strict-reviewer subagents cannot see this conversation, so every delegation must be self-contained: paste the spec, the documents to read, the decisions that bind it and the exact commands.

0. Start. Run `git branch --show-current` and `git status --short`. If HEAD is detached, or the branch is not the one the task names, stop and tell me. Do not start on a tree with changes that are not the task's, unless the task says what to do with them.
1. Requirements. Read what you need to understand the task. If the task states its decisions, do not ask about them. If something else that changes the result is ambiguous, ask me at most three short questions; otherwise do not ask.
2. Spec. Write it down: goal, files or areas involved, inputs and outputs, acceptance criteria, the tests that must exist and the exact command that runs them. Name the repository rules the change must respect and the decisions (D-numbers) that bind it. If the task gives a size limit, tell the developer.
3. Build. Delegate to the developer subagent with that spec. Pass `model: opus` for it when the spec touches a migration, SQL functions or triggers, erasure, concurrency or the history module; otherwise leave the default. Ask it to report changed paths, test results and every assumption.
4. Check. Do not trust the report alone: run the test command yourself once and read the exit status. If it fails, send the output back to the developer. After two failed rounds, stop and tell me what is blocking.
5. Review. Only when the tests pass: the reviewer cannot run git, so first write the diff of this task's paths to a file (`git add -N <new paths>` for new files, then `git diff -- <paths> > /tmp/review-<task>.diff`), check it is not empty, and delegate to the strict-reviewer subagent with the spec, the diff file's path and the list of changed files. If it answers REJECTED, send its BLOCKER comments to the developer, re-run the tests, rewrite the diff file and review again. After three review rounds, stop and show me the open comments.
6. Finish, following the Definition of done in CLAUDE.md: add the change log entry, run `scripts/check-forbidden-names.sh` (the working tree before a commit, `--log <base>..HEAD` after it), and report to me briefly: what changed, the test commands with exit status and the whole summary, the decisions you made (each marked "(manager)" with its D-number), assumptions, what is still open. Quote the new change log entry.
7. Commit only when the task says I ask for it, or when I ask later. Stage exactly the task's paths (never `git add -A` or `git add .`), use the commit subjects the task names, do not push.

Never run the production e2e suite, and never run anything on a server.
