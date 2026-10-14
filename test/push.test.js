import { describe, it, expect, beforeEach } from 'vitest';
import signingKey from './fixtures/signing-key.json';
import { env } from 'cloudflare:test';
import { STATUSES } from '../src/tasks.js';
import { sql, exec, post, call } from './helpers.js';
import { accessToken, forgetToken, isDead, sendTo, noteFor } from '../src/push.js';
import { tell, credsFrom } from '../src/notify.js';
import { adminDevices, remember, markDead, forget } from '../src/devices.js';
import { setRole, newSession } from '../src/accounts.js';

// Push, which is the one part of this app that reaches Matt when the app
// is closed. Everything here is about it never costing a request: a phone
// that cannot be told is not a reason for a merge to fail.

/**
 * A service account shaped like Google's, with a real RSA key.
 *
 * The key is a FIXTURE, not generated here. Minting 2048 bits inside the
 * test isolate was slow enough on a CI runner to take the pool's storage
 * down with it: eight other test files failed with "table cards already
 * exists" and this one passed. It authorises nothing, anywhere — see
 * test/fixtures/signing-key.json.
 */
const CREDS = signingKey;

// Deliberately NOT a global `beforeEach(forgetToken)`.
//
// Clearing the cache makes the next send mint a fresh JWT, and signing
// with a 2048-bit RSA key is the most expensive thing in this file. Done
// once per test it was enough CPU to get the workerd isolate killed on a
// CI runner — "Network connection lost", then every later test in that
// isolate failing with "table cards already exists" because the storage
// it meant to roll back had gone with the process. Eight other files
// failed and this one passed, which is what made it look like their bug.
//
// Only the tests that are ABOUT the cache clear it. Everything else is
// happy to reuse a token, which is what production does too.

describe('getting an access token', () => {
  // The only place the cache is cleared: these are the tests about it.
  beforeEach(() => { forgetToken(); });

  const tokenStub = (calls) => async (url, init) => {
    calls.push({ url: String(url), body: String(init?.body || '') });
    return Response.json({ access_token: 'at-' + calls.length, expires_in: 3600 });
  };

  it('signs a JWT and trades it for a token', async () => {
    const calls = [];
    const t = await accessToken(CREDS, { fetch: tokenStub(calls) });
    expect(t).toBe('at-1');
    expect(calls[0].url).toContain('oauth2.googleapis.com');
    expect(calls[0].body).toContain('jwt-bearer');
  });

  it('reuses the token rather than buying one per send', async () => {
    const calls = [];
    const f = tokenStub(calls);
    await accessToken(CREDS, { fetch: f });
    await accessToken(CREDS, { fetch: f });
    await accessToken(CREDS, { fetch: f });
    expect(calls.length, 'it bought a token for every call').toBe(1);
  });

  it('buys a new one once the old is nearly spent', async () => {
    const calls = [];
    const f = tokenStub(calls);
    let now = 1_000_000;
    await accessToken(CREDS, { fetch: f, now: () => now });
    now += 3600_000; // an hour later, the first has expired
    await accessToken(CREDS, { fetch: f, now: () => now });
    expect(calls.length).toBe(2);
  });

  it('says so when Google refuses, rather than sending with nothing', async () => {
    const f = async () => Response.json({ error: 'invalid_grant' }, { status: 400 });
    await expect(accessToken(CREDS, { fetch: f })).rejects.toThrow(/could not get a push token/);
  });
});

describe('a token Firebase has given up on', () => {
  it('reads UNREGISTERED as dead', () => {
    expect(isDead(404, {})).toBe(true);
    expect(isDead(400, { error: { status: 'UNREGISTERED' } })).toBe(true);
  });

  it('does not read a server wobble as dead', () => {
    expect(isDead(500, { error: { status: 'INTERNAL' } })).toBe(false);
    expect(isDead(429, { error: { status: 'RESOURCE_EXHAUSTED' } })).toBe(false);
  });
});

