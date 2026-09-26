"""Exercise deployment safety using fake tools; never touch an attached phone.

Run: python3 -m unittest discover -s tests -p 'test_deploy_android.py'
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[1]
MOCK_TOOL = r'''#!/usr/bin/env python3
import os, pathlib, sys
tool = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['MOCK_LOG'], 'a') as log:
    log.write(tool + ' ' + ' '.join(args) + '\n')
if tool == 'adb':
    if args[0] == 'devices':
        print('List of devices attached\n' + os.environ.get('MOCK_DEVICES', 'phone\tdevice'))
    elif args[:2] == ['-s', 'phone'] or args[0] == '-s':
        serial, args = args[1], args[2:]
        if args == ['get-state']:
            print('device')
        elif args[:2] == ['shell', 'getprop']:
            print('1' if serial == 'virtual' else '0')
        elif args[:3] == ['shell', 'pm', 'path']:
            if os.environ.get('MOCK_FRESH') != '1':
                print('package:/data/app/base.apk')
        elif args[:3] == ['shell', 'dumpsys', 'package']:
            print('versionCode=120\nversionName=test\nfirstInstallTime=2026-09-26 12:00:00')
        elif args[0] == 'install':
            if os.environ.get('MOCK_FAIL_INSTALL') == '1':
                print('Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]')
                sys.exit(1)
            print('Success')
        elif args[:3] == ['shell', 'am', 'start']:
            print('Error: activity unavailable' if os.environ.get('MOCK_FAIL_LAUNCH') else 'Status: ok')
        else:
            sys.exit('Unexpected adb operation: ' + str(args))
elif tool == 'aapt':
    print("package: name='" + os.environ.get('MOCK_PACKAGE', 'com.audiobookshelf.app.debug') + "' versionCode='120'")
elif tool == 'node':
    print('20')
elif tool in ('npm', 'npx', 'git', 'gradlew'):
    pass
else:
    sys.exit('Unexpected tool: ' + tool)
'''


class DeployAndroidTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        shutil.copyfile(REPO / 'scripts/deploy_android.sh', self.root / 'scripts/deploy_android.sh')
        apk = self.root / 'android/app/build/outputs/apk/debug/app-debug.apk'
        apk.parent.mkdir(parents=True)
        apk.write_text('mock APK')
        tools = self.root / 'bin'
        tools.mkdir()
        for name in ('adb', 'aapt', 'node', 'npm', 'npx', 'git'):
            tool = tools / name
            tool.write_text(MOCK_TOOL)
            tool.chmod(0o755)
        gradle = self.root / 'android/gradlew'
        gradle.write_text(MOCK_TOOL)
        gradle.chmod(0o755)
        (self.root / '.android').mkdir()
        (self.root / '.android/debug.keystore').write_text('mock key')
        self.log = self.root / 'commands.log'
        self.env = dict(os.environ, PATH=f'{tools}:{os.environ["PATH"]}',
                        HOME=str(self.root), MOCK_LOG=str(self.log))

    def run_script(self, *args, **env):
        result = subprocess.run(['bash', str(self.root / 'scripts/deploy_android.sh'), *args],
                                env=dict(self.env, **env), capture_output=True, text=True)
        self.commands = self.log.read_text() if self.log.exists() else ''
        # Every scenario must be free of data removal, including error handling.
        self.assertNotIn('uninstall', self.commands)
        self.assertNotIn('pm clear', self.commands)
        self.assertNotIn('connectedDebugAndroidTest', self.commands)
        return result

    def test_full_build_and_in_place_update(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        for command in ('npm ci --omit=optional', 'npm run generate',
                        'npx --no-install cap sync android',
                        'gradlew assembleDebug -p android --no-daemon', 'adb -s phone install -r'):
            self.assertIn(command, self.commands)
        self.assertIn('Existing installation timestamp preserved', result.stdout)

    def test_multiple_phones_require_selection(self):
        devices = 'phone\tdevice\nother\tdevice'
        result = self.run_script('--skip-build', MOCK_DEVICES=devices)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(' install ', self.commands)
        result = self.run_script('--skip-build', 'other', MOCK_DEVICES=devices)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('adb -s other install -r', self.commands)

    def test_emulators_and_unauthorized_devices_are_not_selected(self):
        for devices in ('emulator-5554\tdevice', 'virtual\tdevice', 'phone\tunauthorized'):
            with self.subTest(devices=devices):
                result = self.run_script('--skip-build', MOCK_DEVICES=devices)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn(' install ', self.commands)

    def test_production_apk_is_rejected(self):
        result = self.run_script('--skip-build', MOCK_PACKAGE='com.audiobookshelf.app')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(' install ', self.commands)

    def test_signature_failure_stops_without_launch_or_cleanup(self):
        result = self.run_script('--skip-build', MOCK_FAIL_INSTALL='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.commands.count(' install -r '), 1)
        self.assertNotIn('am start', self.commands)

    def test_missing_key_stops_before_building(self):
        (self.root / '.android/debug.keystore').unlink()
        result = self.run_script('--native-only')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('gradlew', self.commands)
        self.assertNotIn(' install ', self.commands)

    def test_first_install_and_launch_failure(self):
        result = self.run_script('--skip-build', MOCK_FRESH='1')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('First fork installation', result.stdout)
        result = self.run_script('--skip-build', MOCK_FAIL_LAUNCH='1')
        self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main()
