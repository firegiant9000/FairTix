import api from './client';

function mockResponse(status, body) {
  const ok = status >= 200 && status < 300;
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  });
}

beforeEach(() => {
  global.fetch = jest.fn();
});

afterEach(() => {
  jest.resetAllMocks();
});

test('GET request returns parsed JSON on success', async () => {
  global.fetch.mockReturnValue(mockResponse(200, { id: 1, name: 'test' }));
  const result = await api.get('/api/resource');
  expect(result).toEqual({ id: 1, name: 'test' });
  expect(fetch).toHaveBeenCalledWith(
    '/api/resource',
    expect.objectContaining({ method: 'GET', credentials: 'include' })
  );
});

test('POST request sends JSON body with Content-Type header', async () => {
  global.fetch.mockReturnValue(mockResponse(200, { success: true }));
  await api.post('/api/resource', { key: 'value' });
  expect(fetch).toHaveBeenCalledWith(
    '/api/resource',
    expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ key: 'value' }),
      headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
    })
  );
});

test('204 response returns null without parsing body', async () => {
  global.fetch.mockReturnValue(
    Promise.resolve({ ok: true, status: 204, json: () => Promise.reject(new Error('no body')) })
  );
  const result = await api.delete('/api/resource');
  expect(result).toBeNull();
});

test('throws error with status and message on non-ok response', async () => {
  global.fetch.mockReturnValue(mockResponse(404, { message: 'Not found' }));
  await expect(api.get('/api/missing')).rejects.toMatchObject({
    status: 404,
    message: 'Not found',
  });
});

test('uses failureReason as fallback message when message is absent', async () => {
  global.fetch.mockReturnValue(mockResponse(400, { failureReason: 'Bad input' }));
  await expect(api.post('/api/action', {})).rejects.toMatchObject({
    status: 400,
    message: 'Bad input',
  });
});

test('401 on a non-auth path triggers a refresh attempt then retries the original request', async () => {
  global.fetch
    .mockReturnValueOnce(mockResponse(401, {}))          // original fails with 401
    .mockReturnValueOnce(mockResponse(200, {}))          // refresh succeeds
    .mockReturnValueOnce(mockResponse(200, { ok: true })); // retry returns data

  const result = await api.get('/api/protected');
  expect(result).toEqual({ ok: true });
  expect(fetch).toHaveBeenCalledTimes(3);
});

test('dispatches auth:session-expired when refresh also fails', async () => {
  const listener = jest.fn();
  window.addEventListener('auth:session-expired', listener);

  global.fetch
    .mockReturnValueOnce(mockResponse(401, {}))  // original fails
    .mockReturnValueOnce(mockResponse(401, {})); // refresh fails

  await expect(api.get('/api/protected')).rejects.toBeDefined();
  expect(listener).toHaveBeenCalled();

  window.removeEventListener('auth:session-expired', listener);
});

test('skips refresh and propagates error for /auth/login 401', async () => {
  global.fetch.mockReturnValue(mockResponse(401, { message: 'Bad credentials' }));
  await expect(api.post('/auth/login', {})).rejects.toMatchObject({ status: 401 });
  expect(fetch).toHaveBeenCalledTimes(1);
});

test('428 response dispatches auth:step-up-required with action from body', async () => {
  const listener = jest.fn();
  window.addEventListener('auth:step-up-required', listener);

  global.fetch.mockReturnValue(
    Promise.resolve({
      ok: false,
      status: 428,
      clone: () => ({ json: () => Promise.resolve({ action: 'CHECKOUT' }) }),
    })
  );

  await expect(api.post('/api/payments/checkout', {})).rejects.toMatchObject({
    status: 428,
    code: 'STEP_UP_REQUIRED',
  });

  await new Promise((r) => setTimeout(r, 0)); // flush microtasks
  expect(listener).toHaveBeenCalledTimes(1);
  expect(listener.mock.calls[0][0].detail).toEqual({ action: 'CHECKOUT' });

  window.removeEventListener('auth:step-up-required', listener);
});

