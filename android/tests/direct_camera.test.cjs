// Runs only the bundled page with fake WebRTC, timers and bridge; no network or Android device.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/direct_camera.html'), 'utf8');
const script = html.match(/<script>([\s\S]*)<\/script>/)[1];

function fixture(options = {}) {
  const calls = {ready: 0, offers: [], states: [], stats: []};
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
    async setLocalDescription(value) { this.localDescription = value; }
    async setRemoteDescription(value) { this.remoteDescription = value; }
    addEventListener(name, callback) { this.events.set(name, callback); }
    removeEventListener(name) { this.events.delete(name); }
    async getStats() { return options.stats || new Map(); }
    close() { this.closed = true; this.connectionState = 'closed'; }
  }
  const context = vm.createContext({
    document: {getElementById: () => video},
    window: {addEventListener() {}}, performance: {now: () => time}, RTCPeerConnection: Peer,
    MediaStream: class { constructor(tracks) { this.tracks = tracks; } },
    NativeCamera: {
      ready: () => calls.ready++, offer: sdp => calls.offers.push(sdp),
      state: value => calls.states.push(value), stats: (...args) => calls.stats.push(args)
    },
    setTimeout: (fn, delay) => { timers.set(++timerId, {fn, delay, interval: false}); return timerId; },
    setInterval: (fn, delay) => { timers.set(++timerId, {fn, delay, interval: true}); return timerId; },
    clearTimeout: id => timers.delete(id), clearInterval: id => timers.delete(id)
  });
  vm.runInContext(script, context);
  return {
    calls, video, get peer() { return peer; }, api: context.window.DirectCamera,
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
