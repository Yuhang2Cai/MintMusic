# Mint Music lyrics and emotion service

The service first looks up a metadata-matched, time-synchronised LRC (song title,
artist, album, and duration) from LrcAPI, then tries LRCLIB. If no timed lyric
is found, the app reports the miss. It never uploads audio or generates lyrics
with an AI recognizer, so inaccurate transcription cannot replace song lyrics.

```powershell
cd <project-root>
python -m venv .venv
.venv\Scripts\python -m pip install -r server\requirements.txt
.venv\Scripts\python -m uvicorn app:app --app-dir server --host 0.0.0.0 --port 8000
```

Python 3.11+ is required.

Online matching is enabled by default. Set `MINT_ONLINE_LYRICS_ENABLED=false`
to disable lyric lookup. The current adapter is deliberately an API client rather than
a web scraper: it accepts a synced LRC only when the metadata endpoint confirms
the current song. This prevents wrong lyrics for same-name tracks and keeps the
provider configurable via `MINT_LRCAPI_URL` and `MINT_LRCLIB_URL`. LrcAPI is
queried first and its response is accepted only if it contains LRC time tags.

## Catalogue coverage check

Run this to inspect timed-lyric coverage using real recordings from MusicBrainz:

```powershell
.venv\Scripts\python server\scripts\verify_lrclib_coverage.py
```

It samples 80 songs each for 周杰伦 and 王力宏 (160 total), throttles requests
to both services, and writes a row-by-row report to `server/reports/lrclib_coverage.csv`.
Use `--per-artist 100` to increase the check to 200 songs.

For production, place the API behind TLS/authentication and enforce appropriate
rate limits for the upstream providers.

## Music2Emo song-emotion analysis

The player uploads one local song to `POST /v1/music-emotions` when the user starts
an emotion analysis.
This is **not** a lyric generator: the service returns Music2Emo's mood labels,
valence and arousal, then immediately deletes the temporary upload. The model
is lazy-loaded only when the endpoint is called and runs one GPU inference at a
time to protect consumer GPUs with 8 GB VRAM.

Install the official AMAAI-Lab project and its Python/CUDA dependencies in the
same Python environment, then point the service at its checkout:

```powershell
git clone https://github.com/AMAAI-Lab/Music2Emotion server\vendor\Music2Emotion
.venv\Scripts\python -m pip install -r server\vendor\Music2Emotion\requirements.txt
$env:MINT_MUSIC2EMO_HOME = (Resolve-Path server\vendor\Music2Emotion)
```

The official checkpoint is downloaded by Music2Emo on its first successful
load. If the model or CUDA dependencies are unavailable, the endpoint returns
HTTP 503; it never substitutes a guessed emotion result.
