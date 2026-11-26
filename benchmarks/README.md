# Device benchmark

Measured on a Pixel 8a running Android 16 with the glass lock-screen mode, connected by USB and charging. The phone was locked and its display was off during both approximately one-minute windows. Activity was real local T3 activity, not a replay.

| Metric | v0.2-style baseline | v0.3 adaptive |
| --- | ---: | ---: |
| Window (s) | 61.33 | 61.12 |
| Companion CPU time | 2.75 s | 0.03 s |
| UID network bytes | 81,396 | 120 |
| UID network packets | 431 | 3 |
| Successful feed responses | 21 | 0 |

The final window had no feed requests; three residual network packets were accounted for during the transition to sleep. The Mac bridge used 2.54 seconds of CPU in the same window while serving active agents. It keeps a warm cache so the phone can be dormant without losing responsiveness.

Four wake trials, following 20 seconds of screen-off time each:

- First response: 1.04–1.62 s; median 1.34 s.
- Fresh response (source age at most 2.5 s): 1.04–3.48 s; median 1.57 s.

These are measured CPU/network counters and response times, **not a measured battery-discharge percentage**. The handset was charging; the workload was live and may vary. The measurements are from the personal build with the same feed and scheduling code as this release. See [results.json](results.json).

## Reproduce

With Android platform tools and USB debugging available for the benchmark (not required to install the app):

```bash
adb shell input keyevent KEYCODE_SLEEP
python3 benchmarks/measure.py --label screen_off --seconds 60
python3 benchmarks/wake.py --trials 4 --sleep-seconds 20
```

The first script reads process CPU time and per-UID network counters without resetting system statistics. The second locks and wakes the display and reads only the companion's timing logs. Neither script unlocks the device or accesses chat titles.
