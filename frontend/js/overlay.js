// Making the back button dismiss what is on top, instead of navigating
// behind it.
//
// The card drawer and the dialogs open without touching history, so on a
// phone the back gesture went to the previous route while the drawer stayed
// up. You could only get rid of it by finding the X, and the page behind it
// had silently moved.
//
// An overlay pushes a history entry when it opens. Back pops that entry and
// closes the overlay instead of leaving the route. Closing any other way —
// the X, escape, the scrim — takes the entry back off again, so the history
// never fills up with dead steps.

/** Closers for the overlays currently up, outermost first. */
const open = [];

// history.back() fires popstate, and the handler must not then close a
// second overlay. One counter, because two can be dismissed in a row.
let expectedPops = 0;

/**
 * Register an overlay that has just opened.
 * `close` is called with no arguments when the back button dismisses it.
 */
export function pushOverlay(close) {
  open.push(close);
  history.pushState({ overlay: open.length }, '');
}

/**
 * An overlay closed by its own means. Drops the history entry it pushed so
 * the next back press goes where the user expects rather than doing nothing.
 */
export function dropOverlay(close) {
  const i = close ? open.lastIndexOf(close) : open.length - 1;
  if (i === -1) return;
  open.splice(i, 1);
  expectedPops += 1;
  history.back();
}

/**
 * Forget an overlay without touching history.
 *
 * For the case where the overlay is closing because the caller is about to
 * `location.replace` the route: the entry the overlay pushed is the current
 * one, so replacing it puts the new route exactly where the overlay was and
 * back still reaches the page underneath. Popping first would undo the
 * navigation instead.
 */
export function forgetOverlay(close) {
  const i = close ? open.lastIndexOf(close) : open.length - 1;
  if (i !== -1) open.splice(i, 1);
}

/** Forget everything, for a route change that took the overlays with it. */
export function clearOverlays() {
  open.length = 0;
}

export const overlayCount = () => open.length;

addEventListener('popstate', () => {
  if (expectedPops > 0) { expectedPops -= 1; return; }
  const close = open.pop();
  if (close) close();
});
