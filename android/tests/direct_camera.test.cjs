// Runs only the bundled page with fake WebRTC, timers and bridge; no network or Android device.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/direct_camera.html'), 'utf8');
const script = html.match(/<script>([\s\S]*)<\/script>/)[1];

function fixture(options = {}) {
  const calls = {ready: 0, offers: [], states: [], stats: [], diagnostics: [], iceCounts: []};
  const timers = new Map();
  let timerId = 0, time = 0, peer;
  const video = {srcObject: null, play: async () => {}, pause() { this.paused = true; }};
  class Peer {
    constructor(config) {
      peer = this; this.config = config; this.iceGatheringState = options.gathering || 'complete';
      this.connectionState = 'new'; this.events = new Map(); this.transceivers = [];
    }
    addTransceiver(kind, init) { this.transceivers.push({kind, init}); }
    createOffer() { return options.offerPromise || Promise.resolve({type: 'offer', sdp: 'v=0\r\n'}); }
    async setLocalDescription(value) { if (options.localError) throw new Error(options.localError); this.localDescription = value; }
    async setRemoteDescription(value) { if (options.remoteError) throw new Error(options.remoteError); this.remoteDescription = value; }
    addEventListener(name, callback) { this.events.set(name, callback); }
    removeEventListener(name) { this.events.delete(name); }
    async getStats() { return options.getStats ? options.getStats() : options.stats || new Map(); }
    close() { this.closed = true; this.connectionState = 'closed'; }
  }
  const context = vm.createContext({
    document: {getElementById: () => video},
    window: {addEventListener() {}, isSecureContext: true}, performance: {now: () => time}, RTCPeerConnection: options.unsupported ? undefined : Peer,
    MediaStream: class { constructor(tracks) { this.tracks = tracks; } },
    NativeCamera: {
      ready: () => calls.ready++, offer: sdp => calls.offers.push(sdp),
      state: value => calls.states.push(value), stats: (...args) => calls.stats.push(args),
      diagnostic: value => calls.diagnostics.push(value),
      iceCandidates: (...args) => calls.iceCounts.push(args)
    },
    setTimeout: (fn, delay) => { timers.set(++timerId, {fn, delay, interval: false}); return timerId; },
    setInterval: (fn, delay) => { timers.set(++timerId, {fn, delay, interval: true}); return timerId; },
    clearTimeout: id => timers.delete(id), clearInterval: id => timers.delete(id)
  });
  vm.runInContext(script, context);
  return {
    calls, video, get peer() { return peer; }, get timerCount() { return timers.size; }, api: context.window.DirectCamera,
    async timer(delay, elapsed = delay) {
      time += elapsed;
      const match = [...timers].find(([, timer]) => timer.delay === delay);
      assert.ok(match, `timer ${delay} must exist`);
      if (!match[1].interval) timers.delete(match[0]);
      await match[1].fn();
    }
  };
}

test('page negotiates recvonly video with STUN and no TURN or HTTP fallback', async () => {
  const f = fixture();
  assert.equal(f.calls.ready, 1);
  await f.api.start();
  assert.equal(f.peer.config.iceServers.length, 1);
  assert.equal(f.peer.config.iceServers[0].urls, 'stun:stun.l.google.com:19302');
  assert.equal(f.peer.transceivers[0].kind, 'video');
  assert.equal(f.peer.transceivers[0].init.direction, 'recvonly');
  assert.equal(f.calls.offers.length, 1);
  assert.doesNotMatch(script, /fetch\(|XMLHttpRequest|\/video_feed|\/ws\/viewer|turn:/);
});

test('native answer becomes only an answer SDP; video attaches a receive track', async () => {
  const f = fixture(); await f.api.start();
  await f.api.acceptAnswer('v=0\r\ns=answer\r\n');
  assert.equal(f.peer.remoteDescription.type, 'answer');
  assert.equal(f.peer.remoteDescription.sdp, 'v=0\r\ns=answer\r\n');
  f.peer.ontrack({streams: [], track: {kind: 'video'}});
  assert.equal(f.video.srcObject.tracks[0].kind, 'video');
  f.video.onplaying();
  assert.deepEqual(f.calls.states, ['playing']);
});

test('incomplete ICE gathering fails instead of sending partial SDP', async () => {
  const f = fixture({gathering: 'gathering'});
  const starting = f.api.start();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.calls.offers.length, 0);
  await f.timer(10000);
  await starting;
  assert.deepEqual(f.calls.states, ['failed']);
  assert.equal(f.calls.offers.length, 0);
  assert.equal(f.peer.closed, true);
  assert.ok(f.calls.diagnostics.includes('ice_gather_timeout'));
  assert.deepEqual(f.calls.iceCounts, [[false, 0, 0, 0]]);
});

