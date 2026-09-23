# Vendored copy — yctun

Source of this copy: `/home/i3sey/cdn/tunnel` on the maintainer machine
(local worktree, no git history). Copy made 2026-08-25.

Both client (`cmd/relayc`) and server (`cmd/relayd`) are now maintained in
this tree. The v2 GET-only server is built from this source and runs as the
isolated CDN TLS origin on de2:18443. The v1 code at the source path is **not**
protocol-compatible and must not be redeployed. Server configuration and
secrets are private and are not in this repository.

Recommended practice before it becomes a real upstream dependency:
- publish yctun as its own git repository (relayd/relayc);
- pin a commit here and record the SHA-256 in this file;
- update via the pinned commit instead of copy-paste.

To update: review changes here, run Go race tests and Android unit tests, rebuild
`librelayc.so`, verify 16 KB ELF/APK alignment, and test an independent server
upgrade with rollback. Do not copy the old source worktree over v2.