describe('what a transition says on a phone', () => {
  // Matt, an hour after the first version shipped: "the push
  // notifications are way too fucking spammy. I only want to be
  // notified when a task CANNOT be recovered, or when a task is
  // deployed."
  it('says a task is live when it deploys', () => {
    expect(noteFor({ title: 'Pinch zoom', status: 'deployed' }).title).toBe('Pinch zoom is live');
  });

  it('says a task needs you when it blocks, carrying the reason', () => {
    const n = noteFor({ title: 'Fetch list', status: 'blocked', note: 'Not logged in' });
    expect(n.title).toContain('needs you');
    expect(n.body).toBe('Not logged in');
  });

  // Everything else, by name. A notification nobody wants is how
  // notifications get turned off, and then the two that matter are
  // gone too.
  it.each(['merged', 'cancelled', 'in progress', 'in review', 'pending', 'paused'])(
    'says nothing for %s',
    (status) => {
      expect(noteFor({ title: 'X', status })).toBeNull();
    },
  );

  // The whole vocabulary, so a status added later is silent until
  // somebody decides it is worth a buzz.
  it('sends for exactly two of the eight statuses', () => {
    const loud = STATUSES.filter((s) => noteFor({ title: 'X', status: s }) !== null);
    expect(loud.sort()).toEqual(['blocked', 'deployed']);
  });
});

describe('who gets told', () => {
  const asAdmin = async (slug, token) => {
    const [u] = await sql('SELECT id FROM users WHERE slug = ?', slug);
    await remember(env.DB, u.id, { token, platform: 'android' });
    return u.id;
  };

  it('is every admin with a live device', async () => {
    await asAdmin('m4tt0001', 'tok-matt');
    expect((await adminDevices(env.DB)).map((d) => d.token)).toContain('tok-matt');
  });

  it('is not somebody who lost the admin role', async () => {
    const id = await asAdmin('m4tt0001', 'tok-matt');
    await exec('UPDATE users SET role = ? WHERE id = ?', 'user', id);
    expect(await adminDevices(env.DB)).toEqual([]);
  });

  it('is not a device Firebase said was gone', async () => {
    await asAdmin('m4tt0001', 'tok-matt');
    await markDead(env.DB, 'tok-matt');
    expect(await adminDevices(env.DB)).toEqual([]);
  });

  it('is that device again the moment the app comes back', async () => {
    const id = await asAdmin('m4tt0001', 'tok-matt');
    await markDead(env.DB, 'tok-matt');
    await remember(env.DB, id, { token: 'tok-matt', platform: 'android' });
    expect((await adminDevices(env.DB)).map((d) => d.token)).toContain('tok-matt');
  });
});

describe('telling the phones', () => {
  const withCreds = { DB: env.DB, FCM_SERVICE_ACCOUNT: null };

  it('does nothing at all without credentials, and says so', async () => {
    const r = await tell({ DB: env.DB }, { title: 'X', status: 'deployed' });
    expect(r.skipped).toBe('no credentials');
  });

  it('does nothing for a transition not worth a push', async () => {
    const r = await tell({ DB: env.DB }, { title: 'X', status: 'in progress' });
    expect(r.skipped).toBe('not worth a push');
  });

  it('sends to every admin device', async () => {
    withCreds.FCM_SERVICE_ACCOUNT = JSON.stringify(CREDS);
    const [u] = await sql("SELECT id FROM users WHERE slug = 'm4tt0001'");
    await remember(env.DB, u.id, { token: 'tok-a', platform: 'android' });
    await remember(env.DB, u.id, { token: 'tok-b', platform: 'android' });
    const sent = [];
    const f = async (url, init) => {
      if (String(url).includes('oauth2')) return Response.json({ access_token: 'at', expires_in: 3600 });
      sent.push(JSON.parse(init.body).message.token);
      return Response.json({ name: 'ok' });
    };

    const r = await tell(withCreds, { title: 'Pinch zoom', status: 'deployed' }, { fetch: f });

    expect(r.sent).toBe(2);
    expect(sent.sort()).toEqual(['tok-a', 'tok-b']);
  });

  it('marks a dead device dead instead of trying it forever', async () => {
    withCreds.FCM_SERVICE_ACCOUNT = JSON.stringify(CREDS);
    const [u] = await sql("SELECT id FROM users WHERE slug = 'm4tt0001'");
    await remember(env.DB, u.id, { token: 'tok-gone', platform: 'android' });
    const f = async (url) => (String(url).includes('oauth2')
      ? Response.json({ access_token: 'at', expires_in: 3600 })
      : Response.json({ error: { status: 'UNREGISTERED' } }, { status: 404 }));

    const r = await tell(withCreds, { title: 'X', status: 'deployed' }, { fetch: f });

    expect(r.dead).toBe(1);
    expect(await adminDevices(env.DB)).toEqual([]);
  });

  // The one that matters most: a push must never cost a write.
  it('one phone failing does not stop the others being told', async () => {
    withCreds.FCM_SERVICE_ACCOUNT = JSON.stringify(CREDS);
    const [u] = await sql("SELECT id FROM users WHERE slug = 'm4tt0001'");
    await remember(env.DB, u.id, { token: 'tok-bad', platform: 'android' });
    await remember(env.DB, u.id, { token: 'tok-good', platform: 'android' });
    const sent = [];
    const f = async (url, init) => {
      if (String(url).includes('oauth2')) return Response.json({ access_token: 'at', expires_in: 3600 });
      const to = JSON.parse(init.body).message.token;
      if (to === 'tok-bad') throw new Error('the network went away');
      sent.push(to);
      return Response.json({ name: 'ok' });
    };

    const r = await tell(withCreds, { title: 'X', status: 'deployed' }, { fetch: f });

    expect(sent, 'a broken device stopped the working one').toEqual(['tok-good']);
    expect(r.sent).toBe(1);
  });

  it('never throws, whatever Firebase does', async () => {
    withCreds.FCM_SERVICE_ACCOUNT = JSON.stringify(CREDS);
    const [u] = await sql("SELECT id FROM users WHERE slug = 'm4tt0001'");
    await remember(env.DB, u.id, { token: 'tok-x', platform: 'android' });
    const f = async () => { throw new Error('nothing works'); };
    await expect(tell(withCreds, { title: 'X', status: 'deployed' }, { fetch: f })).resolves.toBeTruthy();
  });
});

