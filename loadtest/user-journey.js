// k6 load test: realistic user journeys through api-gateway (port 8080).
//
// Journeys (weighted by realistic traffic mix):
//   - anonymous_browse (50%): browse catalog + keyword search + vector search — no auth
//   - registered_journey (50%): register once, then repeatedly login -> browse -> review -> get recommendations
//
// Usage:
//   k6 run loadtest/user-journey.js
//   k6 run --vus 50 --duration 3m loadtest/user-journey.js
//   k6 run -e BASE_URL=http://localhost:8080 loadtest/user-journey.js
//
// Results are also written to loadtest/results/summary.json (see handleSummary below).

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { randomIntBetween, randomItem } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Real ObjectIds pulled from the live catalog (sample_mflix), used as fixed test data
// so we don't depend on list-endpoint ordering to find something to fetch.
const MOVIE_IDS = [
  '573a1396f29313caabce48c4',
  '573a13b8f29313caabd4bd33',
  '573a1396f29313caabce4485',
  '573a1393f29313caabcdc5dc',
  '573a13a1f29313caabd07b8a',
  '573a1398f29313caabce9d48',
  '573a13e7f29313caabdc74b6',
  '573a13a6f29313caabd16e4d',
  '573a1398f29313caabce9d51',
  '573a13bdf29313caabd59ad0',
];

const SEARCH_TERMS = ['heist', 'space', 'love', 'war', 'detective', 'monster', 'family', 'crime'];
const VECTOR_QUERIES = [
  'a heist where a crew steals from a casino',
  'an astronaut stranded in space',
  'a detective solving a murder in a big city',
  'a family drama set during wartime',
  'a monster terrorizing a small town',
];

// Custom metrics per journey step, so p95/p99 latency is visible per endpoint, not just overall.
const catalogListTrend = new Trend('catalog_list_duration', true);
const catalogGetTrend = new Trend('catalog_get_duration', true);
const keywordSearchTrend = new Trend('keyword_search_duration', true);
const vectorSearchTrend = new Trend('vector_search_duration', true);
const registerTrend = new Trend('register_duration', true);
const loginTrend = new Trend('login_duration', true);
const reviewTrend = new Trend('review_duration', true);
const recommendationTrend = new Trend('recommendation_duration', true);

const errorRate = new Rate('errors');

// SMOKE=1 runs a tiny 2-VU/1-iteration pass of both journeys — for validating the script
// itself works end-to-end before committing to the full ramping-VU load profile below.
const SMOKE = __ENV.SMOKE === '1';

// Overridable peak VU count per scenario. Default (15) is what this host's Docker VM budget
// was originally sized for; a 15+15 run on 2026-09-15 caused host-level (not container-level)
// memory exhaustion — physical RAM dropped to <50MB free, swap became active, load average hit
// 100+ on a 4-core host, and the *client* thrashing produced request latencies up to 30 minutes.
// That invalidates the run as an application-performance signal. See docs/adr/0010 Update 4.
// STRESS_VUS lets a retry target actual current headroom instead of assuming the old budget.
const STRESS_VUS = __ENV.STRESS_VUS ? parseInt(__ENV.STRESS_VUS, 10) : 15;

export const options = {
  scenarios: SMOKE
    ? {
        smoke_anonymous: { executor: 'per-vu-iterations', exec: 'anonymousBrowse', vus: 2, iterations: 1, maxDuration: '30s' },
        smoke_registered: { executor: 'per-vu-iterations', exec: 'registeredJourney', vus: 2, iterations: 1, maxDuration: '30s' },
      }
    : {
        anonymous_browse: {
          executor: 'ramping-vus',
          exec: 'anonymousBrowse',
          startVUs: 0,
          stages: [
            { duration: '30s', target: STRESS_VUS },
            { duration: '2m', target: STRESS_VUS },
            { duration: '30s', target: 0 },
          ],
          gracefulRampDown: '10s',
        },
        registered_journey: {
          executor: 'ramping-vus',
          exec: 'registeredJourney',
          startVUs: 0,
          stages: [
            { duration: '30s', target: STRESS_VUS },
            { duration: '2m', target: STRESS_VUS },
            { duration: '30s', target: 0 },
          ],
          gracefulRampDown: '10s',
        },
      },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<1500', 'p(99)<3000'],
    errors: ['rate<0.02'],
  },
};

function checkAndRecord(res, trend, label) {
  trend.add(res.timings.duration);
  const ok = check(res, { [`${label} status is 2xx`]: (r) => r.status >= 200 && r.status < 300 });
  errorRate.add(!ok);
  return ok;
}

