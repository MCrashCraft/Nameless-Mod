"""Tests for release selection and packaged metadata auditing; no game is launched."""
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import build


class BuildTargetTests(unittest.TestCase):
    def test_only_published_exact_targets_are_selected(self):
        self.assertEqual(90, len(build.matrix()))
        self.assertEqual(67, len(build.matrix(include_quilt=False)))
        for game in ('1.20.5', '1.21.2'):
            with self.assertRaises(ValueError):
                build.plan('forge', game)
        with self.assertRaises(ValueError):
            build.plan('fabric', '1.20.1-26.3')

    def test_quilt_reuses_fabric_without_claiming_runtime_success(self):
        selected = build.plan('quilt', '26.3')
        self.assertEqual('fabric', selected['project'])
        self.assertEqual(25, selected['game_java'])

    def test_old_games_retain_old_runtime_even_when_loom_needs_new_jdk(self):
        selected = build.plan('fabric', '1.20.1')
        self.assertEqual(17, selected['game_java'])
        self.assertEqual(25, selected['build_java'])
        self.assertFalse(selected['native_test_server'])
        self.assertFalse(build.plan('forge', '1.20.6')['native_test_server'])
        self.assertTrue(build.plan('forge', '1.21')['native_test_server'])
        self.assertTrue(build.plan('forge', '1.21.4')['native_test_server'])
        self.assertTrue(build.plan('forge', '1.21.5')['native_test_server'])
        self.assertTrue(build.plan('forge', '26.3')['native_test_server'])

    def test_early_neoforge_selects_the_official_older_toolchain(self):
        for game in ('1.20.2', '1.20.3'):
            selected = build.plan('neoforge', game)
            self.assertEqual('8.14.5', selected['gradle'])
            self.assertEqual(17, selected['build_java'])
        self.assertEqual('8.14.5', build.plan('neoforge', '1.20.5')['gradle'])
        self.assertEqual(21, build.plan('neoforge', '1.20.5')['build_java'])
        self.assertEqual('9.2.1', build.plan('neoforge', '1.20.1')['gradle'])
        self.assertEqual('9.2.1', build.plan('neoforge', '1.20.4')['gradle'])

    def test_a_broad_range_or_bundled_game_is_rejected(self):
        for invalid in ('range', 'game_class', 'addon', 'test_fixture', 'junit', 'license'):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as folder:
                artifact = Path(folder) / 'fixture.jar'
                with zipfile.ZipFile(artifact, 'w') as archive:
                    archive.writestr('fabric.mod.json', json.dumps({'depends': {'minecraft':
                        '>=1.20.1' if invalid == 'range' else '1.20.1'}}))
                    if invalid != 'license':
                        archive.writestr('META-INF/licenses/core/LICENSE', 'test fixture')
                    if invalid == 'game_class':
                        archive.writestr('net/minecraft/Forbidden.class', '')
                    if invalid == 'addon':
                        archive.writestr('dev/example/namelessexchange/Forbidden.class', '')
                    if invalid == 'test_fixture':
                        archive.writestr('dev/mcrashcraft/namelesstest/Forbidden.class', '')
                    if invalid == 'junit':
                        archive.writestr('org/junit/Forbidden.class', '')
                with self.assertRaises(ValueError):
                    build.audit_artifact(artifact, build.plan('fabric', '1.20.1'))

    def test_old_neoforge_metadata_filename_is_accepted(self):
        with tempfile.TemporaryDirectory() as folder:
            artifact = Path(folder) / 'fixture.jar'
            with zipfile.ZipFile(artifact, 'w') as archive:
                archive.writestr('META-INF/mods.toml', 'versionRange="[1.20.2]"')
                archive.writestr('META-INF/licenses/core/LICENSE', 'test fixture')
            receipt = build.audit_artifact(artifact, build.plan('neoforge', '1.20.2'))
            self.assertFalse(receipt['runtime_verified'])
            self.assertEqual(64, len(receipt['sha256']))


if __name__ == '__main__':
    unittest.main()
