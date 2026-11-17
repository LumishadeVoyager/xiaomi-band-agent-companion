"""Execute the actual Android feed verifier on a JVM, including hostile feeds."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--jdk', type=Path)
p.add_argument('--json-jar', type=Path, required=True)
args = p.parse_args()
root = Path(__file__).resolve().parents[1]
suffix = '.exe' if os.name == 'nt' else ''
javac = args.jdk / f'bin/javac{suffix}' if args.jdk else shutil.which('javac')
java = args.jdk / f'bin/java{suffix}' if args.jdk else shutil.which('java')
with tempfile.TemporaryDirectory(prefix='band-agent-policy-') as work:
    subprocess.run([str(javac), '-encoding', 'UTF-8', '-cp', str(args.json_jar), '-d', work,
                    str(root / 'android-probe/src/io/github/lumishadevoyager/bandagent/SignedFeed.java'),
                    str(root / 'android-probe/src/io/github/lumishadevoyager/bandagent/OtaHttp.java'),
                    str(root / 'test/java/SignedFeedTest.java')], check=True)
    subprocess.run([str(java), '-cp', work + os.pathsep + str(args.json_jar), 'SignedFeedTest'], check=True)
