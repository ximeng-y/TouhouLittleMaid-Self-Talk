#!/usr/bin/env python3
"""CI 2 / NeoForge 真实客户端链路测试控制器。

只使用 Python 标准库：启动专服与两个真实客户端，托管假 LLM/TTS 与控制 HTTP，
按 JSON 场景驱动真实的客户端控件／网络包与服务端事件，并输出统一报告。

用法：
    python ci/runtime/runner.py run --loader neoforge --suite all
    python ci/runtime/runner.py run --loader neoforge --scenario player-chat
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import traceback
import uuid
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable
from urllib.parse import parse_qs, urlparse

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SCENARIO_DIR = HERE / "scenarios"
FIXTURE_DIR = HERE / "fixtures"
LAUNCH_DIR = ROOT / "build" / "ci-runtime" / "launch"
DEFAULT_GRADLE = os.environ.get("GRADLE", "")
JDK_HOME = os.environ.get("JAVA_HOME", "")

sys.path.insert(0, str(HERE))
from mock_http import MockController  # noqa: E402
from errors import CheckFailure, RunnerError  # noqa: E402
import checks  # noqa: E402
import checks_compat  # noqa: E402,F401


class ActionError(RunnerError):
    def __init__(self, action: dict[str, Any], result: dict[str, Any]):
        super().__init__(
            f"action {action.get('op')} failed: {result.get('error') or result.get('payload')}")
        self.action = action
        self.result = result


@dataclass
class Proc:
    role: str
    argv: list[str]
    cwd: Path
    log_path: Path
    env: dict[str, str]
    proc: subprocess.Popen
    started_at: float
    log_handle: Any = None
    exit_code: int | None = None

    def poll(self) -> int | None:
        if self.exit_code is None:
            self.exit_code = self.proc.poll()
        return self.exit_code

    def wait(self, timeout: float) -> int | None:
        try:
            self.exit_code = self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.exit_code = None
        return self.exit_code

    def tail(self, lines: int = 120) -> str:
        try:
            text = self.log_path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return ""
        return "\n".join(text.splitlines()[-lines:])


@dataclass
class Assertion:
    name: str
    ok: bool
    detail: str = ""
    evidence: Any = None


@dataclass
class SubResult:
    group: str
    sub: str
    status: str
    duration: float
    assertions: list[Assertion] = field(default_factory=list)
    error: str = ""
    evidence: dict[str, Any] = field(default_factory=dict)


class ScenarioEnv:
    """场景函数可见的受控 API；所有动作都走控制通道与真实游戏。"""

    def __init__(self, runner: "Runner", group: str, sub: str):
        self.runner = runner
        self.group = group
        self.sub = sub
        self.mock = runner.mock
        self.assertions: list[Assertion] = []
        self.evidence: dict[str, Any] = {}

    def action(self, role: str, op: str, timeout: float = 120, **params: Any) -> dict[str, Any]:
        return self.runner.action(role, op, params, timeout=timeout, scenario=self.sub)

    def server(self, op: str, timeout: float = 120, **params: Any) -> dict[str, Any]:
        return self.action("server", f"server.{op}", timeout=timeout, **params)

    def client(self, role: str, op: str, timeout: float = 120, **params: Any) -> dict[str, Any]:
        return self.action(role, f"client.{op}", timeout=timeout, **params)

    def query_server(self) -> dict[str, Any]:
        return self.server("query", timeout=60)

    def query_client(self, role: str) -> dict[str, Any]:
        return self.client(role, "query", timeout=60)

    def settings(self, player: str = "owner") -> dict[str, Any]:
        return self.server("settings", timeout=60, player=player)

    def wait_until(self, predicate: Callable[[], Any], timeout: float, description: str,
                   interval: float = 0.25) -> Any:
        deadline = time.monotonic() + timeout
        last_exc: Exception | None = None
        while time.monotonic() < deadline:
            try:
                value = predicate()
                if value:
                    return value
            except Exception as exc:  # 轮询期间允许瞬时失败（实体尚未同步等）
                last_exc = exc
            time.sleep(interval)
        suffix = f" (last error: {last_exc})" if last_exc else ""
        raise CheckFailure(f"timeout waiting for {description}{suffix}")

    def wait_llm(self, count: int = 1, timeout: float = 60, since: int | None = None,
                 scenario: str | None = None) -> list[Any]:
        mark = self.mock.mark() if since is None else since
        scenario = scenario or self.sub
        return self.wait_until(
            lambda: [r for r in self.mock.llm_requests(mark)
                     if scenario is None or r.scenario == scenario]
            if len([r for r in self.mock.llm_requests(mark)
                    if scenario is None or r.scenario == scenario]) >= count else None,
            timeout,
            f"{count} llm request(s) for {scenario}",
        )

    def wait_tts(self, count: int = 1, timeout: float = 60, since: int | None = None,
                 scenario: str | None = None) -> list[Any]:
        mark = self.mock.mark() if since is None else since
        scenario = scenario or self.sub
        def ready() -> Any:
            rows = [r for r in self.mock.tts_requests(mark)
                    if scenario is None or r.scenario == scenario]
            return rows if len(rows) >= count else None
        return self.wait_until(ready, timeout, f"{count} tts request(s) for {scenario}")

    def events(self, event_type: str | None = None, scenario: str | None = None,
               since: int = 0) -> list[dict[str, Any]]:
        rows = self.runner.events[since:]
        return [e for e in rows
                if (event_type is None or e.get("type") == event_type)
                and (scenario is None or e.get("scenarioId") == scenario)]

    def wait_event(self, event_type: str, timeout: float, predicate: Callable[[dict], bool] | None = None,
                   scenario: str | None = None) -> dict[str, Any]:
        scenario = scenario or self.sub

        def ready() -> Any:
            for event in self.events(event_type, scenario):
                if predicate is None or predicate(event):
                    return event
            return None

        return self.wait_until(ready, timeout, f"event {event_type} for {scenario}")

    def check(self, name: str, condition: Any, detail: str = "", evidence: Any = None) -> bool:
        ok = bool(condition)
        self.assertions.append(Assertion(name, ok, "" if ok else detail, evidence))
        return ok

    def require(self, name: str, condition: Any, detail: str = "", evidence: Any = None) -> None:
        if not self.check(name, condition, detail, evidence):
            raise CheckFailure(f"{name}: {detail}")

    def soft(self, name: str, condition: Any, detail: str = "", evidence: Any = None) -> None:
        self.check(name, condition, detail, evidence)

    def record(self, key: str, value: Any) -> None:
        self.evidence[key] = value


class Runner:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.loader = args.loader
        self.root = ROOT
        self.run_id = args.run_id or time.strftime("%Y%m%d-%H%M%S") + "-" + str(os.getpid())
        self.run_id = re.sub(r"[^A-Za-z0-9_.-]", "-", self.run_id)
        self.run_dir = ROOT / "build" / "ci-runtime" / self.run_id
        self.logs_dir = self.run_dir / "logs"
        self.report_dir = self.run_dir / "report"
        self.mock = MockController()
        self.httpd: ThreadingHTTPServer | None = None
        self.http_thread: threading.Thread | None = None
        self.port = 0
        self.pending: dict[str, collections.deque] = {
            "server": collections.deque(), "owner": collections.deque(), "other": collections.deque()}
        self.results: dict[str, dict[str, Any]] = {}
        self.events: list[dict[str, Any]] = []
        self.registered: dict[str, dict[str, Any]] = {}
        self.heartbeats: dict[str, float] = {}
        self.byes: set[str] = set()
        self.procs: dict[str, Proc] = {}
        self.cond = threading.Condition()
        self.lock = threading.RLock()
        self.gradle = args.gradle or DEFAULT_GRADLE
        self.java_home = args.java_home or JDK_HOME or self._find_java_home()
        self.profile = "base"
        self.profile_descriptors: dict[str, dict[str, dict[str, Any]]] = {}
        self.descriptors: dict[str, dict[str, Any]] = {}
        self.started_at = time.time()
        self.game_started_at: float | None = None
        self.server_actions = 0
        self.http_server_error: str | None = None
        self.stop_requested = False

    # ---------- 基础设施 ----------

    def log(self, message: str) -> None:
        line = f"[runner {time.strftime('%H:%M:%S')}] {message}"
        print(line, flush=True)
        try:
            with (self.logs_dir / "runner.log").open("a", encoding="utf-8") as fh:
                fh.write(line + "\n")
        except OSError:
            pass

    def _find_java_home(self) -> str:
        candidates = sorted(Path("D:/Games/ABOUT_MINECRAFT/JAVA").glob("zulu21*")) if os.name == "nt" else []
        for candidate in candidates:
            if (candidate / "bin" / "java.exe").exists() or (candidate / "bin" / "java").exists():
                return str(candidate)
        return os.environ.get("JAVA_HOME", "")

    def prepare(self) -> None:
        self.logs_dir.mkdir(parents=True, exist_ok=True)
        self.report_dir.mkdir(parents=True, exist_ok=True)
        (self.run_dir / "logs").mkdir(parents=True, exist_ok=True)
        if not self.args.skip_prepare:
            self.run_gradle_prepare()
        self.load_descriptors()
        self.start_http()
        self.write_all_game_configs()
        self.log(f"run dir: {self.run_dir}")
        self.log(f"control/mock port: {self.port}")

    def run_gradle_prepare(self) -> None:
        if not self.gradle:
            raise RunnerError("Gradle path not provided; use --gradle or set GRADLE")
        gradle = Path(self.gradle)
        if not gradle.exists():
            resolved = shutil.which(self.gradle)
            if resolved:
                gradle = Path(resolved)
        if not gradle.exists():
            raise RunnerError(f"Gradle not found: {gradle}")
        cmd = [str(gradle), "-PciRuntime=true", "ciPrepare", "--console=plain"]
        if os.name == "nt" and gradle.suffix.lower() in (".bat", ".cmd"):
            cmd = ["cmd", "/c", *cmd]
        env = os.environ.copy()
        if self.java_home:
            env["JAVA_HOME"] = self.java_home
        log_path = self.logs_dir / "gradle-ciPrepare.log"
        self.log(f"running ciPrepare: {' '.join(cmd)}")
        started = time.time()
        with log_path.open("w", encoding="utf-8", errors="replace") as fh:
            proc = subprocess.run(cmd, cwd=self.root, env=env, stdout=fh, stderr=subprocess.STDOUT,
                                  text=True, timeout=900)
        self.log(f"ciPrepare finished code={proc.returncode} in {time.time() - started:.1f}s")
        if proc.returncode != 0:
            raise RunnerError(f"ciPrepare failed, see {log_path}")

    def load_descriptors(self) -> None:
        for profile, launch_dir in (
                ("base", LAUNCH_DIR),
                ("optional", LAUNCH_DIR / "optional"),
        ):
            descriptors: dict[str, dict[str, Any]] = {}
            for role, name in (("server", "server.json"), ("owner", "owner.json"),
                               ("other", "other.json")):
                path = launch_dir / name
                if not path.exists():
                    raise RunnerError(f"{profile} launch descriptor missing: {path}")
                data = json.loads(path.read_text(encoding="utf-8"))
                if not data.get("mainClass") or not data.get("classpath"):
                    raise RunnerError(f"invalid {profile} launch descriptor: {path}")
                descriptors[role] = data
            self.profile_descriptors[profile] = descriptors
        self.descriptors = self.profile_descriptors["base"]
        self.profile = "base"
        self.log("loaded base and optional launch descriptors")

    def switch_profile(self, profile: str) -> None:
        if profile == self.profile:
            return
        descriptors = self.profile_descriptors.get(profile)
        if descriptors is None:
            raise RunnerError(f"unknown runtime profile: {profile}")
        self.log(f"switching runtime profile {self.profile} -> {profile}")
        for role in ("other", "owner", "server"):
            self.stop_proc(role, graceful=True, timeout=90)
        self.descriptors = descriptors
        self.profile = profile
        self.start_server()
        self.baseline_server()
        self.start_client("owner")
        self.start_client("other")
        self.place_players()

    def start_http(self) -> None:
        runner = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt: str, *args: Any) -> None:
                # 默认静默：控制通道每 200ms 轮询一次，逐请求日志会淹没场景输出。
                pass

            def _json_body(self) -> dict[str, Any]:
                length = int(self.headers.get("Content-Length", "0") or 0)
                raw = self.rfile.read(length) if length else b"{}"
                try:
                    data = json.loads(raw.decode("utf-8"))
                except Exception as exc:
                    raise RunnerError(f"invalid json body: {exc}") from exc
                return data if isinstance(data, dict) else {}

            def _send_json(self, status: int, body: dict[str, Any]) -> None:
                payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def _send_bytes(self, status: int, payload: bytes, content_type: str) -> None:
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self) -> None:
                try:
                    parsed = urlparse(self.path)
                    if parsed.path == "/health":
                        self._send_json(200, {"ok": True, "runId": runner.run_id})
                        return
                    if parsed.path == "/api/actions":
                        query = parse_qs(parsed.query)
                        role = (query.get("role") or [""])[0]
                        run_id = (query.get("runId") or [""])[0]
                        if run_id != runner.run_id:
                            self._send_json(409, {"error": "runId mismatch"})
                            return
                        with runner.cond:
                            queue = runner.pending.get(role)
                            action = queue.popleft() if queue else None
                        self._send_json(200, {"action": action} if action else {})
                        return
                    self._send_json(404, {"error": "not found"})
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception as exc:
                    runner.log(f"GET error {self.path}: {exc}")
                    try:
                        self._send_json(500, {"error": str(exc)})
                    except Exception:
                        pass

            def do_POST(self) -> None:
                try:
                    parsed = urlparse(self.path)
                    if parsed.path == "/v1/chat/completions":
                        body = self._json_body()
                        headers = self._safe_headers()
                        status, payload, content_type, delay = runner.mock.handle_llm(body, headers)
                        if delay:
                            time.sleep(delay)
                        if status <= 0:
                            self.close_connection = True
                            return
                        if 200 <= status < 300:
                            self._send_bytes(status, payload, content_type)
                        else:
                            self._send_json(status, json.loads(payload.decode("utf-8") or "{}"))
                        return
                    if parsed.path == "/v1/audio/speech":
                        body = self._json_body()
                        headers = self._safe_headers()
                        status, payload, content_type, delay = runner.mock.handle_tts(body, headers)
                        if delay:
                            time.sleep(delay)
                        if status <= 0:
                            self.close_connection = True
                            return
                        self._send_bytes(status, payload, content_type)
                        return
                    body = self._json_body()
                    if parsed.path == "/api/register":
                        with runner.cond:
                            runner.registered[str(body.get("role"))] = body
                            runner.heartbeats[str(body.get("role"))] = time.time()
                            runner.cond.notify_all()
                        runner.log(f"registered {body.get('role')} pid={body.get('pid')}")
                        self._send_json(200, {"ok": True})
                        return
                    if parsed.path == "/api/results":
                        action_id = str(body.get("actionId"))
                        with runner.cond:
                            runner.results[action_id] = body
                            runner.cond.notify_all()
                        runner._append_event({"type": "action.result", "role": body.get("role"),
                                              "scenarioId": body.get("scenarioId"),
                                              "actionId": action_id, "time": time.time(),
                                              "data": body})
                        self._send_json(200, {"ok": True})
                        return
                    if parsed.path == "/api/observations":
                        with runner.cond:
                            runner._append_event(body)
                            runner.cond.notify_all()
                        self._send_json(200, {"ok": True})
                        return
                    if parsed.path == "/api/heartbeat":
                        with runner.cond:
                            runner.heartbeats[str(body.get("role"))] = time.time()
                            runner.cond.notify_all()
                        self._send_json(200, {"ok": True})
                        return
                    if parsed.path == "/api/bye":
                        role = str(body.get("role"))
                        with runner.cond:
                            runner.byes.add(role)
                            runner.cond.notify_all()
                        runner.log(f"bye {role}")
                        self._send_json(200, {"ok": True})
                        return
                    self._send_json(404, {"error": "not found"})
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception as exc:
                    runner.log(f"POST error {self.path}: {exc}\n{traceback.format_exc()}")
                    try:
                        self._send_json(500, {"error": str(exc)})
                    except Exception:
                        pass

            def _safe_headers(self) -> dict[str, str]:
                allowed = {"content-type", "user-agent", "accept", "x-ci-run", "x-ci-scenario"}
                return {k.lower(): v for k, v in self.headers.items()
                        if k.lower() in allowed}

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.port = int(self.httpd.server_address[1])
        self.http_thread = threading.Thread(target=self.httpd.serve_forever,
                                            name="ci-http", daemon=True)
        self.http_thread.start()

    # ---------- 配置与进程 ----------

    def write_all_game_configs(self) -> None:
        server_dir = self.run_dir / "server"
        server_dir.mkdir(parents=True, exist_ok=True)
        (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        (server_dir / "server.properties").write_text(
            "\n".join([
                "online-mode=false",
                "server-port=0",
                "server-ip=127.0.0.1",
                "level-name=world",
                "level-seed=ci2",
                "spawn-protection=0",
                "view-distance=5",
                "simulation-distance=5",
                "max-tick-time=-1",
                "sync-chunk-writes=false",
                "allow-flight=true",
                "enable-command-block=true",
                "difficulty=normal",
                "gamemode=survival",
                "pvp=false",
                "motd=ci2-neoforge",
                "enable-status=true",
                "broadcast-console-to-ops=false",
                "spawn-monsters=false",
                "spawn-npcs=true",
                # 关键：EntityMaid 继承 Animal，spawn-animals=false 时 ServerLevel 每 tick 直接 discard 所有动物。
                "spawn-animals=true",
                "generate-structures=false",
                "",
            ]), encoding="utf-8")
        self.write_sites(server_dir)
        self.seed_custom_prebuilt(server_dir)
        for role in ("owner", "other"):
            game_dir = self.run_dir / role
            game_dir.mkdir(parents=True, exist_ok=True)
            self.write_options(game_dir)
            self.write_sites(game_dir)
        self.log("wrote per-run server/client configs")

    def write_sites(self, game_dir: Path) -> None:
        sites_dir = game_dir / "config" / "touhou_little_maid" / "sites"
        sites_dir.mkdir(parents=True, exist_ok=True)
        base = f"http://127.0.0.1:{self.port}"
        llm = {
            "ci_llm": {
                "id": "ci_llm",
                "api_type": "openai",
                "enabled": True,
                "icon": "touhou_little_maid:textures/gui/ai_chat/deepseek.png",
                "url": f"{base}/v1/chat/completions",
                "secret_key": "ci-local-no-secret",
                "headers": {"X-Ci-Run": self.run_id},
                "models": ["ci-model"],
                "has_thinking_field": False,
            }
        }
        tts = {
            "ci_tts": {
                "id": "ci_tts",
                "api_type": "siliconflow",
                "enabled": True,
                "icon": "touhou_little_maid:textures/gui/ai_chat/siliconflow.png",
                "url": f"{base}/v1/audio/speech",
                "secret_key": "ci-local-no-secret",
                "headers": {},
                "models": {"ci-voice": "ci-voice"},
            }
        }
        (sites_dir / "llm.json").write_text(json.dumps(llm, ensure_ascii=False, indent=2),
                                            encoding="utf-8")
        (sites_dir / "tts.json").write_text(json.dumps(tts, ensure_ascii=False, indent=2),
                                            encoding="utf-8")

    def seed_custom_prebuilt(self, server_dir: Path) -> None:
        """预置人类 custom 条目：服务端启动扫描时加载，验证合法投放与坏样本诊断。"""
        base = server_dir / "world" / "data" / "maid_self_talk" / "prebuilt" / "custom" / "CustomAlice"
        valid_dir = base / "self-talk"
        valid_dir.mkdir(parents=True, exist_ok=True)
        (valid_dir / "ci-valid.md").write_text(
            "---\n"
            'bodyFormat: "fenced-v1"\n'
            "env: {}\n"
            "---\n\n"
            "## Chat\n\n"
            "```\n人工自定义回答。\n```\n\n"
            "## TTS\n\n"
            "```\n人工自定义回答。\n```\n",
            encoding="utf-8",
        )
        (valid_dir / "ci-broken.md").write_text("没有 YAML 头的坏样本。\n", encoding="utf-8")

    def write_options(self, game_dir: Path) -> None:
        (game_dir / "options.txt").write_text(
            "\n".join([
                "version:3955",
                "lang:en_us",
                "renderDistance:4",
                "simulationDistance:5",
                "graphicsMode:0",
                "ao:false",
                "guiScale:1",
                "pauseOnLostFocus:false",
                "tutorialStep:none",
                "skipMultiplayerWarning:true",
                "joinedFirstServer:true",
                "onboardAccessibility:false",
                "narrator:0",
                "soundCategory_master:0.0",
                "fullscreen:false",
                "showSubtitles:false",
                "entityShadows:false",
                "particles:2",
                "bobView:false",
                "fov:0.0",
                "gamma:1.0",
                "",
            ]), encoding="utf-8")

    def _java(self) -> str:
        java = Path(self.java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if not java.exists():
            raise RunnerError(f"Java not found: {java}")
        return str(java)

    def _launch_argv(self, role: str) -> tuple[list[str], Path, dict[str, str]]:
        descriptor = self.descriptors[role]
        java = self._java()
        game_dir = self.run_dir / role
        heap = "2G" if role == "server" else "1536M"
        jvm = [f"-Xmx{heap}", "-Xms256M", "-Dfml.earlyprogresswindow=false",
               f"-Dci.controlUrl=http://127.0.0.1:{self.port}",
               f"-Dci.role={role}", f"-Dci.runId={self.run_id}"]
        if role != "server":
            jvm.append("-Dminecraft.launcher.brand=ci2-neoforge")
            jvm.append("-Dminecraft.launcher.version=ci2")
        jvm.extend(descriptor.get("jvmArgs") or [])
        cp = os.pathsep.join(descriptor["classpath"])
        args = [java, *jvm, "-cp", cp, descriptor["mainClass"]]
        args.extend(descriptor.get("programArgs") or [])
        if role == "server":
            if "--nogui" not in args:
                args.append("--nogui")
        else:
            username = "OwnerBot" if role == "owner" else "OtherBot"
            args.extend(["--username", username,
                         "--quickPlayMultiplayer", f"127.0.0.1:{self.server_port}",
                         "--width", "640", "--height", "360"])
        env = os.environ.copy()
        env.update({k: str(v) for k, v in (descriptor.get("environment") or {}).items()})
        env["JAVA_HOME"] = self.java_home
        env["LANG"] = "en_US.UTF-8"
        env["LC_ALL"] = "en_US.UTF-8"
        if os.name != "nt":
            env["LIBGL_ALWAYS_SOFTWARE"] = "1"
            env["MESA_GL_VERSION_OVERRIDE"] = "4.5"
            env["MESA_GLSL_VERSION_OVERRIDE"] = "450"
            env["ALSOFT_DRIVERS"] = "null"
            env["SDL_AUDIODRIVER"] = "dummy"
        return args, game_dir, env

    @property
    def server_port(self) -> int:
        props = self.run_dir / "server" / "server.properties"
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("server-port="):
                value = line.split("=", 1)[1].strip()
                if value.isdigit() and int(value) > 0:
                    return int(value)
        raise RunnerError("server port not allocated")

    def allocate_server_port(self) -> int:
        import socket
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.bind(("127.0.0.1", 0))
            return int(sock.getsockname()[1])

    def start_proc(self, role: str) -> Proc:
        if role in self.procs and self.procs[role].poll() is None:
            raise RunnerError(f"{role} already running")
        # 重启同角色时必须丢弃上一 JVM 的注册记录，否则 wait_registered 会立刻返回旧进程。
        with self.cond:
            self.registered.pop(role, None)
        args, game_dir, env = self._launch_argv(role)
        log_path = self.logs_dir / f"{role}.log"
        handle = log_path.open("wb")
        creationflags = 0
        preexec = None
        if os.name == "nt":
            creationflags = subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            preexec = os.setsid
        self.log(f"starting {role}: pid soon, log {log_path}")
        proc = subprocess.Popen(args, cwd=game_dir, env=env, stdin=subprocess.DEVNULL,
                                stdout=handle, stderr=subprocess.STDOUT,
                                creationflags=creationflags, preexec_fn=preexec)
        item = Proc(role, args, game_dir, log_path, env, proc, time.time(), handle)
        self.procs[role] = item
        self.log(f"started {role} pid={proc.pid}")
        return item

    def wait_registered(self, role: str, timeout: float = 420) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self.cond:
                entry = self.registered.get(role)
                if entry:
                    return entry
            proc = self.procs.get(role)
            if proc is not None and proc.poll() is not None:
                raise RunnerError(f"{role} exited before registering (code={proc.exit_code})\n"
                                  + proc.tail())
            time.sleep(0.25)
        proc = self.procs.get(role)
        raise RunnerError(f"{role} did not register in {timeout}s\n" + (proc.tail() if proc else ""))

    def start_server(self) -> None:
        if "server" not in self.descriptors:
            raise RunnerError("no server descriptor")
        port = self.allocate_server_port()
        props = self.run_dir / "server" / "server.properties"
        text = props.read_text(encoding="utf-8")
        text = re.sub(r"server-port=\d+", f"server-port={port}", text)
        props.write_text(text, encoding="utf-8")
        self._server_port = port
        self.game_started_at = self.game_started_at or time.time()
        self.start_proc("server")
        self.wait_registered("server", timeout=420)
        self.server_actions = 0
        self.log(f"server ready on 127.0.0.1:{port}")

    def start_client(self, role: str) -> None:
        self.start_proc(role)
        self.wait_registered(role, timeout=420)
        self.wait_player(role, timeout=240)
        self.log(f"client {role} in world")

    def wait_player(self, role: str, timeout: float = 180) -> dict[str, Any]:
        username = "OwnerBot" if role == "owner" else "OtherBot"
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            proc = self.procs.get(role)
            if proc is not None and proc.poll() is not None:
                raise RunnerError(f"{role} exited while waiting for join (code={proc.exit_code})\n"
                                  + proc.tail())
            try:
                query = self.action("server", "server.query", {}, timeout=30, scenario="setup")
                for player in query.get("players", []):
                    if player.get("name") == username:
                        return player
            except RunnerError:
                pass
            time.sleep(0.5)
        raise RunnerError(f"player {username} did not join in {timeout}s")

    def stop_proc(self, role: str, graceful: bool = True, timeout: float = 45) -> None:
        proc = self.procs.get(role)
        if proc is None:
            return
        if proc.poll() is not None:
            self.log(f"{role} already exited code={proc.exit_code}")
            return
        if graceful:
            op = "server.stop" if role == "server" else "client.quit"
            try:
                self.action(role, op, {}, timeout=30, scenario="teardown")
            except RunnerError as exc:
                self.log(f"graceful stop {role} action failed: {exc}")
        code = proc.wait(timeout)
        if code is None:
            self.log(f"{role} did not exit gracefully; terminating registered pid")
            self.terminate_proc(proc)
            proc.wait(20)
        self.log(f"{role} stopped code={proc.poll()}")
        try:
            proc.log_handle.close()
        except Exception:
            pass
        with self.cond:
            self.registered.pop(role, None)

    def terminate_proc(self, proc: Proc) -> None:
        if proc.poll() is not None:
            return
        try:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(proc.proc.pid), "/T", "/F"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
            else:
                os.killpg(os.getpgid(proc.proc.pid), signal.SIGTERM)
                time.sleep(2)
                if proc.poll() is None:
                    os.killpg(os.getpgid(proc.proc.pid), signal.SIGKILL)
        except Exception as exc:
            self.log(f"terminate {proc.role} pid={proc.proc.pid} failed: {exc}")
            try:
                proc.proc.kill()
            except Exception:
                pass

    def stop_all(self) -> None:
        for role in ("other", "owner", "server"):
            try:
                self.stop_proc(role, graceful=True, timeout=60)
            except Exception as exc:
                self.log(f"stop {role} error: {exc}")
                proc = self.procs.get(role)
                if proc is not None:
                    self.terminate_proc(proc)
        for proc in self.procs.values():
            if proc.poll() is None:
                self.terminate_proc(proc)
        if self.httpd is not None:
            self.httpd.shutdown()
            self.httpd.server_close()

    # ---------- 控制通道 ----------

    def action(self, role: str, op: str, params: dict[str, Any] | None = None,
               timeout: float = 120, scenario: str = "system") -> dict[str, Any]:
        action = {
            "id": uuid.uuid4().hex,
            "op": op,
            "scenarioId": scenario,
            "params": params or {},
        }
        action.update(params or {})
        with self.cond:
            self.pending[role].append(action)
            self.cond.notify_all()
        self._append_event({"type": "action.send", "role": role, "scenarioId": scenario,
                            "actionId": action["id"], "time": time.time(),
                            "data": {"op": op, "params": params or {}}})
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self.cond:
                result = self.results.pop(action["id"], None)
                if result is not None:
                    break
                proc = self.procs.get(role)
                if proc is not None and proc.poll() is not None:
                    raise RunnerError(f"{role} exited while action {op} pending (code={proc.exit_code})")
                self.cond.wait(timeout=min(0.25, max(0.01, deadline - time.monotonic())))
        else:
            raise RunnerError(f"action timeout role={role} op={op} ({timeout}s)")
        if result.get("status") != "ok":
            raise ActionError(action, result)
        return result.get("payload") or {}

    def _append_event(self, event: dict[str, Any]) -> None:
        with self.lock:
            self.events.append(event)
            try:
                with (self.run_dir / "events.jsonl").open("a", encoding="utf-8") as fh:
                    fh.write(json.dumps(event, ensure_ascii=False) + "\n")
            except OSError:
                pass

    def wait_observation(self, event_type: str, timeout: float, scenario: str | None = None,
                         predicate: Callable[[dict], bool] | None = None) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for event in self.events:
                if event.get("type") == event_type and (
                        scenario is None or event.get("scenarioId") == scenario) and (
                        predicate is None or predicate(event)):
                    return event
            time.sleep(0.2)
        raise RunnerError(f"observation timeout type={event_type} scenario={scenario}")

    # ---------- 场景 ----------

    def load_groups(self) -> list[dict[str, Any]]:
        groups = []
        for path in sorted(SCENARIO_DIR.glob("*.json")):
            data = json.loads(path.read_text(encoding="utf-8"))
            data["_path"] = str(path)
            groups.append(data)
        return groups

    def select_groups(self, groups: list[dict[str, Any]]) -> list[dict[str, Any]]:
        target = self.args.scenario
        suite = getattr(self.args, "suite", "all")
        if not target or target == "all":
            if suite == "optional":
                selected = [g for g in groups if g.get("optional") or g["id"] == "optional-compat"]
            elif suite == "base":
                selected = [g for g in groups if not (g.get("optional") or g["id"] == "optional-compat")]
            else:
                selected = groups
        else:
            selected = []
            for group in groups:
                subs = group.get("subScenarios") or []
                if group["id"] == target or any(s.get("id") == target for s in subs):
                    if any(s.get("id") == target for s in subs):
                        group = dict(group)
                        group["subScenarios"] = [s for s in subs if s.get("id") == target]
                    selected.append(group)
        if not selected:
            raise RunnerError(f"scenario not found: {target}")
        return selected

    def run_scenarios(self) -> list[SubResult]:
        groups = self.select_groups(self.load_groups())
        results: list[SubResult] = []
        for group in groups:
            group_id = group["id"]
            self.mock.reset(group_id)
            self.mock.set_scenario(group_id)
            self.log(f"=== scenario group {group_id} ===")
            for sub in group.get("subScenarios") or []:
                sub_id = sub.get("id") or sub.get("name")
                check_name = sub.get("check") or sub_id
                fn = checks.CHECKS.get(check_name)
                if fn is None:
                    results.append(SubResult(group_id, sub_id, "error", 0.0,
                                             error=f"no check function: {check_name}"))
                    continue
                self.switch_profile(sub.get("profile") or group.get("profile") or "base")
                self.mock.set_scenario(sub_id)
                env = ScenarioEnv(self, group_id, sub_id)
                started = time.time()
                self.log(f"--- {sub_id} ---")
                try:
                    fn(env)
                    status = "passed" if all(a.ok for a in env.assertions) else "failed"
                    if status == "failed":
                        details = "; ".join(f"{a.name}: {a.detail}" for a in env.assertions if not a.ok)
                        results.append(SubResult(group_id, sub_id, status, time.time() - started,
                                                 env.assertions, error=details,
                                                 evidence=env.evidence))
                    else:
                        results.append(SubResult(group_id, sub_id, status, time.time() - started,
                                                 env.assertions, evidence=env.evidence))
                except CheckFailure as exc:
                    env.assertions.append(Assertion("scenario", False, str(exc)))
                    results.append(SubResult(group_id, sub_id, "failed", time.time() - started,
                                             env.assertions, error=str(exc), evidence=env.evidence))
                    self.log(f"FAILED {sub_id}: {exc}")
                    self.dump_failure(sub_id)
                except Exception as exc:
                    env.assertions.append(Assertion("scenario", False, f"{type(exc).__name__}: {exc}"))
                    results.append(SubResult(group_id, sub_id, "error", time.time() - started,
                                             env.assertions,
                                             error=f"{type(exc).__name__}: {exc}",
                                             evidence=env.evidence))
                    self.log(f"ERROR {sub_id}: {type(exc).__name__}: {exc}")
                    self.log(traceback.format_exc())
                    self.dump_failure(sub_id)
                finally:
                    self.mock.set_scenario(sub_id)
                    self._write_requests()
        return results

    def dump_failure(self, sub_id: str) -> None:
        target = self.report_dir / "failures" / sub_id
        target.mkdir(parents=True, exist_ok=True)
        for role, proc in self.procs.items():
            try:
                (target / f"{role}.tail.log").write_text(proc.tail(300), encoding="utf-8")
            except OSError:
                pass
        try:
            (target / "requests.jsonl").write_text(
                "\n".join(json.dumps(r.as_json(), ensure_ascii=False)
                          for r in self.mock.records()), encoding="utf-8")
        except OSError:
            pass

    def _write_requests(self) -> None:
        try:
            with (self.run_dir / "requests.jsonl").open("w", encoding="utf-8") as fh:
                for record in self.mock.records():
                    fh.write(json.dumps(record.as_json(), ensure_ascii=False) + "\n")
        except OSError:
            pass

    # ---------- 报告 ----------

    def write_report(self, results: list[SubResult], fatal: str | None = None) -> int:
        total = len(results)
        passed = sum(1 for r in results if r.status == "passed")
        failed = sum(1 for r in results if r.status == "failed")
        errors = sum(1 for r in results if r.status == "error")
        skipped = sum(1 for r in results if r.status == "skipped")
        duration = time.time() - self.started_at
        summary = {
            "runId": self.run_id,
            "loader": "neoforge",
            "minecraft": "1.21.1",
            "neoforge": "21.1.219",
            "tlm": "1.5.3-neoforge+mc1.21.1",
            "scenarioTotal": total,
            "passed": passed,
            "failed": failed,
            "errors": errors,
            "skipped": skipped,
            "durationSeconds": round(duration, 1),
            "startedAt": self.started_at,
            "fatal": fatal,
            "port": self.port,
            "javaHome": self.java_home,
            "gradle": self.gradle,
            "environment": self.environment_summary(),
            "results": [
                {
                    "group": r.group,
                    "sub": r.sub,
                    "status": r.status,
                    "duration": round(r.duration, 2),
                    "error": r.error,
                    "assertions": [
                        {"name": a.name, "ok": a.ok, "detail": a.detail, "evidence": a.evidence}
                        for a in r.assertions
                    ],
                    "evidence": r.evidence,
                }
                for r in results
            ],
        }
        (self.report_dir / "summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        self._write_junit(results, fatal)
        self._write_markdown(summary)
        self._write_requests()
        self.log(f"report: {self.report_dir}")
        ok = fatal is None and failed == 0 and errors == 0 and skipped == 0 and total > 0
        return 0 if ok else 1

    def environment_summary(self) -> dict[str, Any]:
        info: dict[str, Any] = {
            "platform": sys.platform,
            "python": sys.version.split()[0],
            "display": os.environ.get("DISPLAY", ""),
            "xvfb": bool(os.environ.get("DISPLAY")),
            "mesa": os.environ.get("MESA_GL_VERSION_OVERRIDE", ""),
        }
        for key in ("LIBGL_ALWAYS_SOFTWARE", "ALSOFT_DRIVERS", "SDL_AUDIODRIVER"):
            info[key] = os.environ.get(key, "")
        try:
            info["glxinfo"] = subprocess.run(["glxinfo", "-B"], capture_output=True, text=True,
                                             timeout=10).stdout.splitlines()[:8]
        except Exception:
            info["glxinfo"] = []
        return info

    def _write_junit(self, results: list[SubResult], fatal: str | None) -> None:
        suite = ET.Element("testsuite", {
            "name": "ci2-neoforge",
            "tests": str(len(results) + (1 if fatal else 0)),
            "failures": str(sum(1 for r in results if r.status in ("failed", "error")) + (1 if fatal else 0)),
            "errors": "0",
            "skipped": str(sum(1 for r in results if r.status == "skipped")),
            "time": f"{time.time() - self.started_at:.3f}",
        })
        if fatal:
            case = ET.SubElement(suite, "testcase", {"classname": "ci2.fatal", "name": "fatal",
                                                     "time": "0"})
            failure = ET.SubElement(case, "failure", {"message": fatal[:240]})
            failure.text = fatal
        for result in results:
            case = ET.SubElement(suite, "testcase", {
                "classname": f"ci2.{result.group}",
                "name": result.sub,
                "time": f"{result.duration:.3f}",
            })
            if result.status in ("failed", "error"):
                failure = ET.SubElement(case, "failure", {"message": (result.error or result.status)[:500]})
                failure.text = "\n".join(
                    f"{a.name}: {'ok' if a.ok else 'FAIL'} {a.detail}" for a in result.assertions)
            elif result.status == "skipped":
                ET.SubElement(case, "skipped", {"message": result.error or "skipped"})
        tree = ET.ElementTree(suite)
        ET.indent(tree, space="  ")
        tree.write(self.report_dir / "junit.xml", encoding="utf-8", xml_declaration=True)

    def _write_markdown(self, summary: dict[str, Any]) -> None:
        lines = [
            f"# CI 2 / NeoForge Runtime — {summary['runId']}",
            "",
            f"- loader: `{summary['loader']}`",
            f"- game: Minecraft {summary['minecraft']} / NeoForge {summary['neoforge']}",
            f"- TLM: {summary['tlm']}",
            f"- total: {summary['scenarioTotal']} / passed: {summary['passed']} / "
            f"failed: {summary['failed']} / errors: {summary['errors']} / skipped: {summary['skipped']}",
            f"- duration: {summary['durationSeconds']} s",
            "",
            "| group | sub scenario | status | duration | detail |",
            "|---|---|---|---|---|",
        ]
        for row in summary["results"]:
            detail = (row.get("error") or "").replace("|", "\\|").replace("\n", " ")[:180]
            lines.append(f"| {row['group']} | {row['sub']} | {row['status']} | "
                         f"{row['duration']}s | {detail} |")
        if summary.get("fatal"):
            lines.extend(["", "## Fatal", "", summary["fatal"]])
        lines.append("")
        (self.report_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")

    # ---------- 主流程 ----------

    def run(self) -> int:
        self.run_dir.mkdir(parents=True, exist_ok=True)
        self.logs_dir.mkdir(parents=True, exist_ok=True)
        self.report_dir.mkdir(parents=True, exist_ok=True)
        results: list[SubResult] = []
        fatal = None
        try:
            self.prepare()
            self.start_server()
            self.baseline_server()
            self.start_client("owner")
            self.start_client("other")
            self.place_players()
            results = self.run_scenarios()
        except Exception as exc:
            fatal = f"{type(exc).__name__}: {exc}"
            self.log("FATAL: " + fatal)
            self.log(traceback.format_exc())
        finally:
            try:
                self.stop_all()
            except Exception as exc:
                self.log(f"cleanup error: {exc}")
        return self.write_report(results, fatal)

    def baseline_server(self) -> None:
        platform = [
            # 先加载区块，再做 fill；登录前区块未加载时 fill 会静默失败，女仆随后会掉进虚空。
            "forceload add -6 -6 6 6",
            "fill -6 63 -6 6 63 6 minecraft:stone",
            "fill -6 64 -6 6 68 6 minecraft:air",
            "setblock 0 63 0 minecraft:stone",
            # 保留 spawn-animals=true 避免 ServerLevel 每 tick 丢弃 EntityMaid（Animal 子类），
            # 再用 gamerule 停掉自然生成，保证场景里只有显式创建/生成的实体。
            "gamerule doMobSpawning false",
            "gamerule doDaylightCycle false",
            "gamerule doWeatherCycle false",
            "gamerule randomTickSpeed 0",
            "gamerule doFireTick false",
            "gamerule keepInventory true",
            "gamerule mobGriefing false",
            "gamerule naturalRegeneration true",
            "time set 6000",
            "weather clear",
        ]
        for command in platform:
            self.action("server", "server.command", {"command": command}, timeout=60, scenario="setup")
        baseline = {
            "enabled": True,
            "playerOptionEnabled": True,
            "toolCallEnabled": False,
            "state1Enabled": False,
            "state1Min": 10,
            "state1Max": 10,
            "state1Range": 32,
            "state1Keep": 3,
            "state2Enabled": False,
            "state2Min": 10,
            "state2Max": 10,
            "state2Range": 32,
            "state2Keep": 2,
            "welcomeEnabled": False,
            "welcomeWindow": 200,
            "maxTriggerPerSecond": 1,
            "selfTalkMinInterval": 1,
            "selfTalkMaxInterval": 1,
            "deferQueueMax": 3,
            "interChatEnabled": False,
            "interChatMin": 10,
            "interChatMax": 10,
            "interChatPlayerRange": 32,
            "interChatMaidRange": 16,
            "interChatKeepRounds": 2,
            "interChatChainProbability": 0.0,
            "interChatMaxChainRounds": 3,
            "interChatPairLockSeconds": 10,
            "eventContextEnabled": False,
            "eventContextRange": 24,
            "eventContextMaxBuffered": 4,
            "eventContextHurtEnabled": True,
            "eventContextSelfEnabled": True,
            "eventContextHurtMaxAge": 30,
            "chatHistoryMaxStored": 20,
            "prebuiltMinMatchingContexts": 1,
            "prebuiltMaxEntriesPerSource": 4,
            "maidIdentityEnabled": True,
        }
        self.action("server", "server.setup", {"spawnX": 0.5, "spawnZ": 0.5, "values": baseline},
                    timeout=60, scenario="setup")
        self.log("baseline server configured")

    def place_players(self) -> None:
        """玩家登录后再定位；登录前执行 server.player 只会得到 player not online。"""
        self.action("server", "server.player", {"name": "OwnerBot", "x": 0.5, "y": 64, "z": 0.5,
                                                "yaw": -90, "pitch": 0, "heal": True,
                                                "gameMode": "survival"},
                    timeout=60, scenario="setup")
        self.action("server", "server.player", {"name": "OtherBot", "x": 1.5, "y": 64, "z": 0.5,
                                                "yaw": -90, "pitch": 0, "heal": True,
                                                "gameMode": "survival"},
                    timeout=60, scenario="setup")
        self.log("players placed on CI platform")


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="CI 2 NeoForge real-client runtime tests")
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run", help="run scenarios")
    run.add_argument("--loader", required=True, choices=["neoforge", "forge"])
    run.add_argument("--suite", default="all", choices=["all", "base", "optional"])
    run.add_argument("--scenario", default="all", help="scenario group id or sub scenario id")
    run.add_argument("--gradle", default="", help="absolute path to gradle executable")
    run.add_argument("--java-home", default="", help="JDK 21 home")
    run.add_argument("--run-id", default="", help="stable run id")
    run.add_argument("--skip-prepare", action="store_true", help="reuse existing launch descriptors")
    run.add_argument("--verbose", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    if args.command != "run":
        parser.print_help()
        return 2
    if args.loader == "forge":
        print("CI 2 / NeoForge only: --loader forge is not supported by this line", file=sys.stderr)
        return 2
    if args.loader != "neoforge":
        print(f"unsupported loader: {args.loader}", file=sys.stderr)
        return 2
    runner = Runner(args)
    return runner.run()


if __name__ == "__main__":
    raise SystemExit(main())
