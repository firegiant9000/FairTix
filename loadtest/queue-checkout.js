// FairTix on-sale load test: every VU is one user who logs in, joins the
// waiting room of one queued event, waits to be admitted, holds seats, confirms
// the holds and pays through the dev payment simulation. All VUs start at once,
// like an on-sale. See loadtest/README.md and docs/experiments/l2-load-queue-checkout.md.
//
//   k6 run -e EVENT_ID=<uuid> -e USERS=500 queue-checkout.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EVENT_ID = __ENV.EVENT_ID;
const USERS = parseInt(__ENV.USERS || '500', 10);
// Seats each user tries to buy; keep at or below the event's per-user cap.
const SEATS_PER_USER = parseInt(__ENV.SEATS_PER_USER || '2', 10);
// How often a waiting user re-reads its queue status, and when it gives up.
const POLL_S = parseFloat(__ENV.POLL_S || '2');
const MAX_WAIT_S = parseInt(__ENV.MAX_WAIT_S || '300', 10);
const HOLD_ATTEMPTS = 3;

// Must match loadtest/seed.sh.
const PASSWORD = 'LoadTest#2026!';
const EMAIL_DOMAIN = 'load.fairtix.test';

// Every POST that carries the auth cookie needs an allowed Origin (OriginCheckFilter).
const HEADERS = { 'Content-Type': 'application/json', Origin: 'http://localhost:3000' };

if (!EVENT_ID) {
  throw new Error('EVENT_ID is required (printed by loadtest/seed.sh)');
}

export const options = {
  scenarios: {
    onsale: {
      executor: 'per-vu-iterations',
      vus: USERS,
      iterations: 1,
      maxDuration: `${MAX_WAIT_S + 300}s`,
    },
  },
  summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
  thresholds: {
    // Any 5xx is a failure of the run, whatever else happened.
    server_errors: ['count==0'],
    unexpected_responses: ['count==0'],
    // Every checkout that returned 201 produced a COMPLETED order.
    checks: ['rate==1'],
    // Every user finished; one cut off by maxDuration has no outcome counter.
    iterations: [`count==${USERS}`],
  },
};

const loginMs = new Trend('step_login_ms', true);
const joinMs = new Trend('step_queue_join_ms', true);
const admissionWaitMs = new Trend('step_admission_wait_ms', true);
const holdMs = new Trend('step_hold_ms', true);
const confirmMs = new Trend('step_confirm_ms', true);
const checkoutMs = new Trend('step_checkout_ms', true);
const journeyMs = new Trend('journey_to_order_ms', true);

const ordersCompleted = new Counter('orders_completed');
const ticketsBought = new Counter('tickets_bought');
const holdConflicts = new Counter('hold_conflicts');
const soldOut = new Counter('sold_out');
const holdGaveUp = new Counter('hold_gave_up');
const notAdmitted = new Counter('not_admitted');
const serverErrors = new Counter('server_errors');
const unexpected = new Counter('unexpected_responses');

// Expected statuses are not counted as http_req_failed; 409 is a normal answer
// to a seat someone else took first.
const OK = http.expectedStatuses(200, 201, 204);
const OK_OR_CONFLICT = http.expectedStatuses(200, 201, 204, 409);

function record(res, step, expected) {
  if (res.status >= 500 || res.status === 0) {
    serverErrors.add(1, { step });
    console.error(`${step}: ${res.status} ${res.error || ''} ${String(res.body).slice(0, 200)}`);
    return false;
  }
  if (!expected.includes(res.status)) {
    unexpected.add(1, { step, status: String(res.status) });
    console.warn(`${step}: unexpected ${res.status} ${String(res.body).slice(0, 200)}`);
    return false;
  }
  return true;
}

function post(path, body, tag, callback) {
  return http.post(`${BASE_URL}${path}`, body === null ? null : JSON.stringify(body), {
    headers: HEADERS,
    tags: { name: tag },
    responseCallback: callback || OK,
  });
}

