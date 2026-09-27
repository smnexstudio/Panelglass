// Panelglass "Translate page": the page's own text, translated in place. Injected with mt.js. The app pulls and
// pushes through evaluateJavascript only (there is no JavaScript interface), so a page cannot call into the app.
//   app → JS : __pt.sample()          {text, lang}: up to 2000 chars of visible text, and <html lang>
//   app → JS : __pt.collect(max)      [{id, text}]: text not translated yet, on screen first, up to max chars;
//                                     starts watching the page for new text (infinite scroll, lazy lists)
//   app → JS : __pt.apply(items, pad) [{id, text}]: writes the translations in (originals kept)
//   app → JS : __pt.restore()         puts every original back and stops watching
(function () {
  if (window.__pt) return;
  var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, INPUT: 1, SELECT: 1, OPTION: 1, CODE: 1, PRE: 1,
               SVG: 1, CANVAS: 1, IFRAME: 1, TEMPLATE: 1, KBD: 1, SAMP: 1 };
  // Something to translate: any character that is not space, a digit, ASCII/CJK/full-width punctuation or a symbol.
  var WORD = /[^\s\d!-\/:-@\[-`{-~\u2000-\u206f\u2190-\u2bff\u3000-\u303f\uff01-\uff0f\uff1a-\uff20]/;
  var records = [];   // id -> {node, original, sent, shown}: sent = handed to the app, shown = our text
  var queue = null;   // candidate ids from the last walk, on-screen first
  var dirty = true;
  var observer = null;

  function skipped(el) {
    for (; el && el !== document.body; el = el.parentElement) {
      if (SKIP[el.tagName.toUpperCase()] || el.isContentEditable) return true;
      if (el.getAttribute('translate') === 'no' || el.classList.contains('notranslate')) return true;
    }
    return false;
  }

  function onScreen(el, vh) {
    var r = el.getBoundingClientRect();
    return r.bottom > 0 && r.top < vh && r.width > 0;
  }

  // Text nodes worth translating that are not translated yet (or that the page rewrote since).
  function walk() {
    var first = [], later = [];
    if (!document.body) return first;
    var vh = window.innerHeight;
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) {
      var id = n.__ptId;
      if (id !== undefined) {
        var rec = records[id];
        if (rec.shown === null ? rec.sent : n.nodeValue === rec.shown) continue;  // in flight, or showing ours
        if (rec.shown !== null) { rec.original = n.nodeValue; rec.shown = null; rec.sent = false; }  // page changed it
      }
      if (!WORD.test(n.nodeValue) || !n.parentElement || skipped(n.parentElement)) continue;
      var r = n.parentElement.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) continue;                     // hidden; picked up once it shows
      if (id === undefined) {
        id = records.length;
        n.__ptId = id;
        records.push({ node: n, original: n.nodeValue, sent: false, shown: null });
      }
      (onScreen(n.parentElement, vh) ? first : later).push(id);
    }
    return first.concat(later);
  }

  // Whether text runs on from node in that direction on the same line: a sibling, or (inside an inline element such
  // as a link) the element's own sibling.
  function inlineNeighbour(node, dir) {
    for (var n = node; n && n !== document.body; n = n.parentElement) {
      var sib = n[dir];
      while (sib && sib.nodeType === 3 && !sib.nodeValue.trim()) sib = sib[dir];
      if (sib) return sib.nodeType === 3 || (sib.nodeType === 1 && getComputedStyle(sib).display.indexOf('inline') === 0);
      var parent = n.parentElement;
      if (!parent || getComputedStyle(parent).display.indexOf('inline') !== 0) return false;
    }
    return false;
  }

  function watch() {
    if (observer || !document.body) return;
    observer = new MutationObserver(function () { dirty = true; });
    observer.observe(document.body, { childList: true, subtree: true, characterData: true });
  }

  window.__pt = {
    sample: function () {
      var text = '', w = document.body ? document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null) : null;
      var n;
      while (w && text.length < 2000 && (n = w.nextNode())) {
        if (n.parentElement && WORD.test(n.nodeValue) && !skipped(n.parentElement)) text += n.nodeValue.trim() + '\n';
      }
      return { text: text.slice(0, 2000), lang: document.documentElement.lang || '' };
    },

    collect: function (max) {
      watch();
      if (dirty || queue === null) { queue = walk(); dirty = false; }
      var out = [], chars = 0;
      while (queue.length && (out.length === 0 || chars + records[queue[0]].original.length <= max)) {
        var rec = records[queue.shift()];
        if (!rec.node.isConnected || rec.sent) continue;
        rec.sent = true;
        var t = rec.original.trim();
        chars += t.length;
        out.push({ id: rec.node.__ptId, text: t });
      }
      return out;
    },

    // pad: the source is written without spaces (Japanese, Chinese, Thai), so a link and the words around it are
    // separate nodes with nothing between them; in the translation they need a space, or they run together.
    apply: function (items, pad) {
      for (var i = 0; i < items.length; i++) {
        var rec = records[items[i].id];
        if (!rec || !rec.node.isConnected || rec.node.nodeValue !== rec.original) continue;
        var text = items[i].text;
        var lead = rec.original.match(/^\s*/)[0], trail = rec.original.match(/\s*$/)[0];
        if (pad) {
          if (!lead && inlineNeighbour(rec.node, 'previousSibling') && /^[\w(“"'«]/.test(text)) lead = ' ';
          if (!trail && inlineNeighbour(rec.node, 'nextSibling') && /[\w)”"'».,;:!?]$/.test(text)) trail = ' ';
        }
        rec.shown = lead + text + trail;
        rec.node.nodeValue = rec.shown;
      }
    },

    restore: function () {
      if (observer) { observer.disconnect(); observer = null; }
      for (var i = 0; i < records.length; i++) {
        var rec = records[i];
        if (rec.shown !== null && rec.node.nodeValue === rec.shown) rec.node.nodeValue = rec.original;
        delete rec.node.__ptId;
      }
      records = []; queue = null; dirty = true;
    }
  };
})();
