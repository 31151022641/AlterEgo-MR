"""Compatibility entry point for the single supported Windows avatar companion.

No UDP receiver, face renderer, camera-backend downloads or desktop eye controller
are started here. Build the complete native bundle with windows/build.ps1.
"""

from pathlib import Path
import os
import subprocess
import sys


def main() -> int:
    if sys.platform != 'win32':
        print('AlterEgo MR Webcam requires Windows 11 x64.', file=sys.stderr)
        return 1
    project = Path(__file__).resolve().parent
    candidates = [
        project / 'dist' / 'AlterEgoWebcam.exe',
        project.parent / 'windows' / 'dist' / 'AlterEgoWebcam.exe',
        Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'AlterEgo MR Webcam' / 'AlterEgoWebcam.exe',
    ]
    executable = next((candidate for candidate in candidates if candidate.is_file()), None)
    if executable is None:
        print('The native receiver bundle is missing. Build windows/build.ps1 or extract the complete Windows release.', file=sys.stderr)
        return 1
    # Pass only the native receiver's documented options; legacy UDP flags no longer apply.
    return subprocess.call([str(executable), *sys.argv[1:]], cwd=executable.parent)


if __name__ == '__main__':
    raise SystemExit(main())
