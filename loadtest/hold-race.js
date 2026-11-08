// Seat-holding race: many customers try to hold seats of ONE event with only 100 seats, all at the same moment.
//
// What must be true afterwards (checked by scripts/verify-no-double-booking.sql):
//   - no seat is held by two orders, and held seats == items of the pending orders.
// What this script checks itself:
//   - every answer is 201 (won) or 409 (seat taken); nothing else, in particular no 5xx.
//   - at most 100 requests won (one per seat).
//
// Run (from the repository root, app on localhost:8080):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... \
//       grafana/k6 run - < loadtest/hold-race.js
// Optional: -e CUSTOMERS=1000 (virtual users, each one a separate customer), -e SEATS_PER_ROW=50 (2 rows)

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@ticketflow.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin12345';
const CUSTOMERS = parseInt(__ENV.CUSTOMERS || '1000');
const SEATS_PER_ROW = parseInt(__ENV.SEATS_PER_ROW || '50');   // 2 rows -> 100 seats
const PASSWORD = 'load-test-password-1';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const won = new Counter('holds_won');
const lost = new Counter('holds_lost_409');
const unexpected = new Counter('unexpected_responses');

export const options = {
  // every customer is one virtual user making one attempt; all are started at once
  scenarios: {
    race: { executor: 'per-vu-iterations', vus: CUSTOMERS, iterations: 1, maxDuration: '2m' },
  },
  setupTimeout: '10m',
  thresholds: {
    unexpected_responses: ['count==0'],     // only 201 and 409 are acceptable
    holds_won: [`count<=${2 * SEATS_PER_ROW}`], // never more winners than seats
    checks: ['rate==1'],
  },
};

function post(path, body, token) {
  const headers = token ? { ...JSON_HEADERS, Authorization: `Bearer ${token}` } : JSON_HEADERS;
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), { headers });
}

function put(path, body, token) {
  return http.put(`${BASE_URL}${path}`, JSON.stringify(body),
      { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } });
}

export function setup() {
  // 1. an event with exactly 2 x SEATS_PER_ROW seats, created through the API as the administrator
  const login = post('/api/auth/login', { email: ADMIN_EMAIL, password: ADMIN_PASSWORD });
  if (login.status !== 200) fail(`admin login failed (${login.status}); set ADMIN_EMAIL and ADMIN_PASSWORD`);
  const admin = login.json('accessToken');

  const venue = post('/api/venues', {
    name: 'Load test arena', city: 'Cluj', address: 'Race street 1',
    sections: [{ name: 'Floor', rows: [{ label: '1', seats: SEATS_PER_ROW }, { label: '2', seats: SEATS_PER_ROW }] }],
  }, admin);
  if (venue.status !== 201) fail(`venue: ${venue.status} ${venue.body}`);
  const startsAt = new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString();
  const event = post('/api/events', {
    venueId: venue.json('id'), title: `Load test ${Date.now()}`, category: 'CONCERT', startsAt,
  }, admin);
  if (event.status !== 201) fail(`event: ${event.status} ${event.body}`);
  const eventId = event.json('id');
  const prices = put(`/api/events/${eventId}/prices`,
      { prices: [{ sectionId: venue.json('sections')[0].id, price: 10.0 }] }, admin);
  if (prices.status !== 200) fail(`prices: ${prices.status} ${prices.body}`);
  const publish = post(`/api/events/${eventId}/publish`, {}, admin);
  if (publish.status !== 200) fail(`publish: ${publish.status} ${publish.body}`);

  const seatMap = http.get(`${BASE_URL}/api/events/${eventId}/seats`).json();
  const seatIds = [];
  for (const section of seatMap.sections) for (const row of section.rows) for (const seat of row.seats) seatIds.push(seat.id);

  // 2. one registered, logged-in customer per virtual user (BCrypt is slow on purpose, so do it in parallel batches)
  const run = Date.now();
  const tokens = [];
  const BATCH = 25;
  for (let start = 0; start < CUSTOMERS; start += BATCH) {
    const size = Math.min(BATCH, CUSTOMERS - start);
    const emails = Array.from({ length: size }, (_, i) => `race-${run}-${start + i}@example.com`);
    http.batch(emails.map((email) => ['POST', `${BASE_URL}/api/auth/register`,
      JSON.stringify({ email, password: PASSWORD, displayName: 'Race' }), { headers: JSON_HEADERS }]));
    const logins = http.batch(emails.map((email) => ['POST', `${BASE_URL}/api/auth/login`,
      JSON.stringify({ email, password: PASSWORD }), { headers: JSON_HEADERS }]));
    for (const response of logins) {
      if (response.status !== 200) fail(`customer login failed: ${response.status}`);
      tokens.push(response.json('accessToken'));
    }
  }
  console.log(`event ${eventId} with ${seatIds.length} seats, ${tokens.length} customers ready`);
  return { eventId, seatIds, tokens };
}

export default function (data) {
  // each customer wants one random seat; with 10x more customers than seats most of them must lose
  const seat = data.seatIds[Math.floor(Math.random() * data.seatIds.length)];
  const response = post('/api/orders', { eventId: data.eventId, seatIds: [seat] }, data.tokens[__VU - 1]);

  if (response.status === 201) won.add(1);
  else if (response.status === 409) lost.add(1);
  else {
    unexpected.add(1);
    console.error(`unexpected ${response.status}: ${response.body}`);
  }
  check(response, { 'answer is 201 or 409': (r) => r.status === 201 || r.status === 409 });
}

export function teardown(data) {
  console.log(`VERIFY: psql -v event_id=${data.eventId} -f scripts/verify-no-double-booking.sql`);
}
