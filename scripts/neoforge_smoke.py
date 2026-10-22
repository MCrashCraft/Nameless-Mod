"""Smoke-test one packaged mod in a disposable, loopback-only NeoForge server.

Uses an exact official installer and the SHA-256 pinned in the repository. Never
reads an existing server, world or private NamelessMC connection configuration.
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
import urllib.request
import uuid
import zipfile

REPO = Path(__file__).resolve().parents[1]
MARKER = ".namelessmc-smoke.json"
PURPOSE = "disposable-namelessmc-smoke"


def selected_java(major: int, override: Path | None) -> Path:
    home = override or os.environ.get(f"JAVA_HOME_{major}_X64") or os.environ.get("JAVA_HOME")
    executable = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java") if home else None
    if executable is None or not executable.is_file():
        raise ValueError(f"Provide --java-home or JAVA_HOME_{major}_X64 for Java {major}")
    version = subprocess.run([str(executable), "-version"], capture_output=True, text=True, check=True)
    match = re.search(r'version "(\d+)', version.stdout + version.stderr)
    if match is None or int(match[1]) != major:
        raise ValueError(f"This exact Minecraft target requires Java {major}")
    return executable


def scratch_directory(requested: Path | None) -> Path:
    root = requested.resolve() if requested else Path(tempfile.mkdtemp(prefix="namelessmc-neoforge-smoke-")).resolve()
    marker = root / MARKER
    if root.exists() and any(root.iterdir()):
        if not marker.is_file():
            raise ValueError("--work must be empty or marked as a NamelessMC disposable smoke directory")
        contents = json.loads(marker.read_text(encoding="utf-8"))
        if contents.get("purpose") != PURPOSE or contents.get("format") != 1:
            raise ValueError("The existing scratch marker has a different purpose or format")
    root.mkdir(parents=True, exist_ok=True)
    marker.write_text(json.dumps({"purpose": PURPOSE, "format": 1}) + "\n", encoding="utf-8")
    return root


def download_installer(pin: dict, cache: Path) -> Path:
    checksum = pin["sha256"]
    if re.fullmatch(r"[0-9a-f]{64}", checksum) is None:
        raise ValueError("Installer metadata does not contain a valid SHA-256")
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache / ("neoforge-installer-" + checksum + ".jar")
    if destination.exists():
        if hashlib.sha256(destination.read_bytes()).hexdigest() != checksum:
            raise ValueError("Cached installer does not match its pinned checksum")
        return destination
    partial = destination.with_suffix(".download")
    with urllib.request.urlopen(pin["url"], timeout=180) as response, partial.open("wb") as output:
        shutil.copyfileobj(response, output)
    if hashlib.sha256(partial.read_bytes()).hexdigest() != checksum:
        raise ValueError("Official installer does not match its pinned checksum")
    partial.replace(destination)
    return destination


def run(repo: Path, work: Path, target: str, java_home: Path | None, timeout: int) -> dict:
    rows = json.loads((repo / "compatibility/targets.json").read_text(encoding="utf-8"))["releases"]
    row = next((item for item in rows if item["minecraft"] == target), None)
    if row is None or not row["neoforge"]["published"]:
        raise ValueError("Choose an exact published NeoForge target from compatibility/targets.json")
    neo = row["neoforge"]
    pins = json.loads((repo / "neoforge/installer-checksums.json").read_text(encoding="utf-8"))["installers"]
    pin = next((item for item in pins if item["minecraft"] == target), None)
    if pin is None or pin["coordinate"] != neo["coordinate"]:
        raise ValueError("Pinned official installer does not match the selected loader coordinate")
    group, module = neo["artifact"].split(":")
    if group != "net.neoforged" or module not in {"forge", "neoforge"}:
        raise ValueError("Only official NeoForged distribution coordinates are permitted")
    version = neo["version"]
    expected_url = f"https://maven.neoforged.net/releases/net/neoforged/{module}/{version}/{module}-{version}-installer.jar"
    if pin["url"] != expected_url:
        raise ValueError("Pinned installer URL does not match the exact official coordinate")
    artifact = repo / "neoforge/build" / target / "libs" / f"namelessmc-neoforge-{target}-0.2.0.jar"
    if not artifact.is_file():
        raise ValueError("Build the exact target's shaded mod JAR before running its smoke test")
    with zipfile.ZipFile(artifact) as archive:
        if "dev/mcrashcraft/namelessforge/NamelessNeoForgeMod.class" not in archive.namelist():
            raise ValueError("The selected artifact does not contain the native NeoForge adapter")
    java = selected_java(int(row["java_major"]), java_home)
    cache = (work / "downloads").resolve()
    if not cache.is_relative_to(work):
        raise ValueError("Download directory escaped the marked scratch tree")
    installer = download_installer(pin, cache)
    # Every invocation gets a fresh world and blank connection configuration.
    root = (work / "runtime/neoforge" / target / uuid.uuid4().hex).resolve()
    if not root.is_relative_to(work):
        raise ValueError("Runtime directory escaped the marked scratch tree")
    root.mkdir(parents=True, exist_ok=False)
    temporary = (work / "temp").resolve()
    if not temporary.is_relative_to(work):
        raise ValueError("Temporary directory escaped the marked scratch tree")
    temporary.mkdir(exist_ok=True)
    environment = dict(os.environ, TEMP=str(temporary), TMP=str(temporary))
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    with (root / "installer.log").open("w", encoding="utf-8") as output:
        installation = subprocess.run([str(java), "-Xmx512M", "-jar", str(installer), "--installServer"],
                                      cwd=root, env=environment, stdout=output, stderr=subprocess.STDOUT,
                                      timeout=900, check=False, creationflags=flags)
    if installation.returncode:
        raise RuntimeError("Official server installation failed; inspect " + str(root / "installer.log"))
    (root / "mods").mkdir(exist_ok=True)
    shutil.copy2(artifact, root / "mods" / artifact.name)
    (root / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    flat = json.dumps({"biome": "minecraft:plains", "layers": [
        {"block": "minecraft:bedrock", "height": 1}, {"block": "minecraft:dirt", "height": 2},
        {"block": "minecraft:grass_block", "height": 1}], "features": False, "lakes": False}, separators=(",", ":"))
    (root / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nenable-rcon=false\nenable-query=false\n"
        "enable-status=false\nonline-mode=true\nlevel-name=smoke-world\n"
        "level-type=minecraft:flat\ngenerate-structures=false\ngenerator-settings=" + flat + "\n"
        "view-distance=2\nsimulation-distance=2\nspawn-protection=0\nspawn-chunk-radius=0\n"
        "difficulty=peaceful\nmax-players=1\nmax-tick-time=120000\n", encoding="utf-8")
    argfile = root / "libraries/net/neoforged" / module / version / ("win_args.txt" if os.name == "nt" else "unix_args.txt")
    if not argfile.is_file():
        raise RuntimeError("Official installer did not create the expected platform argument file")
    command = [str(java), "-Xms256M", "-Xmx1536M", "@" + str(argfile), "--nogui"]
    process = subprocess.Popen(command, cwd=root, env=environment, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                               encoding="utf-8", errors="replace", creationflags=flags)
    messages = queue.Queue()

    def reader():
        for line in process.stdout:
            messages.put(line)
        messages.put(None)

    threading.Thread(target=reader, daemon=True).start()
    started = time.monotonic()
    ready = initialized = sent = stopped = timed_out = False
    command_at = None
    output = []
    with (root / "smoke.log").open("w", encoding="utf-8") as log:
        try:
            while time.monotonic() - started < timeout:
                try:
                    line = messages.get(timeout=0.2)
                    if line is not None:
                        output.append(line); log.write(line); log.flush()
                        ready |= "Done (" in line and "For help" in line
                        initialized |= "Nameless NeoForge 0.2.0 initialized" in line
                except queue.Empty:
                    pass
                if ready and initialized and not sent:
                    # Exercise worker invalidation while help overlaps configuration unload.
                    process.stdin.write("namelessforge:status\n" + "nlpl help\nnlpl reload\n" * 3
                                        + "namelessforge:status\n")
                    process.stdin.flush()
                    command_at = time.monotonic()
                    sent = True
                if sent and not stopped and time.monotonic() - command_at > 5:
                    process.stdin.write("stop\n"); process.stdin.flush(); stopped = True
                if process.poll() is not None:
                    while not messages.empty():
                        line = messages.get_nowait()
                        if line is not None:
                            output.append(line); log.write(line)
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
                process.kill(); process.wait(timeout=10)
    text = "".join(output)
    adapter_failure_markers = (
        "NamelessMC command failed", "Nameless background task failed",
        "Nameless shutdown cleanup:", "Nameless NeoForge initialization failed",
        "Nameless NeoForge reload failed",
    )
    adapter_failures = [marker for marker in adapter_failure_markers if marker in text]
    passed = (ready and initialized and process.returncode == 0 and not timed_out
              and text.count("Nameless NeoForge: not configured") >= 2
              and text.count("Nameless NeoForge configuration reloaded; website configured=false") >= 3
              and not adapter_failures)
    result = {"minecraft": target, "loader": "neoforge", "loader_version": version,
              "java_major": row["java_major"], "java_executable": str(java),
              "installer_sha256": pin["sha256"], "mod_sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
              "started": ready, "adapter_initialized": initialized, "exit_code": process.returncode,
              "timed_out": timed_out, "status_reload_passed": passed,
              "adapter_failures": adapter_failures,
              "runtime_verified": passed, "website_integration_verified": False,
              "scope": "Packaged mod startup, console status/help plus three reloads, orderly shutdown",
              "log": str(root / "smoke.log")}
    (root / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("minecraft")
    parser.add_argument("--repo", type=Path, default=REPO)
    parser.add_argument("--work", type=Path, help="An empty or already marked disposable scratch directory")
    parser.add_argument("--java-home", type=Path, help="Exact target runtime Java home; otherwise JAVA_HOME_<major>_X64")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--accept-eula", action="store_true", help="Accept Minecraft EULA for these disposable tests only")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Read https://aka.ms/MinecraftEULA and pass --accept-eula for disposable tests")
    if not 30 <= args.timeout <= 1800:
        parser.error("--timeout must be between 30 and 1800 seconds")
    try:
        result = run(args.repo.resolve(), scratch_directory(args.work), args.minecraft, args.java_home, args.timeout)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, zipfile.BadZipFile) as failure:
        parser.error(str(failure))
    print(json.dumps(result), flush=True)
    return 0 if result["status_reload_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