test('428 response does not trigger token refresh loop', async () => {
  global.fetch.mockReturnValue(
    Promise.resolve({
      ok: false,
      status: 428,
      clone: () => ({ json: () => Promise.resolve({ action: 'SEAT_HOLD' }) }),
    })
  );

  await expect(api.post('/api/events/1/holds', {})).rejects.toMatchObject({ status: 428 });

  // Only the original request — no refresh call
  expect(fetch).toHaveBeenCalledTimes(1);
});

const busy = () =>
  mockResponse(409, {
    code: 'REQUEST_IN_PROGRESS',
    message: 'Another request is still being processed; try again in a moment',
  });

test('409 REQUEST_IN_PROGRESS is retried and the later success is returned', async () => {
  global.fetch
    .mockReturnValueOnce(busy())
    .mockReturnValueOnce(mockResponse(201, { id: 'hold-1' }));

  const result = await api.post('/api/events/1/holds', { seatIds: ['s1'] });

  expect(result).toEqual({ id: 'hold-1' });
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(fetch.mock.calls[1][1]).toEqual(fetch.mock.calls[0][1]);
});

test('409 REQUEST_IN_PROGRESS gives up after two retries with the server message', async () => {
  global.fetch.mockReturnValue(busy());

  await expect(api.post('/api/payments/intent', { holdIds: ['h1'] })).rejects.toMatchObject({
    status: 409,
    code: 'REQUEST_IN_PROGRESS',
    message: 'Another request is still being processed; try again in a moment',
  });
  expect(fetch).toHaveBeenCalledTimes(3);
});

test.each([
  ['checkout', '/api/payments/checkout'],
  ['queue join', '/api/events/1/queue/join'],
])('409 REQUEST_IN_PROGRESS on %s is not retried', async (_, path) => {
  global.fetch.mockReturnValue(busy());

  await expect(api.post(path, {})).rejects.toMatchObject({
    status: 409,
    code: 'REQUEST_IN_PROGRESS',
  });
  expect(fetch).toHaveBeenCalledTimes(1);
});

test('busy retries are still bounded after a 401 refresh', async () => {
  global.fetch
    .mockReturnValueOnce(busy())                  // busy, retry 1 follows
    .mockReturnValueOnce(mockResponse(401, {}))   // retry 1 hits an expired session
    .mockReturnValueOnce(mockResponse(200, {}))   // refresh succeeds
    .mockReturnValue(busy());                     // still busy from here on

  await expect(api.post('/api/events/1/holds', {})).rejects.toMatchObject({
    status: 409,
    code: 'REQUEST_IN_PROGRESS',
  });
  // original + retry 1 + refresh + replay of retry 1 + retry 2
  expect(fetch).toHaveBeenCalledTimes(5);
});

test('other 409 conflicts are not retried', async () => {
  global.fetch.mockReturnValue(mockResponse(409, { code: 'PURCHASE_CAP_EXCEEDED', message: 'cap' }));

  await expect(api.post('/api/payments/intent', {})).rejects.toMatchObject({
    status: 409,
    code: 'PURCHASE_CAP_EXCEEDED',
  });
  expect(fetch).toHaveBeenCalledTimes(1);
});

test('428 with missing action defaults to UNKNOWN', async () => {
  const listener = jest.fn();
  window.addEventListener('auth:step-up-required', listener);

  global.fetch.mockReturnValue(
    Promise.resolve({
      ok: false,
      status: 428,
      clone: () => ({ json: () => Promise.resolve({}) }),
    })
  );

  await expect(api.get('/api/protected')).rejects.toBeDefined();
  await new Promise((r) => setTimeout(r, 0));
  expect(listener.mock.calls[0][0].detail).toEqual({ action: 'UNKNOWN' });

  window.removeEventListener('auth:step-up-required', listener);
});
