# TempleI Raw Transmission Debug Guide (OBS SRT MPEG-TS)

This file is the single place to track **what we actually transmit** versus **what we expect to transmit** during Android -> OBS bring-up.

## Runtime model (must remain true)
- OBS starts first as **SRT listener**.
- Android starts second as **SRT caller**.
- OBS Media Source uses `input_format=mpegts`.

---

## 1) Raw transmission evidence to collect each run

### A. Android app logs (transport + mux + encoder)
Collect with:

```bash
adb logcat -c
adb logcat -v time | rg "TempleI-SrtTransport|TempleI-TsMux|TempleI-VideoEnc|TempleI-MuxStub"
```

Recommended (narrower) filter during active startup debugging:

```bash
adb logcat -c
adb logcat -v time | rg "TempleI-SrtTransport|TempleI-TsMux|TempleI-VideoEnc|TempleI-MuxStub|startup-send-gate|first-idr-delivered|first PAT/PMT emitted|analysis frame size observed"
```

If logs are scrolling too fast, write a run log file for later review:

```bash
adb logcat -c
adb logcat -v time | rg "TempleI-SrtTransport|TempleI-TsMux|TempleI-VideoEnc|TempleI-MuxStub|startup-send-gate|first-idr-delivered|first PAT/PMT emitted|analysis frame size observed" | tee obs-debug-run.log
```

Then inspect only startup/failure markers:

```bash
rg "connect-open|send-loop started|startup-send-gate|first PAT/PMT emitted|first-idr-delivered|send-packet ok|Failed to open media|Failed to find stream info|Could not detect TS packet size" obs-debug-run.log
```

Optional stage-isolated filters:

```bash
# Transport only
adb logcat -v time | rg "TempleI-SrtTransport|connect-open|send-loop started|send-packet|connect-close"

# Mux only
adb logcat -v time | rg "TempleI-TsMux|TempleI-MuxStub|first PAT/PMT emitted|startup-send-gate"

# Encoder only
adb logcat -v time | rg "TempleI-VideoEnc|codec-start|codec-format|first-idr-delivered|encoder-reconfigure"
```

Capture these lines:
- SRT open/start/send/close lines.
- Mux startup state lines.
- TS packet count + PAT/PMT lines.
- Encoder format/config/IDR lines.

### B. OBS/FFmpeg probe output
Copy these exact lines from OBS log:
- `Could not detect TS packet size...`
- `Failed to find stream info...`
- `Failed to open media...`
- `av_read_frame failed...`

### C. Session metadata
Record:
- Device model + Android version
- OBS version
- URL used (`srt://host:port?mode=listener&timeout=...`)
- Hardware decoding on/off
- FFmpeg options (probesize/analyzeduration)

---

## 2) What we are transmitting now (intended current behavior)

### Transport
- SRT sender connects in caller mode.
- Sender writes raw packet bytes passed by mux.

### Container
- MPEG-TS packets are 188 bytes.
- TS sync byte at packet start should be `0x47`.
- PAT/PMT startup burst + periodic refresh are enabled.
- PCR is emitted regularly on video packets.

### Stream shape
- Bring-up is currently **video-only** (audio suppressed for startup stability).
- Encoder sends H.264 with short GOP (`I_FRAME_INTERVAL_SECONDS=1`).
- Startup gate opens only after codec config + first keyframe are latched.

---

## 3) What we EXPECT to see in raw startup sequence

Within first 250-500ms after Start:

1. SRT connect success (`caller` -> OBS listener)
2. Mux startup state progresses to gate-open sequence
3. PAT/PMT packets repeated at startup (not just once)
4. Video PES appears with decodable path (SPS/PPS + IDR early)
5. PCR appears at regular cadence on video PID packets
6. Continuous TS packet flow without long stalls

If any are missing, mark run as "probe-unfriendly startup".

---

## 4) Quick pass/fail checklist per run

- [ ] `connect-open mode=caller ... state=SRTS_CONNECTED`
- [ ] `send-loop started ...`
- [ ] `muxer-start container=MPEG-TS packetSize=188`
- [ ] `first PAT/PMT emitted`
- [ ] `startup-send-gate opened ...`
- [ ] `first-idr-delivered ...`
- [ ] recurring `send-packet ok ...`
- [ ] OBS does **not** print packet-size/probe failures

---

## 5) Known failure signatures

### Signature A: Probe fails immediately
Symptoms:
- OBS: cannot detect TS packet size / failed stream info / failed open.

Likely causes:
- Early payload not decodable enough for FFmpeg probe window.
- PSI/PCR present but startup timing still probe-unfriendly.

### Signature B: Camera/analysis backpressure
Symptoms:
- Android: `ImageReader ... dequeueBuffer ... timeout`.

Likely causes:
- Analyzer/encoder mismatch or processing stalls starving camera pipeline.

---

## 6) Run log template (fill per attempt)

```text
Run ID:
Date/Time:
Device/OS:
OBS Version:
OBS URL:
OBS FFmpeg options:
HW decode:

Android key lines:
- connect-open:
- send-loop started:
- muxer-start:
- first PAT/PMT:
- startup-send-gate:
- first-idr-delivered:
- send-packet ok count sample:

OBS key lines:
- packet-size warning:
- stream-info failure:
- open failure:
- av_read_frame failure:

Result:
- PASS/FAIL
- suspected first failing stage:
- next action:
```
