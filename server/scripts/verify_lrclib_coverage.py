"""Check LRCLIB timed-lyric coverage for 周杰伦 and 王力宏.

The real-song sample is read from MusicBrainz at runtime. Default: 80 tracks
per artist (160 total) and a UTF-8 CSV report in server/reports/.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlencode

import requests

MUSICBRAINZ = "https://musicbrainz.org/ws/2"
LRCLIB = "https://lrclib.net/api/get"
ARTISTS = ("周杰伦", "王力宏")
USER_AGENT = "MintMusic-LRCLIB-Coverage/1.0 (local verification)"


@dataclass(frozen=True)
class Recording:
    artist: str
    title: str
    duration_seconds: int | None


def get_json(url: str, attempts: int = 4) -> object:
    for attempt in range(attempts):
        try:
            response = requests.get(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"}, timeout=25)
            if response.status_code == 404:
                raise requests.HTTPError(response=response)
            if response.status_code >= 400:
                response.raise_for_status()
            return response.json()
        except requests.HTTPError as error:
            code = error.response.status_code if error.response is not None else 0
            if code == 404 or (code not in {429, 500, 502, 503, 504}) or attempt == attempts - 1:
                raise
        except requests.RequestException:
            if attempt == attempts - 1:
                raise
        time.sleep(1.5 * (attempt + 1))
    raise RuntimeError("retry loop exhausted")


def find_artist_id(name: str) -> str:
    query = urlencode({"query": f'artist:"{name}"', "fmt": "json", "limit": 5})
    payload = get_json(f"{MUSICBRAINZ}/artist/?{query}")
    assert isinstance(payload, dict)
    matched = next(
        (
            item for item in payload.get("artists", [])
            if item.get("name") == name
            or any(alias.get("name") == name for alias in item.get("aliases", []))
        ),
        None,
    )
    # MusicBrainz often stores the Traditional-Chinese primary name and the
    # Simplified-Chinese value as an alias (e.g. 周杰倫 / 周杰伦).
    if matched is None:
        matched = next((item for item in payload.get("artists", []) if item.get("score") == 100), None)
    if not matched:
        raise RuntimeError(f"MusicBrainz exact artist match missing: {name}")
    return str(matched["id"])


def collect_recordings(artist: str, minimum: int) -> list[Recording]:
    unique: dict[str, Recording] = {}
    artist_id = find_artist_id(artist)
    offset = 0
    while len(unique) < minimum and offset < 1000:
        query = urlencode({"artist": artist_id, "fmt": "json", "limit": 100, "offset": offset})
        payload = get_json(f"{MUSICBRAINZ}/recording/?{query}")
        assert isinstance(payload, dict)
        recordings = payload.get("recordings", [])
        if not recordings:
            break
        for item in recordings:
            title = str(item.get("title", "")).strip()
            if not title or title.casefold() in unique:
                continue
            length = item.get("length")
            duration = round(length / 1000) if isinstance(length, int) and length > 0 else None
            unique[title.casefold()] = Recording(artist, title, duration)
        offset += len(recordings)
        time.sleep(1.05)  # MusicBrainz policy: max one request per second.
    if len(unique) < minimum:
        raise RuntimeError(f"{artist}: only {len(unique)} unique recordings found")
    return sorted(unique.values(), key=lambda item: item.title.casefold())[:minimum]


def check(recording: Recording) -> tuple[bool, str]:
    query = {"track_name": recording.title, "artist_name": recording.artist}
    if recording.duration_seconds:
        query["duration"] = str(recording.duration_seconds)
    try:
        payload = get_json(f"{LRCLIB}?{urlencode(query)}")
    except requests.HTTPError as error:
        code = error.response.status_code if error.response is not None else 0
        return False, "not_found" if code == 404 else f"http_{code}"
    except requests.RequestException as error:
        return False, f"network_{type(error).__name__}"
    return (bool(isinstance(payload, dict) and payload.get("syncedLyrics")), "matched" if isinstance(payload, dict) and payload.get("syncedLyrics") else "no_synced_lrc")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--per-artist", type=int, default=80)
    parser.add_argument("--delay", type=float, default=0.35)
    parser.add_argument("--output", type=Path, default=Path("server/reports/lrclib_coverage.csv"))
    args = parser.parse_args()
    if args.per_artist < 75:
        parser.error("--per-artist must be >= 75 (at least 150 total samples)")
    sample = [track for artist in ARTISTS for track in collect_recordings(artist, args.per_artist)]
    rows: list[dict[str, object]] = []
    for index, track in enumerate(sample, 1):
        matched, result = check(track)
        rows.append({"artist": track.artist, "title": track.title, "duration_seconds": track.duration_seconds or "", "matched": matched, "result": result})
        print(f"[{index:03}/{len(sample)}] {track.artist} - {track.title}: {result}")
        time.sleep(args.delay)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8-sig") as file:
        writer = csv.DictWriter(file, fieldnames=rows[0].keys())
        writer.writeheader(); writer.writerows(rows)
    for artist in ARTISTS:
        subset = [row for row in rows if row["artist"] == artist]
        hits = sum(bool(row["matched"]) for row in subset)
        print(f"{artist}: {hits}/{len(subset)} ({hits / len(subset):.1%})")
    hits = sum(bool(row["matched"]) for row in rows)
    print(f"TOTAL: {hits}/{len(rows)} ({hits / len(rows):.1%})\nReport: {args.output.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
