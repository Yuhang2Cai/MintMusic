"""Mint Music online timed-lyric lookup service."""

from __future__ import annotations

import os
import re
import shutil
import tempfile
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel

from music2emo_adapter import Music2EmoAdapter, Music2EmoUnavailable

ONLINE_LYRICS_ENABLED = os.getenv("MINT_ONLINE_LYRICS_ENABLED", "true").lower() in {"1", "true", "yes"}
LRCAPI_URL = os.getenv("MINT_LRCAPI_URL", "https://api.lrc.cx/lyrics")
LRCLIB_URL = os.getenv("MINT_LRCLIB_URL", "https://lrclib.net/api/get")

app = FastAPI(title="Mint Music Lyrics", version="2.0.0")
music2emo = Music2EmoAdapter()
MAX_EMOTION_AUDIO_BYTES = 80 * 1024 * 1024
EMOTION_AUDIO_SUFFIXES = {
    "audio/mpeg": ".mp3",
    "audio/mp3": ".mp3",
    "audio/mp4": ".m4a",
    "audio/x-m4a": ".m4a",
    "audio/flac": ".flac",
    "audio/x-flac": ".flac",
    "audio/wav": ".wav",
    "audio/x-wav": ".wav",
    "audio/wave": ".wav",
    "audio/ogg": ".ogg",
    "audio/aac": ".aac",
}


class LyricLookup(BaseModel):
    """A metadata-verified online lyric candidate.

    `synced_lyrics` is intentionally only returned when the provider has an LRC
    time line. Plain-text lyrics are not useful to the player page and must not
    replace an existing timed lyric file.
    """

    found: bool
    source: str | None = None
    synced_lyrics: str | None = None


class MusicEmotion(BaseModel):
    """Music2Emo's categorical moods plus continuous valence/arousal."""

    moods: list[str]
    valence: float | None = None
    arousal: float | None = None


def has_timed_lines(text: str) -> bool:
    """Return true only for player-usable LRC time tags."""
    return bool(re.search(r"\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?\]", text))


def lrcapi_lyrics(title: str, artist: str, album: str = "") -> str | None:
    """Get the best LRC candidate from LrcAPI's metadata endpoint."""
    query = {"title": title}
    if artist.strip() and artist.strip() not in {"本地音乐", "unknown", "<unknown>"}:
        query["artist"] = artist.strip()
    if album.strip() and album.strip() not in {"[Unknown Album]", "unknown", "<unknown>"}:
        query["album"] = album.strip()
    request = Request(
        f"{LRCAPI_URL}?{urlencode(query)}",
        headers={"User-Agent": "MintMusic/1.0 (lyrics lookup)"},
    )
    try:
        with urlopen(request, timeout=12) as response:
            lyrics = response.read().decode("utf-8")
    except (HTTPError, URLError, TimeoutError, UnicodeDecodeError):
        return None
    return sanitize_lrc_text(lyrics) if has_timed_lines(lyrics) else None


def lrclib_lyrics(title: str, artist: str, album: str = "", duration: int = 0) -> str | None:
    """Resolve an LRC from LRCLIB with metadata-based matching.

    The lookup is metadata based, not a blind web download.  This prevents a
    same-name song from silently replacing the lyrics for the current track.
    This is deliberately a metadata API client rather than a page scraper.
    """
    if not ONLINE_LYRICS_ENABLED or not title.strip():
        return None
    clean_title = re.sub(r"\s*[（(][^（）()]*[）)]\s*$", "", title).strip()
    # Local tags commonly append film/album descriptors in parentheses. Query the
    # literal tag first, then its canonical song title; both retain artist and
    # duration constraints when available. Some local encoders report duration
    # differently from the catalogue, so retry an exact title + artist query
    # without duration before treating the lookup as a miss.
    for candidate_title in dict.fromkeys((title.strip(), clean_title)):
        if not candidate_title:
            continue
        for include_duration in (True, False):
            query = {"track_name": candidate_title}
            if artist.strip() and artist.strip() not in {"本地音乐", "unknown", "<unknown>"}:
                query["artist_name"] = artist.strip()
            if album.strip() and include_duration:
                query["album_name"] = album.strip()
            if duration > 0 and include_duration:
                query["duration"] = str(duration)
            request = Request(
                f"{LRCLIB_URL}?{urlencode(query)}",
                headers={"User-Agent": "MintMusic/1.0 (lyrics lookup)"},
            )
            try:
                import json

                with urlopen(request, timeout=12) as response:
                    payload = json.loads(response.read().decode("utf-8"))
            except (HTTPError, URLError, TimeoutError, ValueError):
                continue
            lyrics = payload.get("syncedLyrics") if isinstance(payload, dict) else None
            if isinstance(lyrics, str) and "[" in lyrics:
                return sanitize_lrc_text(lyrics)
    return None


