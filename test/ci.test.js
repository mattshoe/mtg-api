import { describe, it, expect } from 'vitest';
import { parse } from 'yaml';
import appsYml from '../.github/workflows/apps.yml?raw';
import gradleProperties from '../apps/gradle.properties?raw';
import androidBuild from '../apps/androidApp/build.gradle.kts?raw';
import floors from './suite-floors.json';

// Why a pull request takes as long as it does.
//
// Measured on #42: `android` ran compile (1:36), the JVM screens suite
// (5:56) and the emulator (9:38) one after another, eighteen minutes,
// while `web` was done at 3:46 and `shared` at 3:18. Of the emulator's
// time, 6:34 was 469 tests on one device and most of the rest was
// downloading a system image and cold-booting it.
//
// None of this is a guess about speed. It is the shape that makes the
// speed possible: nothing that could run alongside something else waits
// for it, and nothing is redone that was done last time.

const workflow = parse(appsYml);
const jobs = workflow.jobs;

const steps = (job) => job.steps ?? [];
const runs = (job, pattern) => steps(job).some(
  (s) => pattern.test(s.run ?? '') || pattern.test(s.with?.script ?? ''),
);
const jobRunning = (pattern) => Object.entries(jobs)
  .filter(([, job]) => runs(job, pattern))
  .map(([name]) => name);
const needs = (name) => [jobs[name]?.needs ?? []].flat();

describe('the apps workflow', () => {
  it('runs the JVM screens suite and the emulator in separate jobs, neither waiting for the other', () => {
    const jvm = jobRunning(/:androidApp:testDebugUnitTest/);
    const device = jobRunning(/:androidApp:connectedDebugAndroidTest/);
    expect(jvm, 'no job runs the JVM screens suite').toHaveLength(1);
    expect(device, 'no job runs the device suite').toHaveLength(1);
    expect(jvm[0], `the screens suite and the emulator are both in "${jvm[0]}", so one waits for the other`)
      .not.toBe(device[0]);
    expect(needs(device[0]), 'the emulator waits for the screens suite').not.toContain(jvm[0]);
    expect(needs(jvm[0]), 'the screens suite waits for the emulator').not.toContain(device[0]);
  });

  it('splits the device suite across more than one emulator', () => {
    const [name] = jobRunning(/:androidApp:connectedDebugAndroidTest/);
    const shards = jobs[name]?.strategy?.matrix?.shard ?? [];
    expect(shards.length, `"${name}" runs every device test on one emulator`).toBeGreaterThan(1);
    // Two shards of 235 and 234 took 2:35 and 4:45 on #46 — the split
    // is by test, not by cost, so one half can carry the slow ones.
    // Under 200 a shard keeps the slow half inside ten minutes.
    const perShard = Math.ceil(floors.device / shards.length);
    expect(perShard, `${shards.length} shards is ${perShard} device tests each, and the slow shard decides the run`)
      .toBeLessThan(200);
    expect(jobs[name].strategy['fail-fast'], 'one shard failing would cancel the other and hide its results')
      .toBe(false);
    const script = steps(jobs[name]).map((s) => s.with?.script ?? '').join('\n');
    expect(script).toMatch(/numShards=\$\{\{\s*strategy\.job-total\s*\}\}/);
    expect(script).toMatch(/shardIndex=\$\{\{\s*strategy\.job-index\s*\}\}/);
  });

  it('still counts every device test once, across all the shards together', () => {
    const [device] = jobRunning(/:androidApp:connectedDebugAndroidTest/);
    const counters = Object.keys(jobs).filter((name) => needs(name).includes(device)
      && runs(jobs[name], /check-test-count\.mjs\s+apps\/androidApp\/src\/sharedTest/)
      && runs(jobs[name], /check-suite-floor\.mjs\s+device=/));
    expect(counters, 'no job adds the shards up, so a shard that ran nothing would go green').toHaveLength(1);
    expect(runs(jobs[device], /check-suite-floor\.mjs\s+device=/),
      'a single shard is checked against the whole device floor, which it can never reach').toBe(false);
  });

  it('keeps the emulator between runs rather than downloading and cold-booting it every time', () => {
    const [name] = jobRunning(/:androidApp:connectedDebugAndroidTest/);
    const cached = steps(jobs[name]).filter((s) => /^actions\/cache(\/restore)?@/.test(s.uses ?? ''))
      .map((s) => String(s.with?.path ?? ''));
    expect(cached.some((p) => p.includes('~/.android/avd')), 'the AVD is not cached').toBe(true);
    expect(cached.some((p) => /system-images/.test(p)), 'the system image is not cached').toBe(true);
  });

  it('does not throw away the Gradle daemon between steps of one job', () => {
    const noDaemon = Object.entries(jobs).filter(([, job]) => runs(job, /--no-daemon/)).map(([n]) => n);
    expect(noDaemon, 'these jobs start a fresh JVM for every Gradle step').toEqual([]);
  });
});

describe('the Gradle build', () => {
  const props = Object.fromEntries(gradleProperties.split('\n')
    .map((l) => l.trim()).filter((l) => l && !l.startsWith('#'))
    .map((l) => l.split('=').map((s) => s.trim())));

  it('builds independent modules at the same time', () => {
    expect(props['org.gradle.parallel']).toBe('true');
  });

  it('reuses task outputs it has already produced', () => {
    expect(props['org.gradle.caching']).toBe('true');
  });

  it('runs more than one screens test class at a time, each still in a JVM of its own', () => {
    // forkEvery(1) is load-bearing — see the comment beside it. It
    // says how often a JVM is replaced, not how many run at once.
    expect(androidBuild).toMatch(/setForkEvery\(1\)/);
    expect(androidBuild, 'maxParallelForks is unset, so 42 classes run one after another')
      .toMatch(/maxParallelForks\s*=/);
  });
});
