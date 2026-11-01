# Third-party notices

AlterEgo MR is based on Meta's Passthrough Camera API sample. The upstream
Apache License 2.0 text is retained in [LICENSE](LICENSE), together with the
existing copyright notices in source files. Modified files and added application
features are recorded in this repository's Git history and documentation.

Third-party libraries and assets retain their own licenses. The root license
does not replace those component licenses.

- **Meta Spatial, Platform and Avatars SDKs and sample avatar assets:** their
  existing license texts are packaged under `app/src/main/assets/licenses/`.
  The supplied Avatars SDK third-party notice is retained in
  `app/src/main/assets/licenses/Meta-Avatars-Third-Party-Notices.html`.
  The pinned native runtime provenance is described in
  `app/src/main/jniLibs/README.md`.
- **Rain and Snow:** Blender Foundation/Blender Studio character adaptations,
  licensed CC BY 4.0. Sources, modifications and credits are recorded in
  [ARTIST_PORTRAITS_LICENSES.md](ARTIST_PORTRAITS_LICENSES.md). Full attribution
  and license text are also packaged in `app/src/main/assets/portraits/`.
- **Other dependencies:** dependency declarations and their package-provided
  notices remain in the Android, Windows and macOS projects. Retain relevant
  notices when producing distributions.

Private release-signing files and local account credentials are not part of
this repository. Downloaded Blender source archives are development references
and are not checked into Git; use the official source links in the credits.
