const API_BASE = process.env.REACT_APP_API_URL || '';

let refreshPromise = null;

async function apiRequest(path, options = {}, isRetry = false) {
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
        return apiRequest(path, options, true);
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
