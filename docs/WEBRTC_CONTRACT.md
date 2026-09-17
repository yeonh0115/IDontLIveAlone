# Direct video signaling contract

This protocol transfers SDP only. No video bytes, saved photos, TURN credentials,
or automatic JPEG relay fallback are sent through these endpoints. Existing app
Bearer sessions and paired CAMERA Bearer identities determine ownership; caller
supplied user numbers or device IDs do not select another camera.

## Endpoints

All paths are relative to the existing Render HTTPS origin. JSON UTF-8; no cache.

| Endpoint | Auth | Request | Success |
|---|---|---|---|
| POST `/api/rtc/sessions` | App | `{"type":"offer","sdp":"..."}` | 201 status object |
| GET `/api/rtc/sessions/{id}` | App owner and original session | none | 200 status; renew viewer lease |
| POST `/api/rtc/sessions/{id}/close` | App owner and original session | none | 204; idempotent |
| GET `/api/rtc/camera/next` | Paired CAMERA | none | 200 offer object, or 204 |
| POST `/api/rtc/camera/sessions/{id}/answer` | Same CAMERA identity | `{"type":"answer","sdp":"..."}` | 204; same answer idempotent |
| GET `/api/rtc/camera/sessions/{id}` | Same CAMERA identity | none | 200 status; does not renew viewer lease |

Status object: `{"sessionId":"uuid","status":"PENDING|READY|CLOSED|EXPIRED","answerType":null,"answerSdp":null,"expiresAt":"ISO-8601 UTC"}`.
For READY, answerType is `answer` and answerSdp is present. Other states never expose an answer.
Offer object: `{"sessionId":"uuid","type":"offer","sdp":"...","expiresAt":"ISO-8601 UTC"}`.

Camera next repeatedly returns the same pending offer until answered/closed/expired.
Pi must deduplicate in-flight negotiation and close any previous peer when a new
session arrives. At most one active session per camera; a new app offer closes the
previous session. SDP limit is 64KiB in UTF-8. Only full, non-trickle SDP is used.

401: invalid/expired/revoked caller credential. 403: unpaired or wrong device role.
404: missing CAMERA or unknown/foreign session. 409: conflicting answer/state.
429: bounded server capacity or offer rate reached. Never return another owner's
SDP. On revoked creator session, unlinked/rekeyed camera or deleted owner, close
the session and return CLOSED to an otherwise authenticated camera. Session UUIDs
and private SDP/ICE addresses are not written to application logs.

## Lifetime and clients

- Pending offer deadline 90s. READY maximum lifetime 30 minutes. Viewer lease 45s.
- App polls each 750ms awaiting an answer (at most 40 polls), then renews via GET
  every 10s while visible. Closing the screen ends PeerConnection and signaling.
- Pi polls next every 2s; once active, also checks its session status every 10s.
  If cloud authorization/status cannot be confirmed for 45s, Pi closes the peer.
- Every status access validates both stored creator session hash and original
  CAMERA token hash/owner. Expiry cleanup is bounded and does not need a database
  schema change. Server restart loses sessions and clients negotiate anew.
- Google STUN `stun:stun.l.google.com:19302` on both sides, no TURN configured.
  Fail clearly when direct connection cannot be established; do not invoke the old
  Render MJPEG/viewer endpoints or a cloud photo endpoint.
- App receives only, using WebView RTCPeerConnection with a local bundled page.
  Native code owns the Bearer token and fixed API paths; the JavaScript bridge
  only submits SDP and receives answer/status, never general network access.
- Pi uses the shared local camera frame source, retaining existing face/GPIO
  services. There is no continuous Render frame upload in WebRTC mode.

## Verification

Cover cross-account and device-role denial, creator logout, camera unlink/rekey,
pending/lease expiry, duplicate answer, replaced session, memory bounds and direct
connection failure without relay fallback. Pi-off is expected offline state.
Real Pi school Wi-Fi to phone LTE test must confirm a non-relay ICE candidate pair
and video bytes arriving while Render receives signaling only.
