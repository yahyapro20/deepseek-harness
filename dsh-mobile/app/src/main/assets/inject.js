/* dsh-mobile injection script: handles SPA re-rendering and soft keyboard.
   Idempotent: repeated execution will not stack side effects. */
(function () {
  'use strict';
  if (window.__dshMobileInjected) return;
  window.__dshMobileInjected = true;

  // Old WebViews (Chrome < 116, common in system WebViews on Android 12 and below)
  // lack AbortSignal.any/timeout, causing dsh's workspace selectors to directly throw
  // "AbortSignal.any is not a function" (issue #2/#4). Provide minimal implementation.
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.any !== 'function') {
    AbortSignal.any = function (signals) {
      var ctrl = new AbortController();
      for (var i = 0; i < signals.length; i++) {
        var s = signals[i];
        if (!s) continue;
        if (s.aborted) { ctrl.abort(s.reason); return ctrl.signal; }
        s.addEventListener('abort', (function (sig) {
          return function () { ctrl.abort(sig.reason); };
        })(s), { once: true });
      }
      return ctrl.signal;
    };
  }
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout !== 'function') {
    AbortSignal.timeout = function (ms) {
      var ctrl = new AbortController();
      setTimeout(function () {
        try { ctrl.abort(new DOMException('The operation timed out.', 'TimeoutError')); }
        catch (e) { ctrl.abort(); }
      }, ms);
      return ctrl.signal;
    };
  }

  // Ensure viewport is not reverted to desktop width after SPA route/theme changes
  function ensureViewport() {
    var v = document.querySelector('meta[name=viewport]');
    var want = 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover, interactive-widget=resizes-content';
    if (v && v.getAttribute('content') !== want) {
      v.setAttribute('content', want);
    }
  }

  // Details panel (_detailsCol) three-state gating:
  //  - Has substantial content → .dsh-mobile-open, full-screen overlay
  //  - Only placeholder text ("Click a tool row...") → .dsh-mobile-empty, completely hidden.
  //    Otherwise it would permanently occupy most of the width in the grid, squeezing the message flow into a narrow strip, wrapping text word by word,
  //    and stretching cards to full screen height—another root cause of "cannot see conversation messages".
  function updateDetailsCol() {
    var mobile = window.innerWidth <= 700;
    var cols = document.querySelectorAll('div[class*="_detailsCol"]');
    for (var i = 0; i < cols.length; i++) {
      var dc = cols[i];
      var text = (dc.innerText || '').trim();
      var placeholder = /click a tool row/i.test(text) || text.length < 30;
      var has = !placeholder && (text.length > 150 ||
        !!dc.querySelector('pre, code, table, img, video, canvas, textarea, [class*="_code"], [class*="_terminal"]'));
      dc.classList.toggle('dsh-mobile-open', mobile && has);
      dc.classList.toggle('dsh-mobile-empty', mobile && !has);
    }
  }

  // Uniformly find dsh sidebar toggle: prefer class name fragment (_toggle), with aria-label text as fallback
  // (aria-label may change after dsh updates or language switches, class names are more stable).
  function findSidebarToggle() {
    return document.querySelector('div[class*="_sidebarCol"] button[class*="_toggle"]') ||
      document.querySelector('div[class*="_sidebarCol"] button[aria-label*="sidebar" i]') ||
      document.querySelector('div[class*="_sidebarCol"] button[class*="_iconButton"]');
  }

  // dsh sidebar's actual internal state (collapsed state has _collapsed class on inner root node).
  // Must never check width (collapsed state is display:none via our CSS, width is always 0),
  // nor check body's dsh-mobile-drawer class—that is derived by us, with a 300ms debounce lag,
  // relying on it to flip toggle again would close the drawer immediately after opening on slow-rendering real devices ("button unresponsive").
  function sidebarIsOpen() {
    var col = document.querySelector('div[class*="_sidebarCol"]');
    if (!col) return false;
    return !col.querySelector('[class*="_collapsed"]');
  }
  // User intent timestamps: active open (lastOpenIntent) cancels ongoing forced close,
  // active close (lastCloseIntent) prevents delayed re-opening—two async operations no longer conflict
  // (old behavior: clicking session row then hamburger within 6s forced close window caused drawer to open and close repeatedly,
  //  looking like "touch failure/jumping").
  var lastOpenIntent = 0;
  var lastCloseIntent = 0;
  // Only click toggle to expand when confirmed currently collapsed; retry at most once if expansion fails (click had no effect).
  function openSidebar(attemptsLeft) {
    lastOpenIntent = Date.now();
    if (sidebarIsOpen()) { updateSidebarDrawer(); return; }
    var t = findSidebarToggle();
    if (t) t.click();
    setTimeout(function () {
      updateSidebarDrawer();
      // If user actively closed within 700ms (clicked mask/selected session), do not retry opening
      if (!sidebarIsOpen() && attemptsLeft > 0 && Date.now() - lastCloseIntent > 900) openSidebar(attemptsLeft - 1);
    }, 700);
  }
  // Only click toggle to collapse when confirmed currently expanded.
  function closeSidebar() {
    if (!sidebarIsOpen()) { updateSidebarDrawer(); return; }
    lastCloseIntent = Date.now();
    var t = findSidebarToggle();
    if (t) t.click();
    setTimeout(updateSidebarDrawer, 300);
  }
  // Continuously enforce closing within time window: dsh re-expands sidebar based on persisted state after navigation,
  // and expansion may occur 1-2 seconds after navigation ends (slower on real devices), clicking toggle once might be ineffective.
  // Check every 400ms within window, only click toggle if confirmed expanded (never blindly flip).
  // If user actively opens within window (lastOpenIntent later than window start), yield immediately.
  function enforceClosed(until, start) {
    if (start === undefined) start = Date.now();
    if (Date.now() > until) return;
    if (lastOpenIntent > start) return;
    closeSidebar();
    setTimeout(function () { enforceClosed(until, start); }, 400);
  }

  // Top-left floating button: rail is not permanent, clicking it calls out sidebar drawer (i.e., triggers dsh's built-in toggle)
  var railBtn = null;
  function ensureRailBtn() {
    if (railBtn && railBtn.isConnected) return railBtn;
    railBtn = document.getElementById('dsh-mobile-railbtn');
    if (!railBtn) {
      railBtn = document.createElement('button');
      railBtn.id = 'dsh-mobile-railbtn';
      railBtn.setAttribute('aria-label', 'Open sidebar');
      railBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="4" y1="6" x2="20" y2="6"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="18" x2="20" y2="18"/></svg>';
      railBtn.addEventListener('click', function () { openSidebar(2); });
      document.body.appendChild(railBtn);
    }
    return railBtn;
  }

  // Sidebar drawerification: on mobile, when sidebar is expanded (width >100px), it should not squeeze session area,
  // instead use overlay drawer + mask, click mask to close (click built-in toggle button).
  var maskEl = null;
  function ensureMask() {
    if (maskEl && maskEl.isConnected) return maskEl;
    maskEl = document.getElementById('dsh-mobile-drawer-mask');
    if (!maskEl) {
      maskEl = document.createElement('div');
      maskEl.id = 'dsh-mobile-drawer-mask';
      maskEl.addEventListener('click', function () { closeSidebar(); });
      document.body.appendChild(maskEl);
    }
    return maskEl;
  }
  function updateSidebarDrawer() {
    if (window.innerWidth > 700) {
      document.body.classList.remove('dsh-mobile-drawer');
      return;
    }
    var col = document.querySelector('div[class*="_sidebarCol"]');
    var open = false;
    if (col) {
      // Collapsed state sidebarCol is display:none via our CSS (width 0), cannot check width;
      // dsh adds _collapsed class to inner root node when collapsed, use this as standard.
      open = !col.querySelector('[class*="_collapsed"]');
    }
    document.body.classList.toggle('dsh-mobile-drawer', open);
    if (open) ensureMask();
    if (window.innerWidth <= 700) ensureRailBtn();
  }

  // Token/time statistics bar tagging (class names are build hashes, identify by text):
  //  - Input seat summary bar: "1 turns · 1 steps | LLM … | TTFT avg … tok/s"
  //  - Message timing line: "04:37 · Ran for 12s · TTFT 1s · 169 tok/s" (_timeEnd)
  // Both are single-line nowrap, directly overflowing screen right edge on narrow screens (right edge hangs a bit of "k/s").
  // "|" separators between segments are also tagged—after line break, isolated "|" hangs at line end, very disjointed, CSS hides them.
  function tagStatsBar() {
    var els = document.querySelectorAll('div, span');
    var cand = [];
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.classList.contains('dsh-mobile-stats')) continue;
      var t = el.textContent || '';
      if (t.length < 8 || t.length > 200) continue;
      if (el.querySelector('textarea, input, button, [class*="_card"]')) continue;
      var composerStats = /turns|steps/.test(t) && /tok\/s|TTFT|LLM/.test(t);
      var msgStats = t.length < 80 && /Ran for|TTFT/.test(t) && /tok\/s/.test(t);
      if (composerStats || msgStats) cand.push(el);
    }
    // Only tag innermost matchers: outer containers (entire message bubble) just "contain" stats text,
    // tagging them makes all spans inside become inline-block nowrap, breaking body layout.
    for (var c = 0; c < cand.length; c++) {
      var innermost = true;
      for (var d = 0; d < cand.length; d++) {
        if (d !== c && cand[c].contains(cand[d])) { innermost = false; break; }
      }
      if (innermost) cand[c].classList.add('dsh-mobile-stats');
    }
    var bars = document.querySelectorAll('.dsh-mobile-stats');
    for (var b = 0; b < bars.length; b++) {
      var kids = bars[b].querySelectorAll('*');
      for (var k = 0; k < kids.length; k++) {
        if ((kids[k].textContent || '').trim() === '|') {
          kids[k].classList.add('dsh-mobile-stats-sep');
        }
      }
    }
  }

  // Popover horizontal clamping: dsh desktop-positioned popovers (menus/bubbles, e.g., model selector right:0 aligned
  // to trigger right edge) get clipped off left/right edges on mobile narrow screens. When out of bounds, rewrite left/right to pull back into screen;
  // dsh re-rendering resets inline positioning, clamp again next time out of bounds, no accumulation.
  function clampPopovers() {
    if (window.innerWidth > 700) return;
    var vw = window.innerWidth;
    // Note: _popover/_floating class names do not exist in rc.6/rc.7 DOM (issue #5),
    // tooltip bubbles use [role="tooltip"] matching instead of class names
    var els = document.querySelectorAll(
      'div[class*="_menu"], div[class*="_dropdown"], [role="tooltip"]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var cs = window.getComputedStyle(el);
      if (cs.position !== 'absolute' && cs.position !== 'fixed') continue;
      var r = el.getBoundingClientRect();
      if (r.width < 20 || r.height < 10) continue;
      var op = el.offsetParent ? el.offsetParent.getBoundingClientRect() : { left: 0, right: vw };
      if (r.left < 6) {
        el.style.right = 'auto';
        el.style.left = Math.round(6 - op.left) + 'px';
      } else if (r.right > vw - 6) {
        el.style.left = 'auto';
        el.style.right = Math.round(op.right - (vw - 6)) + 'px';
      }
    }
  }

  // Tooltip auto-hide: dsh's Tooltip component pops up on mouseenter/focus,
  // disappears on mouseleave/blur—touch tapping synthesizes mouseenter/focus to pop up bubble,
  // but requires another tap elsewhere to disappear (sticks "command" black block on Bash line, stats bar pops up full-text
  // large black box covering input area). Bubbles are allowed to pop up, but auto-hide after 2.5 seconds.
  // Only add inline display:none, do not delete DOM nodes: nodes are unloaded by React on anchor
  // mouseleave/blur, deleting nodes would desynchronize with React rendering.
  var TIP_AUTOHIDE_MS = 2500;
  function armTooltipAutohide() {
    var tips = document.querySelectorAll('[role="tooltip"]:not([data-dsh-tip-armed])');
    for (var i = 0; i < tips.length; i++) {
      var tip = tips[i];
      tip.setAttribute('data-dsh-tip-armed', '1');
      (function (el) {
        setTimeout(function () {
          el.style.setProperty('display', 'none', 'important');
        }, TIP_AUTOHIDE_MS);
      })(tip);
    }
  }

  // Whitespace at bottom of message flow = actual height of input seat + 16px gap.
  // Background: input seat is pinned to viewport bottom as fixed by mobile.css (out of document flow), message flow
  // end needs equivalent whitespace to scroll to "last message above seat".
  // Historical lessons (do not revert):
  //  - Old version wrote whitespace as scrollBody's padding-bottom: sticky seat's anchor point gets pushed up by
  //    scroll container's own padding, the larger the padding the higher the seat floats; and measurement uses
  //    sc.bottom - seat.top, seat.top moves with padding → positive feedback loop out of control,
  //    composer drifts to page middle when seat height changes during runtime.
  //  - Inline writing of padding-bottom without !important gets overridden by mobile.css's
  //    !important fallback rules, dynamic compensation becomes ineffective.
  // Current solution: append independent spacer block to end of scrollBody (in-flow, height takes effect stably),
  // measurement only takes seat "height" (changes with content, not with whitespace/positioning), cutting off feedback loop.
  // Composer summary stats bar ("N turns · N steps | LLM …", tagged by tagStatsBar as
  // .dsh-mobile-stats and not inside message _flowItem) in some dsh versions is not inside
  // _composerSeat, but an independent node in document flow below seat—after seat becomes fixed, it
  // gets covered by seat or falls outside viewport (real device screenshot: stats line sticks to screen bottom edge, half clipped).
  // Pin it to viewport bottom (CSS .dsh-mobile-composer-stats), seat's bottom moves up by bar height. Return bar height (return 0 if inside seat/non-existent). Measurement only takes "height", stable without feedback.
  var pinnedStats = null;
  function pinComposerStats(comp) {
    var bar = null;
    var bars = document.querySelectorAll('div[class*="_centerCol"] .dsh-mobile-stats');
    for (var i = 0; i < bars.length; i++) {
      var b = bars[i];
      if (comp.contains(b)) continue;
      if (b.closest('div[class*="_flowItem"]')) continue;
      // Bar root may be repeatedly tagged (outer container is also .dsh-mobile-stats), take outermost
      while (b.parentElement && b.parentElement.classList &&
        b.parentElement.classList.contains('dsh-mobile-stats') &&
        !comp.contains(b.parentElement)) {
        b = b.parentElement;
      }
      bar = b;
      break;
    }
    if (pinnedStats && pinnedStats !== bar) {
      pinnedStats.classList.remove('dsh-mobile-composer-stats');
      pinnedStats = null;
    }
    if (!bar) {
      comp.style.removeProperty('bottom');
      return 0;
    }
    pinnedStats = bar;
    bar.classList.add('dsh-mobile-composer-stats');
    var h = Math.round(bar.getBoundingClientRect().height);
    comp.style.setProperty('bottom', h + 'px', 'important');
    return h;
  }

  var lastPad = -1;
  var spacerEl = null;
  function fitComposerOverlap() {
    var comp = document.querySelector('div[class*="_centerCol"] div[class*="_composerSeat"]');
    var sc = document.querySelector('div[class*="_centerCol"] [class*="_scrollBody"]');
    if (!comp || !sc) return;
    // Seat on new conversation hero page stays in document flow (not pinned to bottom), no compensation needed;
    // Clear old spacer/pinned stats when navigating from session page.
    if (comp.querySelector('div[class*="_composerHero"]')) {
      if (spacerEl && spacerEl.isConnected) spacerEl.remove();
      if (pinnedStats) {
        pinnedStats.classList.remove('dsh-mobile-composer-stats');
        pinnedStats = null;
      }
      lastPad = -1;
      return;
    }
    if (!spacerEl || !spacerEl.isConnected || spacerEl.parentElement !== sc) {
      spacerEl = document.getElementById('dsh-mobile-composer-spacer');
      if (!spacerEl) {
        spacerEl = document.createElement('div');
        spacerEl.id = 'dsh-mobile-composer-spacer';
        spacerEl.setAttribute('aria-hidden', 'true');
        // scrollBody is flex column, prevent shrinking (mobile.css has same rule, double insurance here)
        spacerEl.style.flex = '0 0 auto';
      }
      sc.appendChild(spacerEl);
    }
    // dsh re-rendering may insert new nodes at end of scrollBody (e.g., stats bar outside seat),
    // spacer must remain last child, otherwise new content falls below spacer and gets clipped
    if (sc.lastElementChild !== spacerEl) sc.appendChild(spacerEl);
    var statsH = pinComposerStats(comp);
    var want = Math.max(48, Math.round(comp.getBoundingClientRect().height) + statsH + 16);
    if (want !== lastPad) {
      lastPad = want;
      spacerEl.style.height = want + 'px';
      sc.style.scrollPaddingBottom = want + 'px';
    }
  }

  // Intercept "Open configuration file": desktop version calls settings.openDocument →
  // container lacks xdg-open/editor, inevitably reports "Cannot open configuration file". Switch to native bridge
  // (DshNative.openConfig → ConfigEditorActivity directly edits settings.yaml in container,
  // dsh-settings-file watcher hot-reloads, no restart needed).
  // Capture phase interception: React 17+ events hang on root container, document capture phase
  // stopPropagation can block original handler. Allow original logic if bridge does not exist (dev/debug).
  document.addEventListener('click', function (ev) {
    var t = ev.target;
    if (!t || !t.closest) return;
    var btn = t.closest('button, [role="button"]');
    if (!btn) return;
    var txt = (btn.textContent || '').trim();
    if (!/打开配置文件|Open configuration file/i.test(txt)) return;
    if (!window.DshNative || !window.DshNative.openConfig) return;
    ev.preventDefault();
    ev.stopPropagation();
    window.DshNative.openConfig();
  }, true);

  // Plugin page "Add plugin": dsh web only has plugin config/view, no installation entry
  // (desktop installation goes through CLI: dsh plugin --profile web add <pkg>, forwards to pnpm).
  // Inject input+button row at bottom of plugin section, run same CLI in container via native bridge.
  // Result returned asynchronously via window.__dshOnPluginInstallResult (installation requires internet, may take minutes).
  function ensurePluginAdder() {
    if (!window.DshNative || !window.DshNative.installPlugin) return;
    var dlg = document.querySelector('div[role="dialog"], div[class*="_dialog"]');
    if (!dlg) return;
    var heads = dlg.querySelectorAll('[class*="_heading"]');
    var sec = null;
    for (var i = 0; i < heads.length; i++) {
      var t = (heads[i].textContent || '').trim();
      if (t === '插件' || t === 'Plugins') {
        sec = heads[i].closest('div[class*="_section"]') || heads[i].parentElement;
        break;
      }
    }
    if (!sec || document.getElementById('dsh-mobile-plugin-adder')) return;

    var row = document.createElement('div');
    row.id = 'dsh-mobile-plugin-adder';
    var input = document.createElement('input');
    input.type = 'text';
    input.placeholder = 'npm package name, e.g., dsh-plugin-xxx';
    input.setAttribute('autocapitalize', 'off');
    input.setAttribute('autocorrect', 'off');
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = 'Add plugin';
    var statusEl = document.createElement('div');
    statusEl.className = 'dsh-mobile-plugin-adder-status';
    btn.addEventListener('click', function () {
      var name = input.value.trim();
      if (!name) {
        statusEl.textContent = 'Please enter plugin package name';
        return;
      }
      btn.disabled = true;
      input.disabled = true;
      statusEl.textContent = 'Installing… (first install requires downloading pnpm, may take a few minutes)';
      window.__dshOnPluginInstallResult = function (res) {
        btn.disabled = false;
        input.disabled = false;
        if (res && res.ok) {
          statusEl.textContent = 'Installed: ' + name + '. Go to App Settings page and click "Restart Service" to take effect.';
          input.value = '';
        } else {
          statusEl.textContent = 'Installation failed:\n' + ((res && res.output) || 'Unknown error').slice(-800);
        }
      };
      try {
        window.DshNative.installPlugin(name);
      } catch (e) {
        btn.disabled = false;
        input.disabled = false;
        statusEl.textContent = 'Native bridge call failed: ' + e;
      }
    });
    row.appendChild(input);
    row.appendChild(btn);
    row.appendChild(statusEl);
    sec.appendChild(row);
  }

  var scheduled = false;
  var observer = new MutationObserver(function () {
    if (scheduled) return;
    scheduled = true;
    setTimeout(function () {
      scheduled = false;
      ensureViewport();
      updateDetailsCol();
      updateSidebarDrawer();
      tagStatsBar();
      clampPopovers();
      fitComposerOverlap();
      ensurePluginAdder();
      armTooltipAutohide();
    }, 300);
  });
  observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
  ensureViewport();
  updateDetailsCol();
  updateSidebarDrawer();
  tagStatsBar();
  clampPopovers();
  fitComposerOverlap();
  ensurePluginAdder();
  armTooltipAutohide();
  window.addEventListener('resize', updateSidebarDrawer);

  // Automatically close drawer after clicking session/workspace row (_sessionRow etc.) in drawer.
  // dsh desktop behavior is to select without collapsing sidebar, on mobile not collapsing means session content is invisible.
  // Continuously enforce closing within 6-second window: dsh may re-expand sidebar based on persisted state after navigation,
  // re-expansion on slow-rendering real devices may drag to 2-3 seconds after navigation ends, clicking toggle once easily fails.
  // Do not rely on body's drawer class (has debounce lag), effective when clicking inside sidebar.
  document.addEventListener('click', function (ev) {
    var t = ev.target;
    if (!t || !t.closest) return;
    var col = t.closest('div[class*="_sidebarCol"]');
    if (!col) return;
    // ⋯ button in row and popped-up menu are not "selecting row": must never trigger forced close—
    // otherwise menu just opened, drawer gets closed, menu unmounted, archive/rename never clickable,
    // manifestation is "click archive immediately returns to chat interface but not archived" (issue #1)
    if (t.closest('[class*="_rowActions"], [class*="_menu"], [role="menu"], [role="menuitem"], [role="dialog"]')) {
      return;
    }
    if (t.closest('[class*="_sessionRow"], [class*="_workspaceRow"], [class*="_projectRow"], [class*="_newSession"], [data-slot^="sidebar.session"]')) {
      enforceClosed(Date.now() + 6000);
    }
  }, true);

  // If sidebar is in persisted expanded state at startup, actively close it—otherwise entering App shows
  // drawer+mask covering face, entire interface looks like covered with black gauze (all clicks blocked by mask).
  // Once user actively opens drawer, cancel startup fallback to avoid competing with user.
  // Note must use closest: click target is usually <svg>/<line> child node inside button,
  // direct id comparison will miss, startup fallback still closes drawer user just opened ("clicked then closed itself").
  var startupEnforce = true;
  document.addEventListener('click', function (ev) {
    if (ev.target && ev.target.closest && ev.target.closest('#dsh-mobile-railbtn, #dsh-mobile-drawer-mask')) {
      startupEnforce = false;
    }
  }, true);
  setTimeout(function () { if (startupEnforce) enforceClosed(Date.now() + 1500); }, 1200);
  setTimeout(function () { if (startupEnforce) enforceClosed(Date.now() + 2500); }, 3500);

  // Gesture trigger: swipe right from screen left edge to call out drawer; swipe left on drawer/mask to close when drawer is open.
  // Only judge on touchend (passive listener, does not interrupt page scrolling): only trigger with clear horizontal displacement
  // (|dx|>64 and |dy|<=50), vertical scrolling/diagonal swipe not mis-triggered.
  var drawerTouch = null;
  document.addEventListener('touchstart', function (ev) {
    drawerTouch = null;
    if (window.innerWidth > 700) return;
    var t = ev.touches[0];
    if (!t) return;
    if (document.body.classList.contains('dsh-mobile-drawer')) {
      if (ev.target && ev.target.closest && ev.target.closest('div[class*="_sidebarCol"], #dsh-mobile-drawer-mask')) {
        drawerTouch = { x: t.clientX, y: t.clientY, close: true };
      }
    } else if (t.clientX <= 24) {
      drawerTouch = { x: t.clientX, y: t.clientY, close: false };
    }
  }, { passive: true });
  document.addEventListener('touchend', function (ev) {
    if (!drawerTouch) return;
    var t = ev.changedTouches[0];
    var dx = t.clientX - drawerTouch.x;
    var dy = t.clientY - drawerTouch.y;
    var close = drawerTouch.close;
    drawerTouch = null;
    if (Math.abs(dy) > 50) return;
    if (!close && dx > 64) openSidebar(2);
    if (close && dx < -64) closeSidebar();
  }, { passive: true });

  // Fallback self-check: observer only runs on DOM changes, if page is static after some race condition,
  // mask/drawer state may get stuck—correct derived class every 2 seconds based on dsh's actual state.
  setInterval(function () { updateSidebarDrawer(); fitComposerOverlap(); }, 2000);

  // Scroll focused input into visible area when soft keyboard pops up
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', function () {
      var el = document.activeElement;
      if (el && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT' || el.isContentEditable)) {
        setTimeout(function () {
          try { el.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); } catch (e) {}
        }, 120);
      }
    });
  }
  // Note: Do not intercept touchend here to prevent double-tap zoom—viewport already set maximum-scale=1 +
  // user-scalable=no, system level has disabled double-tap zoom; extra preventDefault will swallow second click
  // of rapid consecutive taps, causing button "unresponsive".
})();
