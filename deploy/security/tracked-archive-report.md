# Tracked archive audit

Audit date: 2026-08-12

`.github/scripts/audit_tracked_files.py` enumerated Git-tracked files and inspected every ZIP member it can read. It found no tracked PID, dump, token file, keystore, private key payload, or strong token signature.

The following seven archives are already tracked:

1. `deliverables/yulin-youxian-admin-source-no-comments-20260714.zip`
2. `deliverables/yulin-youxian-backend-source-no-comments-20260714.zip`
3. `deliverables/yulin-youxian-miniprogram-source-no-comments-20260714.zip`
4. `deliverables/yulin-youxian-smart-delivery-three-end-source-no-comments-20260714.zip`
5. `design/prototypes/wechat-miniapp-ui-prototype-stitch.zip`
6. `packages/yulin-youxian-miniprogram-source.zip`
7. `third_party/xprinter-xp58/芯烨票据产品SDK开发包 Windows 2.3.1.zip`

These files were not deleted automatically:

- The `deliverables/` and `packages/` archives may be customer handoff records.
- The prototype archive may be a design source.
- The Xprinter archive is third-party provenance and may be needed to rebuild the print agent.
- Deleting a tracked file in a new commit does not remove it from Git history. If a later scan finds a real secret, repository administrators must revoke it first, then coordinate a history rewrite and fresh clones.

CI now re-runs the member audit and a Git-history secret scan. Unsupported future `.7z` or `.rar` files fail the audit instead of being silently trusted.