function shuffle(a) {
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

function leaveQueue() {
  http.del(`${BASE_URL}/api/events/${EVENT_ID}/queue/leave`, null, {
    headers: HEADERS,
    tags: { name: 'queue_leave' },
    responseCallback: http.expectedStatuses(204, 404, 409),
  });
}

export default function () {
  const email = `load${__VU}@${EMAIL_DOMAIN}`;
  const start = Date.now();

  // 1. Log in. The JWT comes back as an HttpOnly cookie in the VU's jar.
  let res = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ email, password: PASSWORD }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'login' },
  });
  loginMs.add(res.timings.duration);
  if (!record(res, 'login', [200])) return;

  // 2. Join the waiting room.
  res = post(`/api/events/${EVENT_ID}/queue/join`, null, 'queue_join');
  joinMs.add(res.timings.duration);
  if (!record(res, 'queue_join', [201])) return;
  const joinedAt = Date.now();

  // 3. Wait for admission. The browser listens on the SSE stream; stock k6 has
  //    no SSE client, so this polls /queue/status, which returns the same payload.
  let status = res.json('status');
  while (status === 'WAITING' && Date.now() - joinedAt < MAX_WAIT_S * 1000) {
    sleep(POLL_S);
    res = http.get(`${BASE_URL}/api/events/${EVENT_ID}/queue/status`, {
      tags: { name: 'queue_status' },
    });
    if (!record(res, 'queue_status', [200])) return;
    status = res.json('status');
  }
  if (status !== 'ADMITTED') {
    notAdmitted.add(1, { status: String(status) });
    leaveQueue();
    return;
  }
  admissionWaitMs.add(Date.now() - joinedAt);

  // 4. Hold seats, re-reading availability after each 409. A 409 is usually a
  //    seat someone else took first, but the backend also answers 409 for a
  //    lapsed admission, the per-user cap and the active-hold limit.
  let holds = null;
  let empty = false;
  for (let attempt = 0; attempt < HOLD_ATTEMPTS && holds === null; attempt++) {
    res = http.get(`${BASE_URL}/api/events/${EVENT_ID}/seats?availableOnly=true`, {
      tags: { name: 'seats_available' },
    });
    if (!record(res, 'seats_available', [200])) return;
    const available = res.json();
    if (available.length === 0) {
      empty = true;
      break;
    }
    const pick = shuffle(available.map((s) => s.id)).slice(0, SEATS_PER_USER);

    res = post(`/api/events/${EVENT_ID}/holds`, { seatIds: pick }, 'hold', OK_OR_CONFLICT);
    holdMs.add(res.timings.duration);
    if (res.status === 409) {
      holdConflicts.add(1);
      continue;
    }
    if (!record(res, 'hold', [201])) return;
    holds = res.json();
  }
  if (holds === null) {
    if (empty) {
      soldOut.add(1);
    } else {
      holdGaveUp.add(1);
      console.warn(`hold: gave up after ${HOLD_ATTEMPTS} conflicts: ${String(res.body).slice(0, 200)}`);
    }
    leaveQueue();
    return;
  }

  // 5. Confirm every hold (seat becomes BOOKED), then pay.
  for (const h of holds) {
    res = post(`/api/holds/${h.id}/confirm`, null, 'hold_confirm');
    confirmMs.add(res.timings.duration);
    if (!record(res, 'hold_confirm', [200])) return;
  }

  res = post(
    '/api/payments/checkout',
    { holdIds: holds.map((h) => h.id), simulatedOutcome: 'SUCCESS' },
    'checkout',
  );
  checkoutMs.add(res.timings.duration);
  if (!record(res, 'checkout', [201])) return;
  const ok = check(res, {
    'order completed': (r) => r.json('orderStatus') === 'COMPLETED',
  });
  if (ok) {
    ordersCompleted.add(1);
    ticketsBought.add(holds.length);
    journeyMs.add(Date.now() - start);
  }
}
