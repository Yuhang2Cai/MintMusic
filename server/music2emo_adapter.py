"""Lazy, GPU-aware adapter for the official Music2Emo project.

The model is intentionally optional.  The lyric service stays lightweight until
an operator installs the official Music2Emo checkout and its model weights.
"""

from __future__ import annotations

import os
import sys
import threading
from pathlib import Path
from typing import Any


class Music2EmoUnavailable(RuntimeError):
    """Raised when Music2Emo has not been installed on this server."""


class Music2EmoAdapter:
    """Load Music2Emo once, then run serial predictions on the configured GPU."""

    def __init__(self) -> None:
        self._model: Any | None = None
        self._load_lock = threading.Lock()
        self._predict_lock = threading.Lock()

    def _load(self) -> Any:
        if self._model is not None:
            return self._model
        with self._load_lock:
            if self._model is not None:
                return self._model
            checkout = Path(
                os.getenv(
                    "MINT_MUSIC2EMO_HOME",
                    Path(__file__).resolve().parent / "vendor" / "Music2Emotion",
                )
            )
            if checkout.is_dir() and str(checkout) not in sys.path:
                sys.path.insert(0, str(checkout))
            if not checkout.is_dir():
                raise Music2EmoUnavailable("Music2Emo 官方模型目录不存在")
            # The upstream project resolves bundled checkpoints/configuration
            # relative to its repository root.
            os.chdir(checkout)
            try:
                # This is the public API exposed by AMAAI-Lab/Music2Emotion.
                from music2emo import Music2emo
            except ImportError as error:
                raise Music2EmoUnavailable(
                    "Music2Emo 尚未安装。请按 server/README.md 安装官方模型后重试。"
                ) from error
            try:
                self._model = Music2emo()
            except Exception as error:  # model weights / CUDA errors need a useful API response
                raise Music2EmoUnavailable(f"Music2Emo 模型加载失败：{error}") from error
            return self._model

    def predict(self, audio_path: Path) -> dict[str, Any]:
        model = self._load()
        try:
            # The underlying model keeps mutable inference state; serialising a
            # GPU run prevents concurrent uploads from exhausting 8 GB VRAM.
            with self._predict_lock:
                raw = model.predict(str(audio_path))
        except Exception as error:
            raise RuntimeError(f"Music2Emo 分析失败：{error}") from error
        if not isinstance(raw, dict):
            raise RuntimeError("Music2Emo 返回了无法识别的结果")
        moods = raw.get("predicted_moods", [])
        if isinstance(moods, str):
            moods = [moods]
        if not isinstance(moods, list):
            moods = []
        return {
            "moods": [str(item) for item in moods if str(item).strip()],
            "valence": _number(raw.get("valence")),
            "arousal": _number(raw.get("arousal")),
        }


def _number(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
