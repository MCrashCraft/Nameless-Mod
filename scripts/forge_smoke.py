"""Boot one exact NamelessMC Forge artifact in an isolated loopback server.

Only official Forge installer artifacts are downloaded. Existing servers,
worlds, player data and website credentials are never read or copied.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

REPO = Path(__file__).resolve().parents[1]


def upstream(url: str):
    request = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (compatible; NamelessMC-Compatibility-Test/0.2.0)"
    })
    return urllib.request.urlopen(request, timeout=90)


def download_installer(game: str, forge: str, cache: Path) -> Path:
    name = f"forge-{game}-{forge}-installer.jar"
    url = f"https://maven.minecraftforge.net/net/minecraftforge/forge/{game}-{forge}/{name}"
    cache.mkdir(parents=True, exist_ok=True)
    artifact = cache / name
    if not artifact.is_file():
        with upstream(url) as response, artifact.open("wb") as output:
            shutil.copyfileobj(response, output)
    # Some older Forge artifacts expose SHA-1 but no SHA-256 sidecar.
    for algorithm, length in (("sha256", 64), ("sha1", 40)):
        try:
            with upstream(url + "." + algorithm) as response:
                expected = response.read().decode().split()[0].lower()
        except urllib.error.HTTPError as error:
            if error.code in (403, 404):
                continue
            raise
        if not re.fullmatch(r"[0-9a-f]{" + str(length) + "}", expected):
            raise RuntimeError("Invalid official Forge installer checksum")
        if hashlib.new(algorithm, artifact.read_bytes()).hexdigest() != expected:
            raise RuntimeError("Official Forge installer checksum mismatch")
        return artifact
    raise RuntimeError("Forge installer has no usable official checksum sidecar")


def java_runtime(major: int, override: Path | None) -> Path:
    home = override or os.environ.get(f"JAVA_HOME_{major}_X64") or os.environ.get("JAVA_HOME")
    executable = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java") if home else None
    if executable is None or not executable.is_file():
        raise RuntimeError(f"Provide --java-home or JAVA_HOME_{major}_X64 for Java {major}")
    version = subprocess.run([str(executable), "-version"], capture_output=True, text=True, check=True)
    match = re.search(r'version "(\d+)', version.stderr + version.stdout)
    if not match or int(match[1]) != major:
        raise RuntimeError(f"This target needs runtime Java {major}")
    return executable.resolve()


def owned_scratch(path: Path | None) -> Path:
    root = path.resolve() if path else Path(tempfile.mkdtemp(prefix="namelessmc-forge-smoke-"))
    marker = root / ".namelessmc-forge-smoke.json"
    if root.exists() and any(root.iterdir()) and not marker.is_file():
        raise RuntimeError("--work must be empty or marked as a disposable NamelessMC Forge smoke directory")
    root.mkdir(parents=True, exist_ok=True)
    if marker.exists():
        value = json.loads(marker.read_text())
        if value != {"purpose": "disposable-namelessmc-forge-smoke", "format": 1}:
            raise RuntimeError("Unrecognized scratch ownership marker")
    marker.write_text(json.dumps({"purpose": "disposable-namelessmc-forge-smoke", "format": 1}) + "\n")
    return root


def run(args) -> dict:
    repo = args.repo.resolve()
    target = next((row for row in json.loads((repo / "compatibility/targets.json").read_text())["releases"]
                   if row["minecraft"] == args.minecraft), None)
    if target is None or not target["forge"]["published"]:
        raise RuntimeError("No published Forge loader for the requested exact Minecraft target")
    forge = args.forge_version or target["forge"]["version"]
    if not re.fullmatch(r"\d+(?:\.\d+)+", forge):
        raise RuntimeError("Choose an exact numeric Forge release version")
    java = java_runtime(target["java_major"], args.java_home)
    work = owned_scratch(args.work)
    root = work / "runtime" / (args.minecraft + "-" + forge)
    root.mkdir(parents=True, exist_ok=True)
    result_file = root / "result.json"
    result_file.unlink(missing_ok=True)
    mod = (args.mod_jar.resolve() if args.mod_jar else repo / "forge/build" / args.minecraft / "libs"
           / ("namelessmc-forge-" + args.minecraft + "-0.2.0.jar"))
    if not mod.is_file():
        raise RuntimeError("Build the exact target mod before running a smoke server")
    installer = download_installer(args.minecraft, forge, work / "downloads")
    argfile = root / "libraries/net/minecraftforge/forge" / (args.minecraft + "-" + forge) \
        / ("win_args.txt" if os.name == "nt" else "unix_args.txt")
    # Forge 49.0.2's official installer emits an executable bootstrap shim instead
    # of platform argument files. Use that generated launcher without guessing
    # or reconstructing its classpath.
    shim = root / f"forge-{args.minecraft}-{forge}-shim.jar"
    temporary = work / "temp"
    temporary.mkdir(exist_ok=True)
    environment = dict(os.environ, TEMP=str(temporary), TMP=str(temporary))
    if not argfile.is_file() and not shim.is_file():
        with (root / "installer.log").open("w", encoding="utf-8") as log:
            subprocess.run([str(java), "-Xmx1024M", "-jar", str(installer), "--installServer", str(root)],
                           cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT,
                           check=True, timeout=args.install_timeout,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if not argfile.is_file() and not shim.is_file():
        raise RuntimeError("Official Forge installer did not create a platform argument file or bootstrap shim")
    mods = root / "mods"
    mods.mkdir(exist_ok=True)
    if any(file.name != mod.name for file in mods.iterdir()):
        raise RuntimeError("Disposable smoke mods folder contains an unexpected file")
    shutil.copy2(mod, mods / mod.name)
    artifact_sha256 = hashlib.sha256((mods / mod.name).read_bytes()).hexdigest()
    (root / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (root / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nenable-rcon=false\nenable-query=false\n"
        "level-name=smoke-world\nlevel-type=minecraft:flat\ngenerate-structures=false\n"
        'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"features":false,"lakes":false}\n'
        "view-distance=2\nsimulation-distance=2\nspawn-protection=0\ndifficulty=peaceful\n"
        "max-tick-time=120000\nonline-mode=true\n", encoding="utf-8")
    launch = ["@" + str(argfile)] if argfile.is_file() else ["-jar", str(shim)]
    process = subprocess.Popen([str(java), "-Xms256M", "-Xmx1536M", *launch, "nogui"],
                               cwd=root, env=environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    lines = queue.Queue()
    reader_state = {"complete": False, "error": None}

    def reader():
        try:
            for line in process.stdout:
                lines.put(line)
            reader_state["complete"] = True
        except Exception as error:
            reader_state["error"] = type(error).__name__

    reader_thread = threading.Thread(target=reader, daemon=True)
    reader_thread.start()
    deadline = time.monotonic() + args.timeout
    ready = False
    stopped = False
    shutdown_help_queued = timed_out = False
    command_at = None
    transcript = []
    with (root / "smoke.log").open("w", encoding="utf-8") as log:
        try:
            while time.monotonic() < deadline:
                try:
                    line = lines.get(timeout=0.2)
                    log.write(line); log.flush(); transcript.append(line)
                    if "Done (" in line and "For help" in line:
                        ready = True
                        process.stdin.write("namelessforge:status\nnlpl help\nnlpl reload\nnamelessforge:status\n")
                        process.stdin.flush(); command_at = time.monotonic()
                except queue.Empty:
                    pass
                if ready and not stopped and time.monotonic() - command_at > 5:
                    process.stdin.write("nlpl help\nstop\n"); process.stdin.flush()
                    stopped = shutdown_help_queued = True
                if process.poll() is not None:
                    while not lines.empty():
                        line = lines.get_nowait(); log.write(line); transcript.append(line)
                    break
            else:
                timed_out = True
            if process.poll() is None:
                process.stdin.write("stop\n"); process.stdin.flush()
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.terminate(); process.wait(timeout=20)
        finally:
            if process.poll() is None:
                process.terminate(); process.wait(timeout=20)
            reader_thread.join(timeout=5)
            while not lines.empty():
                line = lines.get_nowait(); log.write(line); transcript.append(line)
    text = "".join(transcript)
    runtime_errors = [line for line in transcript if any(marker in line for marker in (
        "NamelessMC command failed", "Nameless Forge initialization failed", "Nameless Forge reload failed",
        "Nameless background task failed", "Nameless task failed", "Nameless shutdown cleanup:"))]
    platform_warnings = [line.strip() for line in transcript if any(marker in line for marker in (
        "Unable to locate English counter names", "Only supported on OSX/BSD", "Only supported on Linux"))]
    output_complete = reader_state["complete"] and not reader_thread.is_alive() and not reader_state["error"]
    success = ready and shutdown_help_queued and output_complete and not timed_out \
        and process.returncode == 0 and text.count("Nameless Forge: not configured") >= 2 \
        and "Nameless Forge configuration reloaded; website configured=false" in text \
        and not runtime_errors
    result = {"minecraft": args.minecraft, "loader": "forge", "loader_version": forge,
              "java_major": target["java_major"], "java_executable": str(java), "started": ready,
              "exit_code": process.returncode, "status_reload_passed": success,
              "output_complete": output_complete, "shutdown_help_queued": shutdown_help_queued,
              "stdout_reader_error": reader_state["error"],
              "timed_out": timed_out,
              "runtime_verified": success, "runtime_errors": runtime_errors, "platform_warnings": platform_warnings,
              "mod_sha256": artifact_sha256,
              "installer_sha256": hashlib.sha256(installer.read_bytes()).hexdigest(),
              "log": str(root / "smoke.log"), "existing_server_data_copied": False}
    temporary_result = result_file.with_suffix(".json.tmp")
    temporary_result.write_text(json.dumps(result, indent=2) + "\n")
    temporary_result.replace(result_file)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("minecraft")
    parser.add_argument("--forge-version", help="Optional exact older Forge version for compatibility-floor verification")
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--install-timeout", type=int, default=600)
    parser.add_argument("--repo", type=Path, default=REPO)
    parser.add_argument("--work", type=Path, help="Empty scratch directory or one owned by this smoke runner")
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--mod-jar", type=Path)
    parser.add_argument("--accept-eula", action="store_true", help="Accept Minecraft EULA for this disposable test server")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Read https://aka.ms/MinecraftEULA and provide --accept-eula for disposable tests")
    try:
        receipt = run(args)
    except (RuntimeError, ValueError) as error:
        parser.error(str(error))
    print(json.dumps(receipt))
    raise SystemExit(0 if receipt["status_reload_passed"] else 1)