describe('credsFrom', () => {
  it('is null when the binding is missing, so nothing tries to send', () => {
    expect(credsFrom({})).toBeNull();
  });

  it('is null for a half-filled service account rather than failing later', () => {
    expect(credsFrom({ FCM_SERVICE_ACCOUNT: '{"client_email":"a@b"}' })).toBeNull();
  });

  it('is null for something that is not JSON', () => {
    expect(credsFrom({ FCM_SERVICE_ACCOUNT: 'not json' })).toBeNull();
  });
});

// Who is told, proved through the real send path.
//
// Matt: "i need to be positive this ONLY sends to users with the admin
// role, and it needs to be certain that adding that role correctly
// updates pushes to be sent, and removing the role correctly stops
// pushes."
//
// The tests above check `adminDevices` in isolation. These assert the
// tokens Firebase was actually asked to deliver to, because that is the
// thing that reaches a phone — a wrong answer from the query and a wrong
// send are the same bug to the person holding it.
//
// It matters more than it did yesterday: `agent` is a role as of today,
// so "not an admin" has two shapes now, and a test that only tried
// `user` would miss the one that was just invented.
describe('only admins are pushed to, proved end to end', () => {
  const CREDS_ENV = () => ({ DB: env.DB, FCM_SERVICE_ACCOUNT: JSON.stringify(CREDS) });

  /** Every token Firebase was asked to deliver to, in one send. */
  async function tokensSentTo(task = { title: 'A task', status: 'deployed' }) {
    const sent = [];
    const f = async (url, init) => {
      if (String(url).includes('oauth2')) {
        return Response.json({ access_token: 'at', expires_in: 3600 });
      }
      sent.push(JSON.parse(init.body).message.token);
      return Response.json({ name: 'ok' });
    };
    await tell(CREDS_ENV(), task, { fetch: f });
    return sent.sort();
  }

  /** An account at a role, with a device registered to it. */
  async function personWithPhone(slug, role, token) {
    const [u] = await sql('SELECT id FROM users WHERE slug = ?', slug);
    await exec('UPDATE users SET role = ? WHERE id = ?', role, u.id);
    await remember(env.DB, u.id, { token, platform: 'android' });
    return u.id;
  }

  it('sends to an admin', async () => {
    await personWithPhone('m4tt0001', 'admin', 'tok-admin');
    expect(await tokensSentTo()).toEqual(['tok-admin']);
  });

  it('sends nothing to a plain user, even with a device registered', async () => {
    await personWithPhone('m4tt0001', 'user', 'tok-user');
    expect(await tokensSentTo(), 'a non-admin was pushed to').toEqual([]);
  });

  // The role invented today. A build agent has a session and could
  // register a device; it must never be told about tasks.
  it('sends nothing to the agent role', async () => {
    await personWithPhone('m4tt0001', 'agent', 'tok-agent');
    expect(await tokensSentTo(), 'the agent role was pushed to').toEqual([]);
  });

  it('sends to the admins and to nobody else, with a crowd registered', async () => {
    await personWithPhone('m4tt0001', 'admin', 'tok-matt');
    await personWithPhone('k4yy0003', 'admin', 'tok-kayla');
    // Two accounts that must be silent, at both non-admin roles.
    const [a] = await sql("SELECT id FROM users WHERE slug = 'bfa462f5'");
    const [b] = await sql("SELECT id FROM users WHERE slug = 't4pee71g'");
    if (a) { await exec('UPDATE users SET role = ? WHERE id = ?', 'agent', a.id); await remember(env.DB, a.id, { token: 'tok-agent', platform: 'android' }); }
    if (b) { await exec('UPDATE users SET role = ? WHERE id = ?', 'user', b.id); await remember(env.DB, b.id, { token: 'tok-plain', platform: 'android' }); }

    expect(await tokensSentTo()).toEqual(['tok-kayla', 'tok-matt']);
  });

  it('starts sending the moment somebody is made an admin, with nothing else done', async () => {
    const id = await personWithPhone('m4tt0001', 'user', 'tok-promoted');
    expect(await tokensSentTo(), 'pushed before the role was given').toEqual([]);

    // The role, and only the role. No re-registering, no restart.
    await setRole(env.DB, 'm4tt0001', 'admin');

    expect(await tokensSentTo(), 'granting admin did not start pushes').toEqual(['tok-promoted']);
    expect(id).toBeTruthy();
  });

  it('stops sending the moment the role is taken away', async () => {
    await personWithPhone('m4tt0001', 'admin', 'tok-demoted');
    expect(await tokensSentTo()).toEqual(['tok-demoted']);

    await setRole(env.DB, 'm4tt0001', 'user');

    expect(await tokensSentTo(), 'a demoted account was still pushed to').toEqual([]);
  });

  it('stops for one admin without stopping the other', async () => {
    await personWithPhone('m4tt0001', 'admin', 'tok-matt');
    await personWithPhone('k4yy0003', 'admin', 'tok-kayla');
    expect(await tokensSentTo()).toEqual(['tok-kayla', 'tok-matt']);

    await setRole(env.DB, 'k4yy0003', 'user');

    expect(await tokensSentTo()).toEqual(['tok-matt']);
  });

  // Nothing is cached anywhere: the role is read at send time, by join,
  // every time. This is what makes the two tests above hold a minute
  // later as well as a millisecond later.
  it('reads the role at send time rather than when the device registered', async () => {
    await personWithPhone('m4tt0001', 'admin', 'tok-a');
    await setRole(env.DB, 'm4tt0001', 'user');
    await setRole(env.DB, 'm4tt0001', 'admin');
    await setRole(env.DB, 'm4tt0001', 'user');
    expect(await tokensSentTo(), 'a stale role decided the send').toEqual([]);
  });

  it('sends to every device an admin has, not just the newest', async () => {
    const id = await personWithPhone('m4tt0001', 'admin', 'tok-phone');
    await remember(env.DB, id, { token: 'tok-tablet', platform: 'android' });
    expect(await tokensSentTo()).toEqual(['tok-phone', 'tok-tablet']);
  });
})

