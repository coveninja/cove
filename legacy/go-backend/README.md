# Retired Go backend

This marker documents Cove's retired pre-Kotlin backend. The migration worktree
keeps an ignored, reversible copy here while the cutover settles; permanent
source history remains available through Git. None of it is included in new
commits, Gradle, CI, release packaging, Flatpak sources, or the application
runtime.

Do not add new product behavior here. The maintained backend lives in
`app/backend`, and the desktop application starts it in-process.

User-data rollback does not depend on the source archive: run the desktop
launcher with `--export-legacy` to write legacy-compatible JSON sidecars from
SQLite.
