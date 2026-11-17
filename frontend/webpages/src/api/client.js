const API_BASE = process.env.REACT_APP_API_URL || '';

let refreshPromise = null;

// The server answers 409 REQUEST_IN_PROGRESS when another request from the same
// user (or, for a capped queue, another join) still holds the lock it needs. The
// purchase-cap paths retried here are safe to repeat as they are. Two are not:
// - checkout: the server has already retried the order 3 times and audited the
//   failed charge, so a client retry stacks waits and can fulfil an order whose
//   audit row says "refund it";
// - queue join: the lock is shared by everyone joining the event, so retrying
//   adds load at exactly the moment it is saturated.
const BUSY_CODE = 'REQUEST_IN_PROGRESS';
const BUSY_RETRIES = 2;
const BUSY_BASE_DELAY_MS = 300;
const NO_BUSY_RETRY = [/^\/api\/payments\/checkout(?:[/?]|$)/, /\/queue\/join(?:[/?]|$)/];

const busyRetryAllowed = (path) => !NO_BUSY_RETRY.some((pattern) => pattern.test(path));

function busyRetryDelay(attempt) {
  // 300 ms then 600 ms, plus up to 200 ms of jitter so a burst does not retry in step.
  return BUSY_BASE_DELAY_MS * attempt + Math.floor(Math.random() * 200);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function apiRequest(path, options = {}, isRetry = false, busyAttempt = 0) {
  const headers = {
    ...options.headers,
  };

  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }

  const response = await fetch(API_BASE + path, {
    ...options,
    headers,
    credentials: 'include',
  });

  if (response.status === 428) {
    let action = 'UNKNOWN';
    try {
      const body = await response.clone().json();
      action = body.action || action;
    } catch (_) {}
    window.dispatchEvent(new CustomEvent('auth:step-up-required', { detail: { action } }));
    const error = new Error('Step-up verification required');
    error.status = 428;
    error.code = 'STEP_UP_REQUIRED';
    error.action = action;
    throw error;
  }

  if ((response.status === 401 || response.status === 403) && !isRetry && path !== '/auth/refresh' && path !== '/auth/login') {
    if (!refreshPromise) {
      refreshPromise = fetch(API_BASE + '/auth/refresh', {
        method: 'POST',
        credentials: 'include',
      }).finally(() => {
        refreshPromise = null;
      });
    }
    try {
      const refreshResponse = await refreshPromise;
      if (refreshResponse.ok) {
        return apiRequest(path, options, true, busyAttempt);
      }
    } catch (_) {
      // network error during refresh — fall through to logout
    }
    // Refresh failed — signal session expiry to AuthContext via a custom event
    window.dispatchEvent(new CustomEvent('auth:session-expired'));
    throw buildError(response, 'Session expired');
  }

  if (!response.ok) {
    let error;
    try {
      const body = await response.json();
      error = new Error(body.message || body.failureReason || 'Request failed');
      error.status = response.status;
      error.code = body.code;
      error.body = body;
    } catch {
      error = new Error('Request failed');
      error.status = response.status;
    }
    if (error.status === 409 && error.code === BUSY_CODE && busyAttempt < BUSY_RETRIES && busyRetryAllowed(path)) {
      await sleep(busyRetryDelay(busyAttempt + 1));
      return apiRequest(path, options, isRetry, busyAttempt + 1);
    }
    throw error;
  }

  if (response.status === 204) return null;
  return response.json();
}

function buildError(response, message) {
  const error = new Error(message);
  error.status = response.status;
  return error;
}

const withHeaders = (config) => (config && config.headers ? { headers: config.headers } : {});

const api = {
  get: (path, config) => apiRequest(path, { method: 'GET', ...withHeaders(config) }),
  post: (path, body, config) => apiRequest(path, {
    method: 'POST',
    body: body == null ? undefined : (body instanceof FormData ? body : JSON.stringify(body)),
    ...withHeaders(config),
  }),
  put: (path, body, config) => apiRequest(path, { method: 'PUT', body: JSON.stringify(body), ...withHeaders(config) }),
  patch: (path, body, config) => apiRequest(path, { method: 'PATCH', body: JSON.stringify(body), ...withHeaders(config) }),
  delete: (path, config) => apiRequest(path, { method: 'DELETE', ...withHeaders(config) }),
};

export default api;
