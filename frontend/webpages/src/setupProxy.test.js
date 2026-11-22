/**
 * @jest-environment node
 */
const http = require('http');
const { createApiProxy, isApiPath } = require('./setupProxy');

function listen(server) {
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
}

function close(server) {
  return new Promise((resolve) => server.close(resolve));
}

describe('setupProxy', () => {
  let backend;
  let devServer;
  let devPort;
  let lastBackendRequest;
  let openStreams;

  beforeEach(async () => {
    lastBackendRequest = null;
    openStreams = [];
    backend = http.createServer((req, res) => {
      lastBackendRequest = { method: req.method, url: req.url, headers: req.headers };
      if (req.url.endsWith('/queue/stream')) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream' });
        res.write('data: {"position":1}\n\n');
        openStreams.push(res);
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{}');
    });
    const backendPort = await listen(backend);

    const proxy = createApiProxy(`http://127.0.0.1:${backendPort}`);
    devServer = http.createServer((req, res) => {
      proxy(req, res, () => {
        res.writeHead(404);
        res.end('not proxied');
      });
    });
    devPort = await listen(devServer);
  });

  afterEach(async () => {
    openStreams.forEach((res) => res.end());
    backend.closeAllConnections?.();
    devServer.closeAllConnections?.();
    await close(devServer);
    await close(backend);
  });

  function request(method, path, headers = {}) {
    return new Promise((resolve, reject) => {
      const req = http.request(
        { host: '127.0.0.1', port: devPort, method, path, headers },
        resolve
      );
      req.on('error', reject);
      req.end();
    });
  }

  it("keeps the browser's Origin and adds no X-Forwarded headers", async () => {
    const res = await request('POST', '/auth/login', {
      Origin: 'http://localhost:3000',
    });
    res.resume();

    expect(res.statusCode).toBe(200);
    expect(lastBackendRequest.url).toBe('/auth/login');
    expect(lastBackendRequest.headers.origin).toBe('http://localhost:3000');
    expect(lastBackendRequest.headers['x-forwarded-proto']).toBeUndefined();
    expect(lastBackendRequest.headers['x-forwarded-port']).toBeUndefined();
    expect(lastBackendRequest.headers['x-forwarded-host']).toBeUndefined();
  });

  // Covers the proxy itself only: it neither encodes nor buffers. Skipping wds's gzip relies on
  // webpack-dev-server/lib/Server.js mounting compression (setupMiddlewares, ~2204/2499) after
  // the app.use calls that react-scripts makes for src/setupProxy.js.
  it('passes server-sent events through without encoding or buffering', async () => {
    const res = await request('GET', '/api/events/abc/queue/stream', {
      Accept: 'text/event-stream',
      'Accept-Encoding': 'gzip',
    });
    const firstChunk = await new Promise((resolve) => res.once('data', resolve));

    expect(res.headers['content-encoding']).toBeUndefined();
    expect(firstChunk.toString()).toContain('data: {"position":1}');
    res.destroy();
  });

  it('leaves non-API paths to the dev server', async () => {
    const res = await request('GET', '/events', { Accept: 'text/html' });
    res.resume();

    expect(res.statusCode).toBe(404);
    expect(lastBackendRequest).toBeNull();
  });

  it.each([
    ['/api', true],
    ['/api/events', true],
    ['/auth/me', true],
    ['/authors', false],
    ['/apiary', false],
    ['/', false],
    ['/static/js/bundle.js', false],
  ])('isApiPath(%s) is %s', (path, expected) => {
    expect(isApiPath(path)).toBe(expected);
  });
});
