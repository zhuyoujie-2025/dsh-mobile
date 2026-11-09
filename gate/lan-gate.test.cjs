'use strict';
// Integration tests for the gateway auth fixes. The server boots itself on import,
// so each case spawns a fresh process against a tiny upstream.
const {describe, test} = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const net = require('node:net');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawn} = require('node:child_process');

const SERVER = path.join(__dirname, 'lan-gate-server.cjs');
const PASSWORD = 's3cret-pass';

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.once('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

async function waitForPort(port) {
  const start = Date.now();
  for (;;) {
    try {
      await new Promise((resolve, reject) => {
        const s = net.connect(port, '127.0.0.1', () => { s.end(); resolve(); });
        s.once('error', reject);
      });
      return;
    } catch (e) {
      if (Date.now() - start > 5000) throw new Error('gate did not listen on ' + port);
      await sleep(40);
    }
  }
}

function startUpstream() {
  return new Promise((resolve) => {
    const hits = [];
    let upgrades = 0;
    const up = http.createServer((req, res) => {
      hits.push(req.url);
      res.writeHead(200, {'content-type': 'text/plain; charset=utf-8'});
      res.end('upstream ' + req.url);
    });
    up.on('upgrade', (req, socket) => {
      upgrades += 1;
      hits.push('UPGRADE ' + req.url);
      socket.write('HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n');
    });
    up.listen(0, '127.0.0.1', () => resolve({
      up, hits,
      port: up.address().port,
      upgrades: () => upgrades,
    }));
  });
}

async function startGate(extra) {
  const upstream = await startUpstream();
  const port = await freePort();
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'lan-gate-'));
  const env = Object.assign({}, process.env, {
    LAN_GATE_PORT: String(port),
    LAN_GATE_HOST: '127.0.0.1',
    LAN_GATE_TARGET_PORT: String(upstream.port),
    LAN_GATE_DISCOVERY: '0',
    LAN_GATE_AUTO: 'off',
    LAN_GATE_PASSWORD: PASSWORD,
    DSH_HOME: home,
  }, extra || {});
  delete env.LAN_GATE_EDGE;
  if (extra && Object.prototype.hasOwnProperty.call(extra, 'LAN_GATE_EDGE')) env.LAN_GATE_EDGE = extra.LAN_GATE_EDGE;
  const child = spawn(process.execPath, [SERVER], {env, stdio: ['ignore', 'pipe', 'pipe']});
  let logs = '';
  child.stdout.on('data', (d) => { logs += d; });
  child.stderr.on('data', (d) => { logs += d; });
  try { await waitForPort(port); }
  catch (e) {
    try { child.kill('SIGKILL'); } catch (err) {}
    upstream.up.close();
    fs.rmSync(home, {recursive: true, force: true});
    throw new Error(e.message + '\n' + logs);
  }
  return {port, child, home, upstream, logs: () => logs};
}

async function stopGate(g) {
  if (!g) return;
  if (g.child && g.child.exitCode == null) {
    g.child.kill('SIGTERM');
    await Promise.race([
      new Promise((r) => g.child.once('exit', r)),
      sleep(800).then(() => { try { g.child.kill('SIGKILL'); } catch (e) {} }),
    ]);
  }
  if (g.upstream) await new Promise((r) => g.upstream.up.close(() => r()));
  if (g.home) fs.rmSync(g.home, {recursive: true, force: true});
}

function req(port, opts) {
  opts = opts || {};
  return new Promise((resolve, reject) => {
    const r = http.request({
      host: '127.0.0.1',
      port,
      method: opts.method || 'GET',
      path: opts.path || '/',
      headers: opts.headers || {},
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({
        status: res.statusCode,
        headers: res.headers,
        body: Buffer.concat(chunks).toString('utf8'),
      }));
    });
    r.once('error', reject);
    if (opts.body) r.write(opts.body);
    r.end();
  });
}

function tunnelHeaders(ip, proto) {
  const h = {
    'cf-connecting-ip': ip || '203.0.113.5',
    'cf-ray': 'test-ray',
  };
  if (proto) h['x-forwarded-proto'] = proto;
  return h;
}

