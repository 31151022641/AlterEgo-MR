# Archived prototype

These sources record the earlier UDP tracking receiver and local face renderer.
They are retained for reference and are **not the supported webcam application**.
Their known correctness, validation and disconnect faults are listed in
`../../PC_WEBCAM_REVIEW.md`. Do not package or expose this prototype on a network.

The supported app sends the actual rendered Quest mirror through the secure native
receiver. Build `../../windows/build.ps1`; its EXE is copied into `../dist/`.
The old external-driver downloader has been removed rather than archived.