export function anonymousBrowse() {
  group('anonymous_browse', () => {
    const listRes = http.get(`${BASE_URL}/api/movies?limit=20`, { tags: { name: 'catalog_list' } });
    checkAndRecord(listRes, catalogListTrend, 'catalog_list');

    const movieId = randomItem(MOVIE_IDS);
    const getRes = http.get(`${BASE_URL}/api/movies/${movieId}`, { tags: { name: 'catalog_get' } });
    checkAndRecord(getRes, catalogGetTrend, 'catalog_get');

    const term = randomItem(SEARCH_TERMS);
    const searchRes = http.get(`${BASE_URL}/api/movies/search?q=${term}&limit=10`, {
      tags: { name: 'keyword_search' },
    });
    checkAndRecord(searchRes, keywordSearchTrend, 'keyword_search');

    const vq = encodeURIComponent(randomItem(VECTOR_QUERIES));
    const vectorRes = http.get(`${BASE_URL}/api/movies/search/vector?q=${vq}&limit=10`, {
      tags: { name: 'vector_search' },
    });
    checkAndRecord(vectorRes, vectorSearchTrend, 'vector_search');
  });

  sleep(randomIntBetween(1, 3));
}

export function registeredJourney() {
  // Each VU registers once (unique email per VU) and reuses the account for every iteration.
  const email = `loadtest-vu${__VU}@example.com`;
  const password = 'password123';

  if (__ITER === 0) {
    group('register', () => {
      const res = http.post(
        `${BASE_URL}/api/users/register`,
        JSON.stringify({ email, password, displayName: `Load VU ${__VU}` }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'register' } }
      );
      registerTrend.add(res.timings.duration);
      // 201 = newly created, 409 = already exists from a prior run — both are fine to proceed.
      const ok = check(res, {
        'register status is 201 or 409': (r) => r.status === 201 || r.status === 409,
      });
      errorRate.add(!ok);
    });
  }

  let token;
  let userId;
  group('login', () => {
    const res = http.post(
      `${BASE_URL}/api/users/login`,
      JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } }
    );
    const ok = checkAndRecord(res, loginTrend, 'login');
    if (ok) {
      const body = res.json();
      token = body.token;
      userId = body.userId;
    }
  });

  if (!token) {
    sleep(1);
    return;
  }

  const authHeaders = { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };

  group('browse_and_review', () => {
    const listRes = http.get(`${BASE_URL}/api/movies?limit=20`, { tags: { name: 'catalog_list' } });
    checkAndRecord(listRes, catalogListTrend, 'catalog_list');

    const movieId = randomItem(MOVIE_IDS);

    // Post a review roughly 1 in 3 iterations — reviews aren't every-request behavior.
    if (randomIntBetween(1, 3) === 1) {
      const reviewRes = http.post(
        `${BASE_URL}/api/reviews`,
        JSON.stringify({
          userId: String(userId),
          movieId,
          rating: randomIntBetween(1, 5),
          reviewText: 'Load-test generated review.',
        }),
        { ...authHeaders, tags: { name: 'post_review' } }
      );
      reviewTrend.add(reviewRes.timings.duration);
      // 201 = created, 409 = already reviewed this movie — both are acceptable outcomes under load.
      const ok = check(reviewRes, {
        'review status is 201 or 409': (r) => r.status === 201 || r.status === 409,
      });
      errorRate.add(!ok);
    }
  });

  group('recommendations', () => {
    const res = http.get(`${BASE_URL}/api/recommendations/${userId}?limit=10`, {
      ...authHeaders,
      tags: { name: 'recommendations' },
    });
    checkAndRecord(res, recommendationTrend, 'recommendations');
  });

  sleep(randomIntBetween(1, 3));
}

export function handleSummary(data) {
  return {
    'loadtest/results/summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

// Minimal built-in-style text summary (k6 cloud/textSummary helper isn't bundled by default).
function textSummary(data) {
  const m = data.metrics;
  const lines = [];
  lines.push('\n=== Load Test Summary ===');
  lines.push(`http_reqs: ${m.http_reqs ? m.http_reqs.values.count : 'n/a'}`);
  lines.push(`http_req_failed rate: ${m.http_req_failed ? (m.http_req_failed.values.rate * 100).toFixed(2) + '%' : 'n/a'}`);
  if (m.http_req_duration) {
    const d = m.http_req_duration.values;
    lines.push(`http_req_duration: avg=${d.avg.toFixed(1)}ms p95=${d['p(95)'].toFixed(1)}ms p99=${d['p(99)'].toFixed(1)}ms max=${d.max.toFixed(1)}ms`);
  }
  const perEndpoint = [
    ['catalog_list_duration', 'GET /api/movies'],
    ['catalog_get_duration', 'GET /api/movies/{id}'],
    ['keyword_search_duration', 'GET /api/movies/search'],
    ['vector_search_duration', 'GET /api/movies/search/vector'],
    ['register_duration', 'POST /api/users/register'],
    ['login_duration', 'POST /api/users/login'],
    ['review_duration', 'POST /api/reviews'],
    ['recommendation_duration', 'GET /api/recommendations/{id}'],
  ];
  lines.push('\n--- Per-endpoint p95/p99 (ms) ---');
  for (const [key, label] of perEndpoint) {
    const t = m[key];
    if (t && typeof t.values.avg === 'number') {
      lines.push(`${label}: avg=${t.values.avg.toFixed(1)} p95=${t.values['p(95)'].toFixed(1)} p99=${t.values['p(99)'].toFixed(1)} max=${t.values.max.toFixed(1)}`);
    }
  }
  return lines.join('\n') + '\n';
}
