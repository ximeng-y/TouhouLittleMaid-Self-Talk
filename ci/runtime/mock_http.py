"""假 LLM / TTS 服务与请求账本。

只实现产品实际使用的 OpenAI 兼容非流式信封与 Siliconflow TTS 适配器；
每个请求都记账，场景断言只看账本，不允许“默认成功回答”掩盖未预期请求。
"""

from __future__ import annotations

import base64
import json
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "audio"


def _read_audio(name: str) -> bytes:
    return (FIXTURE_DIR / name).read_bytes()


DEFAULT_MP3 = _read_audio("silence.mp3")
OGG_VORBIS = _read_audio("silence.ogg")
OGG_OPUS = _read_audio("silence-opus.ogg")
NEGATIVE_WAV = _read_audio("negative.wav")


def _default_behavior() -> dict[str, Any]:
    return {
        "llm_mode": "ok",
        "tts_mode": "ok",
        "delay": 0.0,
        "status": 500,
        "content": "测试回答。---测试回答。",
        "malformed": '{"choices":[',
        "tool_name": "switch_sit",
        "tool_arguments": '{"sit":true}',
        "tool_turns": 1,
        "tool_final": "已经坐下了。---已经坐下了。",
        "split": {
            "identity": "测试身份",
            "personality": "测试性格",
            "background": "测试背景",
            "hobbies": "测试爱好",
        },
        "planning": {
            "history_keywords": ["旧事"],
            "persona_keywords": ["背景"],
        },
        "tts_audio": "mp3",
        "tts_status": 200,
        "tts_content_type": "audio/mpeg",
        "sequence": [],
    }


@dataclass
class RequestRecord:
    seq: int
    kind: str
    scenario: str
    time: float
    body: dict[str, Any]
    headers: dict[str, str]
    response_status: int
    response_bytes: int
    behavior: dict[str, Any] = field(default_factory=dict)

    def as_json(self) -> dict[str, Any]:
        return {
            "seq": self.seq,
            "kind": self.kind,
            "scenario": self.scenario,
            "time": self.time,
            "body": self.body,
            "headers": self.headers,
            "responseStatus": self.response_status,
            "responseBytes": self.response_bytes,
            "behavior": {k: v for k, v in self.behavior.items() if k != "sequence"},
        }


