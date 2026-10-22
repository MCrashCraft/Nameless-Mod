"""Build and audit one exact-version NamelessMC server artifact.

Requires Python 3.10+ and the JDKs shown by --plan. Newer Forge native tests
need an explicitly accepted, disposable loopback server; see --accept-test-eula.
Wrapper downloads are checksum pinned. Set GRADLE_USER_HOME to choose the cache.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOADERS = ('forge', 'neoforge', 'fabric', 'quilt')


def versions():
    return json.loads((ROOT / 'compatibility/targets.json').read_text())['releases']


def plan(loader, game):
    rows = [row for row in versions() if row['minecraft'] == game]
    if loader not in LOADERS or len(rows) != 1:
        raise ValueError('Choose an exact loader and stable release in compatibility/targets.json')
    row = rows[0]
    if loader == 'forge' and not row['forge']['published']:
        raise ValueError('Forge has no published loader for this Minecraft release')
    project = 'fabric' if loader == 'quilt' else loader
    modern = tuple(map(int, game.split('.'))) >= (1, 20, 5)
    early_neoforge = project == 'neoforge' and game in ('1.20.2', '1.20.3', '1.20.5')
    gradle = ('9.6.0' if project == 'fabric' else
              '9.7.1' if project == 'forge' and modern else
              '8.8' if project == 'forge' else
              '8.14.5' if early_neoforge else '9.2.1')
    native_test_server = project == 'forge' and tuple(map(int, game.split('.'))) >= (1, 21)
    return dict(loader=loader, project=project, minecraft=game,
                game_java=row['java_major'],
                build_java=25 if project == 'fabric' else row['java_major'], gradle=gradle,
                native_test_server=native_test_server)


def matrix(include_quilt=True):
    result = []
    for row in versions():
        for loader in LOADERS:
            if loader == 'quilt' and not include_quilt:
                continue
            if loader == 'forge' and not row['forge']['published']:
                continue
            result.append(plan(loader, row['minecraft']))
    return result


def java_executable(major, override=None):
    home = override or os.environ.get(f'JAVA_HOME_{major}_X64') or os.environ.get('JAVA_HOME')
    executable = Path(home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if home else None
    if executable is None:
        found = shutil.which('java')
        executable = Path(found) if found else None
    if executable is None or not executable.is_file():
        raise ValueError(f'Install JDK {major} and set JAVA_HOME, or provide --java-home')
    version = subprocess.run([str(executable), '-version'], capture_output=True, text=True, check=True)
    match = re.search(r'version "(\d+)', version.stderr + version.stdout)
    if not match or int(match[1]) != major:
        raise ValueError(f'This target needs build JDK {major}; selected Java has a different version')
    compiler = executable.with_name('javac.exe' if os.name == 'nt' else 'javac')
    if not compiler.is_file():
        raise ValueError('A JDK with javac is required; a launcher JRE cannot compile the mod')
    return executable


def audit_artifact(artifact, selected):
    with zipfile.ZipFile(artifact) as archive:
        names = archive.namelist()
        if any(name.startswith(('net/minecraft/', 'net/minecraftforge/', 'net/neoforged/')) for name in names):
            raise ValueError('Game or loader classes must not be bundled')
        if any('/namelessexchange/' in name or 'cash-exchange' in name for name in names):
            raise ValueError('Unexpected removed addon content')
        if any(name.startswith(('dev/mcrashcraft/namelesstest/', 'org/junit/', 'org/opentest4j/'))
               or name == 'dev/mcrashcraft/namelessforge/NativeChatTestSupport.class' for name in names):
            raise ValueError('Test fixtures and JUnit must not be distributed in the production mod')
        game = selected['minecraft']
        if selected['project'] == 'fabric':
            metadata = json.loads(archive.read('fabric.mod.json'))
            if metadata['depends']['minecraft'] != game:
                raise ValueError('Fabric metadata must select this exact Minecraft release')
        else:
            path = next((name for name in ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml') if name in names), None)
            if path is None or f'versionRange="[{game}]"' not in archive.read(path).decode().replace(' ', ''):
                raise ValueError('Forge metadata must select this exact Minecraft release')
        if not any(name.startswith('META-INF/licenses/') for name in names):
            raise ValueError('Dependency licenses are missing')
    return dict(artifact=str(artifact), sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),
                minecraft=game, loader=selected['loader'], runtime_verified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--loader', choices=LOADERS)
    parser.add_argument('--minecraft')
    parser.add_argument('--list', action='store_true')
    parser.add_argument('--build-matrix', action='store_true', help='JSON matrix of 67 distinct artifacts; Quilt shares Fabric')
    parser.add_argument('--plan', action='store_true')
    parser.add_argument('--gradle-executable', help='Optional matching local Gradle executable')
    parser.add_argument('--java-home', help='JDK for the Gradle process; see --plan')
    parser.add_argument('--java-installations', help='Comma-separated installed JDK paths for game toolchains')
    parser.add_argument('--skip-tests', action='store_true', help='Compile/package only; does not count as a test pass')
    parser.add_argument('--accept-test-eula', action='store_true',
                        help='Accept the Minecraft EULA for disposable Forge native-test servers only')
    parser.add_argument('--source-bundle', action='store_true')
    parser.add_argument('--receipt', type=Path, help='Save artifact audit JSON after a successful build')
    args = parser.parse_args()
    if args.list or args.build_matrix:
        targets = matrix(include_quilt=not args.build_matrix)
        print(json.dumps({'include': targets} if args.build_matrix else targets, indent=2))
        return
    if not args.loader or not args.minecraft:
        parser.error('--loader and --minecraft are required unless listing the matrix')
    try:
        selected = plan(args.loader, args.minecraft)
        if args.plan:
            print(json.dumps(selected, indent=2))
            return
        if selected['native_test_server'] and not args.skip_tests and not args.accept_test_eula:
            raise ValueError('This Forge native test suite starts a disposable server. Read '
                             'https://aka.ms/MinecraftEULA and pass --accept-test-eula, or use --skip-tests')
        java = java_executable(selected['build_java'], args.java_home)
        folder = ROOT / selected['project']
        if args.gradle_executable:
            executable = Path(args.gradle_executable).resolve()
            if not executable.is_file():
                raise ValueError('Gradle executable not found')
            command = [str(executable)]
        else:
            if selected['project'] == 'forge' and selected['gradle'].startswith('9.'):
                wrapper = ROOT / 'build-tools/modern-gradle/wrapper/gradle-wrapper.jar'
            elif selected['project'] == 'neoforge' and selected['gradle'] == '8.14.5':
                wrapper = folder / 'gradle-early/wrapper/gradle-wrapper.jar'
            else:
                wrapper = folder / 'gradle/wrapper/gradle-wrapper.jar'
            command = [str(java), '-Xmx64m', '-classpath', str(wrapper), 'org.gradle.wrapper.GradleWrapperMain']
        # Gradle's stale-output cleanup is keyed by task execution history. Sharing
        # that history across targets can delete another target's classes or JAR.
        project_cache = folder / '.gradle' / 'targets' / args.minecraft
        command += ['--no-daemon', '--max-workers=2', '--project-cache-dir', str(project_cache),
                    '-PminecraftVersion=' + args.minecraft]
        if args.accept_test_eula:
            command.append('-PacceptMinecraftEula=true')
        installations = args.java_installations or ','.join(dict.fromkeys(
            value for key, value in os.environ.items() if key.startswith('JAVA_HOME') and value))
        if installations:
            command.append('-Porg.gradle.java.installations.paths=' + installations)
        command += ['build', '-x', 'test'] if args.skip_tests else ['test', 'build']
        if args.source_bundle:
            command.append('sourceBundle')
        environment = os.environ.copy()
        environment['JAVA_HOME'] = str(java.parent.parent)
        environment['PATH'] = str(java.parent) + os.pathsep + environment.get('PATH', '')
        print(json.dumps({'building': selected, 'tests_requested': not args.skip_tests}), flush=True)
        result = subprocess.run(command, cwd=folder, env=environment)
        if result.returncode:
            raise SystemExit(result.returncode)
        candidates = list((folder / 'build' / args.minecraft / 'libs').glob('namelessmc-*.jar'))
        artifacts = [file for file in candidates if not any(tag in file.name for tag in ('-slim', '-dev', '-sources'))]
        if len(artifacts) != 1:
            raise ValueError('Expected one final mod JAR in the exact target directory')
        receipt = audit_artifact(artifacts[0], selected)
        receipt['tests_requested'] = not args.skip_tests
        if args.receipt:
            args.receipt.parent.mkdir(parents=True, exist_ok=True)
            args.receipt.write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt, indent=2))
    except (ValueError, KeyError, zipfile.BadZipFile) as failure:
        parser.error(str(failure))


if __name__ == '__main__':
    main()
