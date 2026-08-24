# Vendored copy — yctun

Source of this copy: `/home/i3sey/cdn/tunnel` on the maintainer machine
(local worktree, no git history). Copy made 2026-08-25.

Only the client (`cmd/relayc`) is used by SA05; `cmd/relayd` is the server
side deployed on the VPS (origin of the Yandex Cloud CDN resource
`dom.sa05.eu.cc`) and is vendored only for reference.

Recommended practice before it becomes a real upstream dependency:
- publish yctun as its own git repository (relayd/relayc);
- pin a commit here and record the SHA-256 in this file;
- update via the pinned commit instead of copy-paste.

To update: copy the changed files from the source worktree, bump this file,
and re-run `scripts/build-relayc-arm64.sh`.