function cookiePair(res) {
  const raw = res.headers['set-cookie'];
  const list = raw == null ? [] : (Array.isArray(raw) ? raw : [raw]);
  const line = list.find((c) => c.indexOf('lg_token=') === 0) || '';
  const m = /^lg_token=([^;]*)/.exec(line);
  return {line, token: m ? m[1] : ''};
}

describe('lan-gate auth', {concurrency: 1}, () => {
  test('loopback without proxy headers still reaches upstream (owner / this machine)', async () => {
    const g = await startGate();
    try {
      const res = await req(g.port, {path: '/'});
      assert.equal(res.status, 200);
      assert.equal(res.body, 'upstream /');
      const admin = await req(g.port, {path: '/lan-gate/admin'});
      assert.equal(admin.status, 200);
      assert.match(admin.body, /内网访问网关/);
    } finally { await stopGate(g); }
  });

  test('proxied tunnel traffic is not treated as local when LAN_GATE_EDGE is unset', async () => {
    const g = await startGate({LAN_GATE_AUTO: 'first', LAN_GATE_PASSWORD: PASSWORD});
    try {
      const res = await req(g.port, {path: '/?pw=' + encodeURIComponent(PASSWORD), headers: tunnelHeaders('203.0.113.8', 'https')});
      assert.equal(res.status, 200);
      assert.match(res.body, /输入访问密码/);
      assert.equal(res.body.includes('upstream'), false);
      assert.equal(g.upstream.hits.length, 0);
      const forwardedOnly = await req(g.port, {path: '/', headers: {'x-forwarded-for': '203.0.113.50', 'x-forwarded-proto': 'https'}});
      assert.match(forwardedOnly.body, /输入访问密码/);
      assert.equal(forwardedOnly.body.includes('upstream'), false);
      const admin = await req(g.port, {path: '/lan-gate/admin', headers: tunnelHeaders('203.0.113.8', 'https')});
      assert.equal(admin.status, 404);
      const status = await req(g.port, {path: '/lan-gate/status', headers: tunnelHeaders('203.0.113.8', 'https')});
      assert.equal(status.status, 404);
    } finally { await stopGate(g); }
  });

  test('proxied clients are not auto-paired even when LAN_GATE_AUTO=first and no password', async () => {
    const g = await startGate({LAN_GATE_AUTO: 'first', LAN_GATE_PASSWORD: ''});
    try {
      const a = await req(g.port, {path: '/', headers: tunnelHeaders('203.0.113.10', 'https')});
      const b = await req(g.port, {path: '/', headers: tunnelHeaders('203.0.113.11', 'https')});
      assert.match(a.body, /正在等待本机批准/);
      assert.match(b.body, /正在等待本机批准/);
      assert.equal(g.upstream.hits.length, 0);
      // The machine itself is still trusted. This must not have consumed a pairing slot.
      const local = await req(g.port, {path: '/'});
      assert.equal(local.body, 'upstream /');
    } finally { await stopGate(g); }
  });

  test('POST /lan-gate/login sets HttpOnly, SameSite=Lax, and Secure on https tunnels', async () => {
    const g = await startGate();
    try {
      const bad = await req(g.port, {
        method: 'POST',
        path: '/lan-gate/login?pw=' + encodeURIComponent(PASSWORD),
        headers: Object.assign({'content-type': 'application/x-www-form-urlencoded'}, tunnelHeaders('203.0.113.5', 'https')),
        body: '',
      });
      assert.equal(bad.status, 200);
      assert.match(bad.body, /密码错误/);
      assert.equal(cookiePair(bad).token, '');

      const ok = await req(g.port, {
        method: 'POST',
        path: '/lan-gate/login',
        headers: Object.assign({'content-type': 'application/x-www-form-urlencoded'}, tunnelHeaders('203.0.113.5', 'https')),
        body: 'pw=' + encodeURIComponent(PASSWORD) + '&kind=phone',
      });
      assert.equal(ok.status, 302);
      assert.equal(ok.headers.location, '/');
      const c = cookiePair(ok);
      assert.ok(c.token.length >= 16, 'expected session token');
      assert.match(c.line, /HttpOnly/);
      assert.match(c.line, /SameSite=Lax/);
      assert.match(c.line, /Secure/);
      assert.equal(/pw=/.test(c.line), false);

      const authed = await req(g.port, {
        path: '/?pw=' + encodeURIComponent(PASSWORD) + '&x=1',
        headers: Object.assign({cookie: 'lg_token=' + c.token}, tunnelHeaders('203.0.113.5', 'https')),
      });
      assert.equal(authed.status, 200);
      assert.equal(authed.body, 'upstream /?x=1');

      const other = await req(g.port, {
        path: '/',
        headers: Object.assign({cookie: 'lg_token=' + c.token}, tunnelHeaders('198.51.100.7', 'https')),
      });
      assert.match(other.body, /输入访问密码/);
      assert.equal(other.status, 200);

      const noCookie = await req(g.port, {path: '/', headers: tunnelHeaders('203.0.113.5', 'https')});
      assert.equal(noCookie.status, 200);
      assert.match(noCookie.body, /输入访问密码/);
      assert.equal(noCookie.headers.location, undefined);
    } finally { await stopGate(g); }
  });

  test('Secure is omitted for an explicit http scheme and set when only cf-ray is present', async () => {
    const g = await startGate();
    try {
      const httpEdge = await req(g.port, {
        method: 'POST',
        path: '/lan-gate/login',
        headers: Object.assign({'content-type': 'application/x-www-form-urlencoded'}, tunnelHeaders('203.0.113.20', 'http')),
        body: 'pw=' + encodeURIComponent(PASSWORD) + '&kind=phone',
      });
      const httpCookie = cookiePair(httpEdge);
      assert.match(httpCookie.line, /HttpOnly/);
      assert.match(httpCookie.line, /SameSite=Lax/);
      assert.equal(/Secure/.test(httpCookie.line), false);

      const rayOnly = await req(g.port, {
        method: 'POST',
        path: '/lan-gate/login',
        headers: Object.assign({
          'content-type': 'application/x-www-form-urlencoded',
          'cf-connecting-ip': '203.0.113.21',
          'cf-ray': 'only-ray',
        }),
        body: 'pw=' + encodeURIComponent(PASSWORD) + '&kind=phone',
      });
      assert.match(cookiePair(rayOnly).line, /Secure/);
    } finally { await stopGate(g); }
  });

  test('LAN_GATE_EDGE=1 still requires the login page for headerless loopback, admin stays local', async () => {
    const g = await startGate({LAN_GATE_EDGE: '1'});
    try {
      const res = await req(g.port, {path: '/'});
      assert.match(res.body, /输入访问密码/);
      assert.equal(g.upstream.hits.length, 0);
      const admin = await req(g.port, {path: '/lan-gate/admin'});
      assert.equal(admin.status, 200);
      const hidden = await req(g.port, {path: '/lan-gate/admin', headers: tunnelHeaders('203.0.113.30', 'https')});
      assert.equal(hidden.status, 404);
    } finally { await stopGate(g); }
  });

  test('websocket upgrade is refused for proxied clients without a session cookie', async () => {
    const g = await startGate();
    try {
      const status = await new Promise((resolve, reject) => {
        const s = net.connect(g.port, '127.0.0.1', () => {
          s.write('GET /socket?pw=' + PASSWORD + ' HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: Upgrade\r\nUpgrade: websocket\r\ncf-connecting-ip: 203.0.113.40\r\ncf-ray: ws\r\nx-forwarded-proto: https\r\n\r\n');
        });
        let buf = '';
        s.on('data', (d) => { buf += d; if (buf.indexOf('\r\n') >= 0) { s.end(); resolve(buf.split('\r\n')[0]); } });
        s.on('error', reject);
        setTimeout(() => resolve(buf.split('\r\n')[0] || ''), 1000);
      });
      assert.equal(status, 'HTTP/1.1 403 Forbidden');
      assert.equal(g.upstream.upgrades(), 0);
    } finally { await stopGate(g); }
  });
});
