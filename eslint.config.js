// Deliberately narrow. The point is not style, it is the one class of bug
// `node --check` cannot see: a name that parses fine and is not defined
// anywhere — a missing import, a rename that missed a call site. That
// shipped a blank deck page once.
export default [
  {
    files: ['frontend/js/**/*.js'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      globals: Object.fromEntries([
        'document', 'window', 'location', 'navigator', 'localStorage',
        'sessionStorage', 'fetch', 'Request', 'Response', 'Headers', 'URL',
        'URLSearchParams', 'Blob', 'FormData', 'AbortController', 'Event',
        'CustomEvent', 'KeyboardEvent', 'Node', 'HTMLElement', 'Image',
        'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval',
        'queueMicrotask', 'requestAnimationFrame', 'performance', 'console',
        'addEventListener', 'removeEventListener', 'matchMedia', 'getComputedStyle',
        'alert', 'confirm', 'prompt', 'structuredClone', 'TextEncoder', 'crypto',
        'CSS', 'history', 'FileReader', 'DataTransfer',
      ].map((g) => [g, 'readonly'])),
    },
    rules: {
      'no-undef': 'error',
      'no-unused-vars': ['error', { args: 'none', varsIgnorePattern: '^_' }],
    },
  },
];
