"""Run only isolated, loopback-only Fabric/Quilt NamelessMC smoke servers.

The installer and all game files are official upstream artifacts. No existing
server, client, world or credentials are read. Each target has a separate world.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.request

REPO = Path(__file__).resolve().parents[1]
HERE = None
MATRIX = None
JAVA_HOME = None


def download(url: str, path: Path, sha1: str | None = None) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        partial = path.with_name(path.name + ".part")
        with urllib.request.urlopen(url, timeout=90) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output)
        partial.replace(path)
    if sha1 and hashlib.sha1(path.read_bytes()).hexdigest() != sha1:
        raise RuntimeError("Official artifact checksum mismatch: " + path.name)
    return path


def get_json(url: str):
    with urllib.request.urlopen(url, timeout=90) as response:
        return json.load(response)


def vanilla_server(version: str) -> Path:
    cache = HERE / "downloads" / ("minecraft-server-" + version + ".jar")
    if cache.exists():
        return cache
    loom = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/fabric-loom" / version
    info_path = loom / "mojang_minecraft_info.json"
    if info_path.exists():
        info = json.loads(info_path.read_text())
    else:
        manifest = get_json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        info = get_json(next(item["url"] for item in manifest["versions"] if item["id"] == version))
    server = info["downloads"]["server"]
    cached = loom / "minecraft-server.jar"
    if cached.exists() and hashlib.sha1(cached.read_bytes()).hexdigest() == server["sha1"]:
        cache.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(cached, cache)
        return cache
    return download(server["url"], cache, server["sha1"])


def selected_java(major: int) -> Path:
    home = JAVA_HOME or os.environ.get(f"JAVA_HOME_{major}_X64") or os.environ.get("JAVA_HOME")
    java = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java") if home else None
    if java is None or not java.is_file():
        raise RuntimeError(f"Provide --java-home or JAVA_HOME_{major}_X64 for Java {major}")
    result = subprocess.run([str(java), "-version"], capture_output=True, text=True, check=True)
    if f'version "{major}.' not in result.stdout + result.stderr:
        raise RuntimeError(f"Smoke runtime must be Java {major} for this target")
    return java


def installer_jar(loader: str) -> Path:
    if loader == "fabric":
        url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.2/fabric-installer-1.1.2.jar"
        path = download(url, HERE / "downloads/fabric-installer-1.1.2.jar")
        with urllib.request.urlopen(url + ".sha256", timeout=90) as response:
            expected = response.read().decode().split()[0]
    else:
        url = "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/0.15.1/quilt-installer-0.15.1.jar"
        path = download(url, HERE / "downloads/quilt-installer-0.15.1.jar")
        # The release Maven repository's sidecar matches its artifact; the
        # separate Quilt Meta installer listing currently carries a stale hash.
        with urllib.request.urlopen(url + ".sha256", timeout=90) as response:
            expected = response.read().decode().split()[0]
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise RuntimeError("Installer checksum mismatch")
    return path


def run(version: str, loader: str, timeout: int) -> dict:
    target = next(item for item in MATRIX["releases"] if item["minecraft"] == version)
    root = HERE / "runtime" / loader / version
    root.mkdir(parents=True, exist_ok=True)
    (root / "result.json").unlink(missing_ok=True)
    java = selected_java(target["java_major"])
    installer = installer_jar(loader)
    launcher_name = "fabric-server-launch.jar" if loader == "fabric" else "quilt-server-launch.jar"
    launcher = root / launcher_name
    if not launcher.exists():
        args = (["server", "-dir", str(root), "-mcversion", version, "-loader", target[loader]["loader"]]
                if loader == "fabric" else
                ["install", "server", version, target[loader]["loader"], "--install-dir=" + str(root)])
        with (root / "installer.log").open("w", encoding="utf-8") as log:
            subprocess.run([str(java), "-Xmx512M", "-jar", str(installer), *args], cwd=root,
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
    server_jar = root / "server.jar"
    if not server_jar.exists():
        shutil.copy2(vanilla_server(version), server_jar)
    mod = REPO / "fabric/build" / version / "libs" / ("namelessmc-fabric-" + version + "-0.2.0.jar")
    if not mod.exists():
        raise RuntimeError("Exact target runtime mod is not built: " + str(mod))
    mods = root / "mods"
    mods.mkdir(exist_ok=True)
    shutil.copy2(mod, mods / mod.name)
    api_version = target["fabric"]["fabric_api"]
    api = download("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/" + api_version
                   + "/fabric-api-" + api_version + ".jar",
                   HERE / "downloads" / ("fabric-api-" + api_version + ".jar"))
    shutil.copy2(api, mods / api.name)
    (root / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (root / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nenable-rcon=false\nenable-query=false\n"
        "level-name=smoke-world\nlevel-type=minecraft:flat\ngenerate-structures=false\n"
        'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"features":false,"lakes":false}\n'
        "view-distance=2\nsimulation-distance=2\nspawn-protection=0\n"
        "difficulty=peaceful\nmax-tick-time=120000\nonline-mode=true\n",
        encoding="utf-8")
    temporary = HERE / "temp"
    temporary.mkdir(exist_ok=True)
    env = dict(os.environ, TEMP=str(temporary), TMP=str(temporary))
    process = subprocess.Popen([str(java), "-Xms256M", "-Xmx1536M", "-jar", str(launcher), "nogui"],
                               cwd=root, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    lines = queue.Queue()
    def reader():
        for line in process.stdout:
            lines.put(line)
    reader_thread = threading.Thread(target=reader, daemon=True)
    reader_thread.start()
    deadline = time.monotonic() + timeout
    ready = False
    sent = False
    output = []
    command_at = None
    with (root / "smoke.log").open("w", encoding="utf-8") as log:
        try:
            while time.monotonic() < deadline:
                try:
                    line = lines.get(timeout=0.2)
                    log.write(line); log.flush(); output.append(line)
                    if 'Done (' in line and 'For help' in line:
                        ready = True
                        process.stdin.write("namelessforge:status\nnlpl help\nnlpl reload\nnamelessforge:status\n")
                        process.stdin.flush(); command_at = time.monotonic()
                except queue.Empty:
                    pass
                if ready and not sent and time.monotonic() - command_at > 5:
                    process.stdin.write("nlpl help\nstop\n"); process.stdin.flush(); sent = True
                if process.poll() is not None:
                    break
            else:
                if process.poll() is None:
                    process.stdin.write("stop\n"); process.stdin.flush()
                    try: process.wait(timeout=30)
                    except subprocess.TimeoutExpired: process.terminate(); process.wait(timeout=20)
        finally:
            if process.poll() is None:
                process.terminate(); process.wait(timeout=20)
            reader_thread.join(timeout=5)
            while not lines.empty():
                line = lines.get_nowait(); log.write(line); output.append(line)
    text = "".join(output)
    failure_markers = ("ExceptionInInitializerError", "NoClassDefFoundError", "NoSuchMethodError", "NoSuchFieldError",
                       "IllegalAccessError", "NamelessMC command failed",
                       "NamelessMC initialization failed", "Nameless background task failed", "NamelessMC reload failed",
                       "Nameless shutdown cleanup:")
    runtime_errors = [line.rstrip() for line in output if any(marker in line for marker in failure_markers)]
    platform_warnings = [line.rstrip() for line in output if "NullPointerException" in line and "oshi." in line]
    output_complete = not reader_thread.is_alive()
    success = ready and sent and output_complete and process.returncode == 0 and "NamelessMC: not configured" in text \
        and "NamelessMC configuration reloaded; website configured=false" in text \
        and not runtime_errors
    result = {"minecraft": version, "loader": loader, "loader_version": target[loader]["loader"],
              "java_major": target["java_major"], "java_executable": str(java), "started": ready, "exit_code": process.returncode,
              "status_reload_passed": success, "mod_sha256": hashlib.sha256(mod.read_bytes()).hexdigest(),
              "shutdown_help_queued": sent,
              "output_complete": output_complete,
              "runtime_errors": runtime_errors,
              "platform_warnings": platform_warnings,
              "log": str(root / "smoke.log")}
    receipt = root / "result.json"
    partial_receipt = root / "result.json.part"
    partial_receipt.write_text(json.dumps(result, indent=2) + "\n")
    partial_receipt.replace(receipt)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("loader", choices=["fabric", "quilt"])
    parser.add_argument("minecraft")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--repo", type=Path, default=REPO)
    parser.add_argument("--work", type=Path, help="An empty scratch directory, or one created by this smoke runner")
    parser.add_argument("--java-home", type=Path, help="Exact target runtime Java home; otherwise JAVA_HOME_<major>_X64")
    parser.add_argument("--accept-eula", action="store_true", help="Accept Minecraft EULA only for these disposable test servers")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Read https://aka.ms/MinecraftEULA and provide --accept-eula for disposable tests")
    REPO = args.repo.resolve()
    HERE = args.work.resolve() if args.work else Path(tempfile.mkdtemp(prefix="namelessmc-smoke-"))
    JAVA_HOME = args.java_home
    marker = HERE / ".namelessmc-smoke.json"
    if HERE.exists() and any(HERE.iterdir()) and not marker.is_file():
        parser.error("--work must be empty or already marked as a NamelessMC disposable smoke directory")
    HERE.mkdir(parents=True, exist_ok=True)
    marker.write_text('{"purpose":"disposable-namelessmc-smoke","format":1}\n')
    MATRIX = json.loads((REPO / "compatibility/targets.json").read_text())
    result = run(args.minecraft, args.loader, args.timeout)
    print(json.dumps(result))
    raise SystemExit(0 if result["status_reload_passed"] else 1)