test('stop while offer is pending prevents late bridge and media activity', async () => {
  let resolveOffer;
  const f = fixture({offerPromise: new Promise(resolve => { resolveOffer = resolve; })});
  const starting = f.api.start();
  f.api.close(); resolveOffer({type: 'offer', sdp: 'v=0\r\n'});
  await starting;
  assert.equal(f.calls.offers.length, 0);
  assert.equal(f.peer.closed, true);
  assert.equal(f.video.srcObject, null);
  assert.equal(f.video.paused, true);
});

function stats(type) {
  return new Map([
    ['transport', {type: 'transport', selectedCandidatePairId: 'pair'}],
    ['pair', {type: 'candidate-pair', localCandidateId: 'local', remoteCandidateId: 'remote'}],
    ['local', {candidateType: type, address: 'must-not-leave-page'}],
    ['remote', {candidateType: 'srflx', address: 'must-not-leave-page'}],
    ['inbound', {type: 'inbound-rtp', kind: 'video', bytesReceived: 1200, framesDecoded: 8}]
  ]);
}
test('diagnostics contain candidate types and counters without addresses', async () => {
  const f = fixture({stats: stats('host')}); await f.api.start();
  await f.timer(5000);
  assert.deepEqual(f.calls.stats, [['host', 'srflx', 1200, 8]]);
});

test('a relay candidate is rejected without enabling any fallback', async () => {
  const f = fixture({stats: stats('relay')}); await f.api.start();
  await f.timer(5000);
  assert.deepEqual(f.calls.states, ['failed']);
  assert.equal(f.calls.stats.length, 0);
  assert.equal(f.peer.closed, true);
});

test('connection failure closes the peer and does not negotiate again', async () => {
  const f = fixture(); await f.api.start();
  f.peer.connectionState = 'failed'; f.peer.onconnectionstatechange();
  await f.api.start();
  assert.deepEqual(f.calls.states, ['failed']);
  assert.equal(f.calls.offers.length, 1);
  assert.equal(f.peer.closed, true);
});

test('unsupported runtime reports a fixed reason before any offer', async () => {
  const f = fixture({unsupported: true}); await f.api.start();
  assert.ok(f.calls.diagnostics.includes('unsupported_rtc'));
  assert.deepEqual(f.calls.states, ['failed']);
  assert.equal(f.calls.offers.length, 0);
});

test('local and remote description errors never expose exception text', async () => {
  const privateDetail = 'sensitive exception text';
  const local = fixture({localError: privateDetail}); await local.api.start();
  assert.ok(local.calls.diagnostics.includes('local_set_failed'));
  assert.equal(local.calls.offers.length, 0);
  const remote = fixture({remoteError: privateDetail}); await remote.api.start();
  await remote.api.acceptAnswer('v=0\r\n');
  assert.ok(remote.calls.diagnostics.includes('remote_set_failed'));
  assert.equal(JSON.stringify([local.calls, remote.calls]).includes(privateDetail), false);
});

