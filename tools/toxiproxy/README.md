# MintMusic weak-network lab

1. Put test audio under `media/`, then run `docker compose up -d`.
2. Run `./profile.ps1 weak` (or `normal`, `severe`, `unstable`, `blackhole`, `reset`, `disconnect`, `restore`).
3. On the Android emulator add `http://10.0.2.2:8666/<file>` as an online source. A physical device uses the host LAN IP.

The proxy changes only the media endpoint. Use airplane mode or `adb shell cmd connectivity airplane-mode enable` separately to test Android's system-offline callback.
