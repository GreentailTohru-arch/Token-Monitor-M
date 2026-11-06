"""A pinned companion must survive updates/removal of the desktop installer."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class PreparedRuntimeTest(unittest.TestCase):
    def test_reuse_without_installed_archive_preserves_provenance(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            runtime = root / 'runtime'
            app = runtime / 'resources/app'
            app.mkdir(parents=True)
            (app / 'package.json').write_text(json.dumps({'version': '0.87.0'}))
            marker = runtime / 'mobile-engine-version.json'
            marker.write_text(json.dumps({'sourceAsarSha256': 'original-hash'}))
            profile = root / 'profile'
            profile.mkdir()
            (profile / 'settings.json').write_text('{"theme":"dark"}')
            env = dict(os.environ, APPDATA=str(root / 'empty-appdata'))
            result = subprocess.run([sys.executable, str(Path(__file__).parent / 'engine/prepare_engine.py'),
                '--installed', str(root / 'missing-installation'), '--runtime', str(runtime),
                '--profile', str(profile)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(marker.read_text())['sourceAsarSha256'], 'original-hash')
            self.assertEqual(json.loads((app / 'package.json').read_text())['main'], 'mobile-main.js')
            self.assertEqual(json.loads((profile / 'settings.json').read_text()), {'theme': 'dark'})


if __name__ == '__main__':
    unittest.main()
