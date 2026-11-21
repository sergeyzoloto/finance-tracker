---
paths:
  - "**/package.json"
  - "**/package-lock.json"
---

## Testing

(D-669, since UX-16's second commit; `9478ece`'s lock, written by the laptop's npm 11.6.2, failed CI's `npm ci`, while UX-16's tests and build had run on the `node_modules` already installed.) For every stage: a commit that changes a `package.json` or a lock file runs `npm ci` from a clean copy before it is committed, a copy of the whole repository's tree at the commit (`git archive`, since `frontend/`'s tests read `docs/`, D-673), never the laptop's installed `node_modules`:

- for `frontend/`, in CI's Node for the Frontend job (Debian, the version the job's log names; v24.22.2 on 2026-11-13), e.g. `docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e CI=true -v <copy>:/repo -w /repo/frontend node:<version> …`, printing `node -v` and `npm -v`, then `npm test` and `npm run build` there;
- for `frontend/`, in the web image's build: `.github/scripts/check-web-image.sh frontend <tag>` from the copy (alpine, `node:24-alpine` as CI pulls it), under a tag of your own, removed afterwards;
- for `e2e/` likewise, when its files change.

A lock is made with CI's npm (in that container), not the laptop's (its default Node v22.19.0's npm 11.6.2 drops entries CI's npm requires, D-672). Since UX-19 `frontend/.nvmrc` names `24` and `package.json`'s `engines` `>=24 <25`, without `engine-strict` (D-820); the laptop's nvm reads `.nvmrc` only on `nvm use` (no hook on `cd`), and `nvm alias default 24` makes 24 every new shell's Node (D-826). The report names each run, its `node -v` and `npm -v`, and its exit status. Tests and builds on an installed `node_modules` don't count for this.
