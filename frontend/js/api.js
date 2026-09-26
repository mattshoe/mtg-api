// Talking to the Worker.
//
// POST for everything: it has no URL length limit, and the GET form of
// /query is deliberately read-only so it could not do add/remove anyway.

export const API = 'https://mtg-api.mattshoe81.workers.dev';

// Set by admin.js once it loads. Kept as a hook rather than a direct import
// so api.js stays the lower layer of the two and there is no cycle.
let authHeader = () => ({});
let onUnauthorized = () => {};
export function useAuth(headerFn, rejectedFn) {
  authHeader = headerFn;
  onUnauthorized = rejectedFn;
}

async function call(path, init) {
  try {
    const res = await fetch(API + path, init);
    const text = await res.text();
    let body;
    try {
      body = JSON.parse(text);
    } catch {
      body = { error: text.slice(0, 400) || `HTTP ${res.status}` };
    }
    if (!res.ok || (body && body.error)) {
      const err = new Error(body?.error || `HTTP ${res.status}`);
      err.status = res.status;
      err.adminRequired = Boolean(body?.admin_required);
      // The token expired or the Worker's password changed. Drop admin mode
      // so the UI stops offering actions it can no longer perform.
      if (res.status === 401) onUnauthorized();
      throw err;
    }
    return body;
  } catch (e) {
    // A network failure and an API error read very differently to a user.
    if (e instanceof TypeError) {
      throw new Error('cannot reach the API — check your connection');
    }
    throw e;
  }
}

const post = (path, body) => call(path, {
  method: 'POST',
  headers: { 'content-type': 'application/json', ...authHeader() },
  body: JSON.stringify(body),
});

/**
 * Run SQL. Returns { cols, rows, n } with rows as arrays.
 * @param {string} sql
 * @param {Array} params values for `?` placeholders
 */
export function query(sql, params = [], opts = {}) {
  return post('/query', { sql, params, ...opts });
}

/** Same, but rows come back as objects — easier when shapes vary. */
export async function rows(sql, params = []) {
  const r = await post('/query', { sql, params, fmt: 'objects' });
  return r.rows || [];
}

/** First row, or null. */
export async function one(sql, params = []) {
  const r = await rows(sql, params);
  return r[0] || null;
}

/** First column of the first row. */
export async function scalar(sql, params = []) {
  const r = await query(sql, params);
  return r.rows.length ? r.rows[0][0] : null;
}

export function schema() {
  return call('/schema', { method: 'GET' });
}

export function addCards(body) {
  return post('/cards/add', body);
}

export function removeCards(body) {
  return post('/cards/remove', body);
}

/** cols+rows -> array of objects, for when query() is more convenient. */
export function toObjects(res) {
  return res.rows.map((r) => Object.fromEntries(res.cols.map((c, i) => [c, r[i]])));
}
