# Android 1.2.2: bundled video page recovery

The previous viewer timed out in its local page-loading phase before sending any WebRTC offer. On a physical Android phone, its request interceptor returned a blocked response and the bundled page never called the native ready bridge.

The viewer now loads one fixed HTTPS app-assets URL. Only a main-frame GET for that exact URL receives the bundled HTML; every other intercepted request still receives 403 with no network fallback. JavaScript, styles and video controls remain bundled. The existing origin guard, restrictive CSP, denied permissions and native-only API authentication remain in place. A terminated WebView renderer is removed and destroyed without executing JavaScript on it.

Fixed diagnostic events record the loading/signaling stage, HTTP status and candidate counts. They exclude SDP, addresses, credentials, session IDs and arbitrary exception/console text.

## Verification

- Android versionCode 5, versionName 1.2.2; existing signing certificate retained.
- JVM tests: 32 passed. Bundled JavaScript tests: 11 passed.
- Debug build and lint passed; lint has 0 errors and 192 existing warnings.
- A physical phone reached local-document served, page ready, offer HTTP 201, answer ready and video playing. The first frame was reported about 8.5 seconds after starting that attempt.
- Selected ICE path was srflx/srflx with increasing received bytes and decoded frames. No TURN or Render video relay was used in this observed connection. Wi-Fi versus cellular access was not independently verified.
- This release changes Android only. Backend and Pi releases remain unchanged.

APK SHA256: `a3fd14d4409ae0bea8167951a85e52d129f7dd55bb6ff87220e264931f7c7174`.

The pre-change source is preserved by annotated tag `checkpoint/pre-webview-fix-2026-09-17`.