// Android only.
//
// Matt: "web does NOT need any pushes for any reason ever right now."
// The website is open in front of him when he is using it. A `web` row
// would be a row the send loop tries, fails on, and marks dead forever —
// so it is refused at the door instead of accepted and never used.
describe('which platforms can register at all', () => {
  const idOf = async () => (await sql("SELECT id FROM users WHERE slug = 'm4tt0001'"))[0].id;

  it('takes an android device', async () => {
    const r = await remember(env.DB, await idOf(), { token: 'tok-droid', platform: 'android' });
    expect(r.status).toBe(200);
  });

  it('refuses web, rather than storing a row nothing will ever send to', async () => {
    const r = await remember(env.DB, await idOf(), { token: 'tok-web', platform: 'web' });
    expect(r.status, 'a web device was registered').toBe(400);
    expect(await sql('SELECT token FROM devices WHERE token = ?', 'tok-web')).toEqual([]);
  });

  it('refuses ios for the same reason', async () => {
    const r = await remember(env.DB, await idOf(), { token: 'tok-ios', platform: 'ios' });
    expect(r.status).toBe(400);
  });

  it('says which platforms it does take, so the refusal is actionable', async () => {
    const r = await remember(env.DB, await idOf(), { token: 'tok-web', platform: 'web' });
    expect(r.body.error).toContain('android');
  });
})

