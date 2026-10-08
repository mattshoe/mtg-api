#!/usr/bin/env node
// A PreToolUse hook that refuses the handful of commands no builder may
// run. The rules are in `scripts/bash-deny.mjs`, where the suite tests
// them; this is only the IO.
//
// It exists because `permissions.deny` is not enough on its own. A deny
// rule is a permission decision, and `bypassPermissions` — which is how
// every builder is launched — is the mode that skips permission
// decisions. A PreToolUse hook answering `deny` blocks the call whatever
// the mode is, so this is the half that actually enforces. The deny list
// in `.claude/settings.json` stays as the belt.
//
// Wire-up, in `.claude/settings.json`:
//
//   "PreToolUse": [{ "matcher": "Bash",
//     "hooks": [{ "type": "command",
//       "command": "node $CLAUDE_PROJECT_DIR/scripts/intake/deny-bash.mjs" }] }]
import { denied } from '../bash-deny.mjs'

let raw = ''
process.stdin.setEncoding('utf8')
process.stdin.on('data', (c) => { raw += c })
process.stdin.on('end', () => {
  let payload
  try { payload = JSON.parse(raw) } catch { process.exit(0) }
  if (payload?.tool_name !== 'Bash') process.exit(0)

  const command = payload?.tool_input?.command
  // The hook's own cwd is the session's, which for a builder is its
  // worktree. That is what `git -C` is measured against.
  const cwd = payload?.cwd || process.cwd()
  const v = denied(command, { cwd })
  if (!v.deny) process.exit(0)

  // Structured, so the reason reaches the model rather than only a log.
  process.stdout.write(`${JSON.stringify({
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason: v.reason,
    },
  })}\n`)
  // And on stderr too, so it is visible when the structured form is not
  // read for any reason.
  process.stderr.write(`${v.reason}\n`)
  process.exit(0)
})