class MockController:
    """线程安全的行为开关与账本；控制线程与 HTTP 线程共用。"""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._behavior = _default_behavior()
        self._requests: list[RequestRecord] = []
        self._seq = 0
        self.active_scenario = "setup"

    def reset(self, scenario: str = "setup") -> None:
        with self._lock:
            self._behavior = _default_behavior()
            self._requests.clear()
            self._seq = 0
            self.active_scenario = scenario

    def configure(self, **values: Any) -> None:
        with self._lock:
            for key, value in values.items():
                if key == "sequence":
                    self._behavior["sequence"] = list(value)
                elif key in self._behavior:
                    self._behavior[key] = value
                else:
                    raise KeyError(f"unknown mock behavior: {key}")

    def behavior(self) -> dict[str, Any]:
        with self._lock:
            return dict(self._behavior)

    def set_scenario(self, scenario: str) -> None:
        with self._lock:
            self.active_scenario = scenario

    def records(self) -> list[RequestRecord]:
        with self._lock:
            return list(self._requests)

    def mark(self) -> int:
        with self._lock:
            return self._seq

    def llm_requests(self, since: int = 0) -> list[RequestRecord]:
        return [r for r in self.records() if r.kind == "llm" and r.seq > since]

    def tts_requests(self, since: int = 0) -> list[RequestRecord]:
        return [r for r in self.records() if r.kind == "tts" and r.seq > since]

    def _next_behavior(self) -> dict[str, Any]:
        with self._lock:
            behavior = dict(self._behavior)
            sequence = behavior.get("sequence") or []
            if sequence:
                head = sequence.pop(0)
                behavior.update(head)
                self._behavior["sequence"] = sequence
            return behavior

    def handle_llm(self, body: dict[str, Any], headers: dict[str, str]) -> tuple[int, bytes, str, float]:
        behavior = self._next_behavior()
        delay = float(behavior.get("delay") or 0)
        mode = behavior.get("llm_mode", "ok")
        status = 200
        content_type = "application/json; charset=utf-8"
        if mode == "http_error":
            status = int(behavior.get("status") or 500)
            payload = json.dumps({"error": {"message": "ci mock http error", "type": "ci"}}).encode()
        elif mode == "malformed":
            payload = str(behavior.get("malformed") or '{"choices":[').encode()
        elif mode == "empty":
            payload = b""
        elif mode == "split":
            content = json.dumps(behavior.get("split") or _default_behavior()["split"],
                                 ensure_ascii=False)
            payload = self._chat_payload(body, content)
        elif mode == "planning":
            content = json.dumps(behavior.get("planning") or _default_behavior()["planning"],
                                 ensure_ascii=False)
            payload = self._chat_payload(body, content)
        elif mode == "tool_call":
            payload = self._tool_payload(body, behavior)
        else:
            payload = self._ok_payload(body, behavior)
        self._record("llm", body, headers, status, len(payload), behavior)
        return status, payload, content_type, delay

    def handle_tts(self, body: dict[str, Any], headers: dict[str, str]) -> tuple[int, bytes, str, float]:
        behavior = self._next_behavior()
        delay = float(behavior.get("delay") or 0)
        mode = behavior.get("tts_mode", "ok")
        if mode == "http_error":
            status = int(behavior.get("tts_status") or 500)
            payload = json.dumps({"error": "ci mock tts error"}).encode()
            content_type = "application/json"
        elif mode == "empty":
            status = 200
            payload = b""
            content_type = "audio/mpeg"
        elif mode == "wav":
            status = 200
            payload = NEGATIVE_WAV
            content_type = "audio/wav"
        elif mode == "ogg":
            status = 200
            payload = OGG_VORBIS
            content_type = "audio/ogg"
        elif mode == "opus":
            status = 200
            payload = OGG_OPUS
            content_type = "audio/ogg"
        else:
            status = 200
            payload = DEFAULT_MP3
            content_type = str(behavior.get("tts_content_type") or "audio/mpeg")
        self._record("tts", body, headers, status, len(payload), behavior)
        return status, payload, content_type, delay

    def _record(self, kind: str, body: dict[str, Any], headers: dict[str, str],
                status: int, size: int, behavior: dict[str, Any]) -> None:
        with self._lock:
            self._seq += 1
            record = RequestRecord(
                seq=self._seq,
                kind=kind,
                scenario=self.active_scenario,
                time=time.time(),
                body=body,
                headers=headers,
                response_status=status,
                response_bytes=size,
                behavior={k: v for k, v in behavior.items() if k != "sequence"},
            )
            self._requests.append(record)

    @staticmethod
    def _chat_payload(body: dict[str, Any], content: str) -> bytes:
        model = str(body.get("model") or "ci-model")
        envelope = {
            "id": "chatcmpl-ci",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": model,
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": "stop",
                }
            ],
            "usage": {
                "prompt_tokens": 120,
                "completion_tokens": 24,
                "total_tokens": 144,
            },
        }
        return json.dumps(envelope, ensure_ascii=False).encode()

    @classmethod
    def _ok_payload(cls, body: dict[str, Any], behavior: dict[str, Any]) -> bytes:
        return cls._chat_payload(body, str(behavior.get("content") or ""))

    def _tool_payload(self, body: dict[str, Any], behavior: dict[str, Any]) -> bytes:
        messages = body.get("messages") or []
        tool_messages = [m for m in messages if isinstance(m, dict) and m.get("role") == "tool"]
        turns = int(behavior.get("tool_turns") or 1)
        if len(tool_messages) < turns:
            name = str(behavior.get("tool_name") or "switch_sit")
            arguments = str(behavior.get("tool_arguments") or "{}")
            envelope = {
                "id": "chatcmpl-ci-tool",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": str(body.get("model") or "ci-model"),
                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": f"call_ci_{len(tool_messages) + 1}",
                                    "type": "function",
                                    "function": {"name": name, "arguments": arguments},
                                }
                            ],
                        },
                        "finish_reason": "tool_calls",
                    }
                ],
                "usage": {"prompt_tokens": 120, "completion_tokens": 18, "total_tokens": 138},
            }
        else:
            content = str(behavior.get("tool_final") or behavior.get("content") or "")
            envelope = {
                "id": "chatcmpl-ci-final",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": str(body.get("model") or "ci-model"),
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": content},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {"prompt_tokens": 160, "completion_tokens": 24, "total_tokens": 184},
            }
        return json.dumps(envelope, ensure_ascii=False).encode()


def b64_audio(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")
