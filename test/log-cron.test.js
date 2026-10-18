import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { LOG_PRUNE_CRON } from '../src/log.js';

// The Worker suite can call `scheduled` with any cron it likes, but only
// wrangler.toml decides whether Cloudflare ever does. Without the hourly
// trigger the prune runs once a day, and a log written just after it
// lives for 48 hours. Matt: "logs older than 24hrs should always be
// deleted".
describe('the log prune is scheduled', () => {
  it('wrangler.toml fires the hourly log prune', () => {
    const toml = readFileSync(new URL('../wrangler.toml', import.meta.url), 'utf8');
    const line = toml.split('\n').find((l) => /^\s*crons\s*=/.test(l)) || '';
    const crons = JSON.parse(line.replace(/^\s*crons\s*=\s*/, '').replace(/#.*$/, ''));
    expect(crons, 'no trigger in wrangler.toml runs the hourly log prune').toContain(LOG_PRUNE_CRON);
  });
});
