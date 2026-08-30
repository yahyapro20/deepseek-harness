/* Legacy WebView compatibility polyfill: must be injected before dsh's own scripts might call it
   (onPageStarted). Only include minimal patches without DOM dependencies.
   Background: Common system WebViews on Android 12 and below (Chrome < 116) lack
   AbortSignal.any/timeout, causing dsh workspace selectors to throw
   "AbortSignal.any is not a function" (issue #2/#4). */
(function () {
  'use strict';
  if (window.__dshEarlyPolyfill) return;
  window.__dshEarlyPolyfill = true;
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

  // Other new APIs used by the dsh bundle (old WebViews crash immediately upon calling them, one of the root causes of issue #4 crashes)
  if (typeof Promise.withResolvers !== 'function') {
    Promise.withResolvers = function () {
      var resolve, reject;
      var promise = new Promise(function (res, rej) { resolve = res; reject = rej; });
      return { promise: promise, resolve: resolve, reject: reject };
    };
  }
  if (!Array.prototype.findLast) {
    Array.prototype.findLast = function (fn, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) {
        if (fn.call(thisArg, this[i], i, this)) return this[i];
      }
      return undefined;
    };
  }
  if (!Array.prototype.findLastIndex) {
    Array.prototype.findLastIndex = function (fn, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) {
        if (fn.call(thisArg, this[i], i, this)) return i;
      }
      return -1;
    };
  }
  if (!Array.prototype.toReversed) {
    Array.prototype.toReversed = function () {
      return this.slice().reverse();
    };
  }
  if (!Array.prototype.toSpliced) {
    Array.prototype.toSpliced = function (start, deleteCount) {
      var copy = this.slice();
      copy.splice.apply(copy, [start, deleteCount].concat(Array.prototype.slice.call(arguments, 2)));
      return copy;
    };
  }
  if (typeof Object.groupBy !== 'function') {
    Object.groupBy = function (items, fn) {
      var out = {};
      for (var i = 0; i < items.length; i++) {
        var k = fn(items[i], i);
        if (!Object.prototype.hasOwnProperty.call(out, k)) out[k] = [];
        out[k].push(items[i]);
      }
      return out;
    };
  }
  if (typeof Map.groupBy !== 'function') {
    Map.groupBy = function (items, fn) {
      var out = new Map();
      for (var i = 0; i < items.length; i++) {
        var k = fn(items[i], i);
        if (!out.has(k)) out.set(k, []);
        out.get(k).push(items[i]);
      }
      return out;
    };
  }
})();
