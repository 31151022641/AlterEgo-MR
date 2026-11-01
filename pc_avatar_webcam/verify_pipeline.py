"""Run the supported native companion's protocol/media checks."""
from pathlib import Path
import subprocess
import sys

if __name__ == '__main__':
    verifier = Path(__file__).resolve().parent.parent / 'windows' / 'verify.py'
    raise SystemExit(subprocess.call([sys.executable, str(verifier)], cwd=verifier.parent))
