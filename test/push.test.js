import { describe, it, expect, beforeEach } from 'vitest';
import { env } from 'cloudflare:test';
import { sql, exec, post, call } from './helpers.js';
import { accessToken, forgetToken, isDead, sendTo, noteFor } from '../src/push.js';
import { tell, credsFrom } from '../src/notify.js';
import { adminDevices, remember, markDead } from '../src/devices.js';

// Push, which is the one part of this app that reaches Matt when the app
// is closed. Everything here is about it never costing a request: a phone
// that cannot be told is not a reason for a merge to fail.

/** A service account shaped like Google's, with a real RSA key. */
const CREDS = {
  client_email: 'push@test.iam.gserviceaccount.com',
  project_id: 'test-project',
  // Generated for this test and used nowhere: signing is exercised
  // against it, and no real key belongs in a repository.
  private_key: null,
};

beforeEach(async () => {
  forgetToken();
  if (!CREDS.private_key) {
    const pair = await crypto.subtle.generateKey(
      { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
      true,
      ['sign', 'verify'],
    );
    const der = await crypto.subtle.exportKey('pkcs8', pair.privateKey);
    const b64 = btoa(String.fromCharCode(...new Uint8Array(der))).replace(/(.{64})/g, '$1\n');
    CREDS.private_key = `-----BEGIN PRIVATE KEY-----\n${b64}\n-----END PRIVATE KEY-----\n`;
  }
});

describe('getting an access token', () => {
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
  it('says a task is live when it deploys', () => {
    expect(noteFor({ title: 'Pinch zoom', status: 'deployed' }).title).toBe('Pinch zoom is live');
  });

  it('says a task needs you when it blocks, carrying the reason', () => {
    const n = noteFor({ title: 'Fetch list', status: 'blocked', note: 'Not logged in' });
    expect(n.title).toContain('needs you');
    expect(n.body).toBe('Not logged in');
  });

  // The thing that decides whether notifications stay turned on.
  it('says nothing for the statuses that happen all day', () => {
    expect(noteFor({ title: 'X', status: 'in progress' })).toBeNull();
    expect(noteFor({ title: 'X', status: 'in review' })).toBeNull();
    expect(noteFor({ title: 'X', status: 'pending' })).toBeNull();
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
    const r = await tell({ DB: env.DB }, { title: 'X', status: 'merged' });
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

    const r = await tell(withCreds, { title: 'X', status: 'merged' }, { fetch: f });

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

    const r = await tell(withCreds, { title: 'X', status: 'merged' }, { fetch: f });

    expect(sent, 'a broken device stopped the working one').toEqual(['tok-good']);
    expect(r.sent).toBe(1);
  });

  it('never throws, whatever Firebase does', async () => {
    withCreds.FCM_SERVICE_ACCOUNT = JSON.stringify(CREDS);
    const [u] = await sql("SELECT id FROM users WHERE slug = 'm4tt0001'");
    await remember(env.DB, u.id, { token: 'tok-x', platform: 'android' });
    const f = async () => { throw new Error('nothing works'); };
    await expect(tell(withCreds, { title: 'X', status: 'merged' }, { fetch: f })).resolves.toBeTruthy();
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