test('offer rejection reports only the fixed create stage', async () => {
  const f = fixture({offerPromise: Promise.reject(new Error('private offer details'))});
  await f.api.start();
  assert.ok(f.calls.diagnostics.includes('offer_create_failed'));
  assert.equal(JSON.stringify(f.calls).includes('private offer details'), false);
  assert.equal(f.calls.offers.length, 0);
});

test('gathering diagnostics emit counts without candidate addresses or SDP', async () => {
  const f = fixture({offerPromise: Promise.resolve({type:'offer', sdp:'v=0\r\na=candidate:1 1 udp 123 private-host 1000 typ host\r\na=candidate:2 1 udp 456 private-public 2000 typ srflx\r\n'})});
  await f.api.start();
  assert.deepEqual(f.calls.iceCounts, [[true, 1, 1, 0]]);
  assert.equal(JSON.stringify(f.calls.iceCounts).includes('private'), false);
});

test('growing received bytes cannot hide a decoder frozen for ten seconds', async () => {
  const report = stats('srflx');
  const inbound = report.get('inbound');
  const f = fixture({stats: report}); await f.api.start();
  f.peer.connectionState = 'connected'; f.video.onplaying();
  await f.timer(5000);
  inbound.bytesReceived += 100000; await f.timer(5000);
  assert.deepEqual(f.calls.states, ['playing']);
  inbound.bytesReceived += 100000; await f.timer(5000);
  assert.deepEqual(f.calls.states, ['playing', 'failed']);
  assert.ok(f.calls.diagnostics.includes('stalled'));
  assert.equal(f.peer.closed, true);
  assert.equal(f.video.srcObject, null);
  assert.equal(f.timerCount, 0);
  await f.api.start();
  assert.equal(f.calls.offers.length, 1, 'recovery remains a native manual retry');
});

test('healthy twelve-fps decoded frames keep the viewer active', async () => {
  const report = stats('srflx');
  const inbound = report.get('inbound');
  const f = fixture({stats: report}); await f.api.start();
  f.peer.connectionState = 'connected'; f.video.onplaying();
  for (let i = 0; i < 12; i++) {
    inbound.bytesReceived += 100000; inbound.framesDecoded += 60;
    await f.timer(5000);
  }
  assert.deepEqual(f.calls.states, ['playing']);
  assert.equal(f.calls.diagnostics.includes('stalled'), false);
  assert.equal(f.peer.closed, undefined);
});

test('no received bytes or decoded frames still terminates the stalled stream', async () => {
  const f = fixture({stats: stats('srflx')}); await f.api.start();
  f.peer.connectionState = 'connected';
  await f.timer(5000); await f.timer(5000); await f.timer(5000);
  assert.deepEqual(f.calls.states, ['failed']);
  assert.ok(f.calls.diagnostics.includes('stalled'));
  assert.equal(f.peer.closed, true);
});

test('a long signaling wait does not consume the connected decode grace period', async () => {
  const report = stats('srflx');
  report.get('inbound').bytesReceived = 0; report.get('inbound').framesDecoded = 0;
  const f = fixture({stats: report}); await f.api.start();
  await f.timer(5000, 180000);
  f.peer.connectionState = 'connected';
  await f.timer(5000);
  assert.deepEqual(f.calls.states, []);
  report.get('inbound').framesDecoded = 1;
  await f.timer(5000);
  assert.deepEqual(f.calls.states, []);
  assert.equal(f.calls.diagnostics.includes('stalled'), false);
});

test('shutdown cancels decode monitoring and ignores a late stats result', async () => {
  let resolveStats;
  const f = fixture({getStats: () => new Promise(resolve => { resolveStats = resolve; })});
  await f.api.start(); f.peer.connectionState = 'connected';
  const pending = f.timer(5000);
  f.api.close();
  resolveStats(stats('srflx')); await pending;
  assert.equal(f.timerCount, 0);
  assert.equal(f.calls.stats.length, 0);
  assert.deepEqual(f.calls.states, []);
  assert.equal(f.peer.closed, true);
  assert.equal(f.video.paused, true);
  assert.equal(f.video.srcObject, null);
});
