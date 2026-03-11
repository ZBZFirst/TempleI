# STEP 09 — FFmpeg Egress Remediation Plan

## Objective
Restore confidence that the **active Screen 2 FFmpeg backend path** performs real end-to-end stream egress (Android caller -> OBS listener) and that health signals reflect true wire output.

## Problem statement
Current FFmpeg runtime behavior appears strong on startup gating and diagnostics, but packet/byte counters can be satisfied by accepted ingress access units instead of confirmed container/network writes. This can make stream status look healthy before true MPEG-TS/SRT output is proven.

## Success criteria
- Active backend emits real SRT output consumable by OBS Media Source Input URL (`input_format=mpegts`).
- Health transitions to healthy only after verified write-path evidence.
- Counters distinguish:
  - encoded AU ingress,
  - muxed packet creation,
  - successful network writes,
  - write failures/retries.
- Diagnostics snapshot and runtime health panel provide unambiguous, operator-usable evidence.

## Delivery phases

### Phase 1 — Metric semantics hardening (no behavior regression)
1. Split FFmpeg native counters into explicit domains:
   - `videoAuIngress`, `audioAuIngress`
   - `muxPacketsProduced`, `muxBytesProduced`
   - `writePacketsSucceeded`, `writeBytesSucceeded`
   - `writePacketsFailed`, `consecutiveWriteFailures`
2. Remove aliasing where `packetsWritten`/`bytesWritten` mirror ingress counters.
3. Keep backward-compatible diagnostics fields temporarily, but add new canonical fields and mark old fields deprecated in comments.
4. Update Kotlin parsers and UI text to consume canonical write counters first.

**Exit check:** `runtimeHealth.packetsWritten` derives from write-success counters only.

### Phase 2 — FFmpeg output lifecycle implementation
1. Implement persistent runtime objects for output context and stream handles.
2. On `nativePrepare(...)`:
   - validate endpoint/mode,
   - construct SRT output URL,
   - initialize format context for MPEG-TS over SRT.
3. On `nativeStart()`:
   - open output (`avio_open2`),
   - write header (`avformat_write_header`),
   - transition to started only on success.
4. On `nativeStop()`:
   - write trailer if started,
   - close IO,
   - free contexts safely,
   - preserve terminal error detail.

**Exit check:** start only succeeds when output is actually opened and header written.

### Phase 3 — AU -> packet -> network write path
1. Convert accepted H.264/AAC AUs into `AVPacket` with correct stream index and timing.
2. Rescale timestamps into stream timebase; preserve monotonic guards already present.
3. Call `av_interleaved_write_frame` for each packet.
4. Increment write-success counters only after successful write return code.
5. On write failure:
   - increment failure counters,
   - set actionable `lastError`,
   - update connection/write state to retrying/faulted.

**Exit check:** packet/write counters advance only on successful FFmpeg write API calls.

### Phase 4 — Health gating and diagnostics alignment
1. Ensure Kotlin health logic treats stream as healthy only when:
   - connected,
   - runtime active (non-stub),
   - `writePacketsSucceeded > 0`,
   - no active consecutive write failures.
2. Expand diagnostics snapshot fields:
   - URL/mode summary,
   - header-written flag,
   - produced-vs-written counter pair,
   - last write error code + message,
   - retry/failure totals.
3. Add explicit warning for `produced > 0 && written == 0` after threshold.

**Exit check:** “healthy” cannot appear without true write evidence.

### Phase 5 — Test plan
1. Native unit/integration-style checks (where feasible in current harness):
   - prepare/start failures propagate deterministic error text,
   - write counter invariants hold,
   - stop is idempotent and clears runtime safely.
2. Kotlin tests:
   - runtime health parser prefers canonical write fields,
   - unhealthy status when ingress active but write counters are zero,
   - healthy status only when write counters are positive and failures cleared.
3. Device validation checklist:
   - OBS listener setup with Input URL,
   - start stream from Screen 2,
   - verify OBS receives moving video,
   - verify diagnostics snapshot records non-zero write counters.

**Exit check:** tests and device checklist both pass.

## Risk management
- **Risk:** FFmpeg symbol probe passes but runtime output init fails at header/open stage.  
  **Mitigation:** fail start early with explicit reason + operator-facing remediation.
- **Risk:** timestamp regressions under capture jitter.  
  **Mitigation:** preserve existing PTS fixup instrumentation and expose fixup counts in diagnostics.
- **Risk:** false confidence from mixed old/new counters during migration.  
  **Mitigation:** prioritize canonical fields in parser/UI; keep legacy fields read-only for one transition cycle.

## Implementation order recommendation
1. Phase 1 (counter semantics)  
2. Phase 4 partial (health parser switch)  
3. Phase 2 + 3 (real write path)  
4. Phase 4 completion (final health/reporting rules)  
5. Phase 5 (tests + device validation)

This order minimizes operator-facing ambiguity early while enabling incremental backend hardening.
