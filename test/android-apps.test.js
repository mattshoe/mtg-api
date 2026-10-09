import { describe, it, expect } from 'vitest';
import settings from '../apps/settings.gradle.kts?raw';
import appsYml from '../.github/workflows/apps.yml?raw';

// Android holds one app per applicationId. Two modules in the build
// claiming the same one means only one of them can ever be installed,
// and the other is dead weight that still builds, still runs in CI and
// still looks like a rollback. `:app` sat there that way after
// `:androidApp` took its id to upgrade it in place.

const builds = import.meta.glob('../apps/*/build.gradle.kts', {
  query: '?raw', import: 'default', eager: true,
});

const included = [...settings.matchAll(/^\s*include\("(:[\w-]+)"\)/gm)].map((m) => m[1]);
const buildOf = (module) => builds[`../apps/${module.slice(1)}/build.gradle.kts`] ?? '';
const applicationId = (module) => buildOf(module).match(/applicationId\s*=\s*"([^"]+)"/)?.[1];

describe('the Android apps in the build', () => {
  it('give the app that ships its applicationId to no other module', () => {
    const shipped = applicationId(':androidApp');
    expect(shipped, ':androidApp declares no applicationId').toBeTruthy();
    const sharing = included.filter((m) => m !== ':androidApp' && applicationId(m) === shipped);
    expect(sharing, `${sharing.join(', ')} claims ${shipped} too, so Android can never install it beside the app that ships`)
      .toEqual([]);
  });

  it('are not assembled in CI unless the build includes them', () => {
    const built = [...appsYml.matchAll(/(:[\w-]+):assemble/g)].map((m) => m[1]);
    const missing = built.filter((m) => !included.includes(m));
    expect(missing, `CI assembles ${missing.join(', ')}, which settings.gradle.kts does not include`).toEqual([]);
  });
});
