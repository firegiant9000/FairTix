// Dev-server proxy for `npm start`. Production builds don't use it.
//
// It handles /api and /auth before CRA's built-in proxy (package.json "proxy") and before
// webpack-dev-server's gzip middleware, for two reasons:
//
// 1. CRA's proxy rewrites Origin to the target and adds X-Forwarded-* headers. The backend's
//    RemoteIpValve (config/ForwardedHeadersConfig.java) trusts the Docker bridge, so it applies
//    X-Forwarded-Proto: http with port 80. Spring then sees Origin http://localhost:8080 as
//    cross-origin and not allowed, and login fails with "403 Invalid CORS request". This proxy
//    keeps the browser's Origin and sends no X-Forwarded-* headers. Open the app at
//    http://localhost:3000, the origin allowed by default; 127.0.0.1 or a LAN address is still
//    rejected unless it's added to APP_CORS_ALLOWED_ORIGINS.
// 2. webpack-dev-server gzips proxied responses, which holds back the waiting-room SSE stream.
//    Middleware registered here runs before compression, so the stream goes through as is.
//
// Keep the "proxy" field in package.json. Without it, CRA turns off the dev server's host check.
const { createProxyMiddleware } = require('http-proxy-middleware');

const DEFAULT_TARGET = 'http://localhost:8080';
const API_PREFIXES = ['/api', '/auth'];

function isApiPath(pathname) {
  return API_PREFIXES.some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`)
  );
}

function createApiProxy(target = DEFAULT_TARGET) {
  return createProxyMiddleware(isApiPath, {
    target,
    changeOrigin: true,
    xfwd: false,
    logLevel: 'warn',
  });
}

module.exports = function setupProxy(app) {
  app.use(createApiProxy());
};
module.exports.createApiProxy = createApiProxy;
module.exports.isApiPath = isApiPath;