def online_lyrics(title: str, artist: str, album: str = "", duration: int = 0) -> tuple[str | None, str | None]:
    """Use LrcAPI first, then LRCLIB; return player-safe timed LRC only."""
    if not ONLINE_LYRICS_ENABLED or not title.strip():
        return None, None
    clean_title = re.sub(r"\s*[（(][^（）()]*[）)]\s*$", "", title).strip()
    for candidate_title in dict.fromkeys((title.strip(), clean_title)):
        if not candidate_title:
            continue
        lyrics = lrcapi_lyrics(candidate_title, artist, album)
        if lyrics is not None:
            return lyrics, "LrcAPI"
    lyrics = lrclib_lyrics(title, artist, album, duration)
    return (lyrics, "LRCLIB") if lyrics is not None else (None, None)


def sanitize_lrc_text(text: str) -> str:
    lines = [line.replace("\ufffd", "").rstrip() for line in text.splitlines()]
    return "\n".join(line for line in lines if line.strip()) + "\n"


@app.get("/health")
def health() -> dict[str, str]:
    return {
        "status": "ok",
        "lyrics_mode": "online_only",
    }


@app.get("/v1/lyrics/lookup", response_model=LyricLookup)
def lookup_lyrics(
    title: str,
    artist: str = "",
    album: str = "",
    duration: int = 0,
) -> LyricLookup:
    """Fetch metadata-matched timed lyrics. Audio is never uploaded or transcribed."""
    lyrics, source = online_lyrics(title, artist, album, duration)
    return LyricLookup(
        found=lyrics is not None,
        source=source,
        synced_lyrics=lyrics,
    )


@app.post("/v1/music-emotions", response_model=MusicEmotion)
async def analyze_music_emotion(audio: UploadFile = File(...)) -> MusicEmotion:
    """Analyse one local audio file with Music2Emo, then delete the upload.

    This endpoint is intentionally separate from lyric lookup: it never creates
    lyrics and does not retain the listener's audio after the request finishes.
    """
    supplied_suffix = os.path.splitext(audio.filename or "")[1].lower()
    suffix = supplied_suffix if supplied_suffix in set(EMOTION_AUDIO_SUFFIXES.values()) else EMOTION_AUDIO_SUFFIXES.get(
        (audio.content_type or "").split(";", 1)[0].lower(),
        ".audio",
    )
    temp_dir = tempfile.mkdtemp(prefix="mint-music2emo-")
    audio_path = os.path.join(temp_dir, f"input{suffix}")
    try:
        written = 0
        with open(audio_path, "wb") as target:
            while chunk := await audio.read(1024 * 1024):
                written += len(chunk)
                if written > MAX_EMOTION_AUDIO_BYTES:
                    raise HTTPException(status_code=413, detail="音频文件不能超过 80 MB")
                target.write(chunk)
        result = music2emo.predict(Path(audio_path))
        return MusicEmotion(**result)
    except Music2EmoUnavailable as error:
        raise HTTPException(status_code=503, detail=str(error)) from error
    except HTTPException:
        raise
    except Exception as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    finally:
        await audio.close()
        shutil.rmtree(temp_dir, ignore_errors=True)