// A role change lands on the next push, with nothing to wait for.
//
// Matt: "do these updates happen mid-session? like if i give a user a
// role and they are mid-session, will they start getting pushes
// immediately? likewise for removal?"
//
// Yes, and the reason is that the send path never looks at a session.
// It joins `devices` to `users` and reads the role as it is at that
// instant. There is no cached claim, no token to expire, nothing to
// refresh. These make that a fact rather than a reading of the code.
describe('a role change while somebody is signed in', () => {
  const CREDS_ENV = () => ({ DB: env.DB, FCM_SERVICE_ACCOUNT: JSON.stringify(CREDS) });

  async function sentTo() {
    const sent = [];
    const f = async (url, init) => {
      if (String(url).includes('oauth2')) return Response.json({ access_token: 'at', expires_in: 3600 });
      sent.push(JSON.parse(init.body).message.token);
      return Response.json({ name: 'ok' });
    };
    await tell(CREDS_ENV(), { title: 'A task', status: 'deployed' }, { fetch: f });
    return sent.sort();
  }

  it('starts pushing to a live session the moment the role is given', async () => {
    const [u] = await sql("SELECT id FROM users WHERE slug = 'm4tt0001'");
    await exec('UPDATE users SET role = ? WHERE id = ?', 'user', u.id);
    // A session that was opened BEFORE the role existed and is never
    // touched again — the exact shape of the question.
    const session = await newSession(env.DB, u.id);
    await remember(env.DB, u.id, { token: 'tok-live', platform: 'android' });
    expect(await sentTo()).toEqual([]);

    await setRole(env.DB, 'm4tt0001', 'admin');

    expect(await sentTo(), 'a live session did not start receiving').toEqual(['tok-live']);
    expect(session, 'the session itself was never re-issued').toBeTruthy();
  });

  it('stops pushing to a live session the moment the role is taken', async () => {
    const [u] = await sql("SELECT id FROM users WHERE slug = 'm4tt0001'");
    await exec('UPDATE users SET role = ? WHERE id = ?', 'admin', u.id);
    await newSession(env.DB, u.id);
    await remember(env.DB, u.id, { token: 'tok-live', platform: 'android' });
    expect(await sentTo()).toEqual(['tok-live']);

    await setRole(env.DB, 'm4tt0001', 'user');

    expect(await sentTo(), 'a demoted live session kept receiving').toEqual([]);
  });

  // The gap Matt's question turned up. Signing out ends the session and
  // used to leave the device registered, so a signed-out phone kept
  // buzzing with task titles for as long as the account stayed admin.
  it('stops pushing to a phone that signed out', async () => {
    const [u] = await sql("SELECT id FROM users WHERE slug = 'm4tt0001'");
    await exec('UPDATE users SET role = ? WHERE id = ?', 'admin', u.id);
    await remember(env.DB, u.id, { token: 'tok-byebye', platform: 'android' });
    expect(await sentTo()).toEqual(['tok-byebye']);

    // What the app does on sign-out, in order.
    await forget(env.DB, 'tok-byebye');

    expect(await sentTo(), 'a signed-out phone was still pushed to').toEqual([]);
  });

  it('leaves the other phones alone when one signs out', async () => {
    const [u] = await sql("SELECT id FROM users WHERE slug = 'm4tt0001'");
    await exec('UPDATE users SET role = ? WHERE id = ?', 'admin', u.id);
    await remember(env.DB, u.id, { token: 'tok-phone', platform: 'android' });
    await remember(env.DB, u.id, { token: 'tok-tablet', platform: 'android' });

    await forget(env.DB, 'tok-phone');

    expect(await sentTo()).toEqual(['tok-tablet']);
  });
})
