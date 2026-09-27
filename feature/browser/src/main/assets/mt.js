// Panelglass in-page bridge. Injected at onPageStarted; start() runs at onPageFinished.
// Surface (keep it this small):
//   app → JS : __mt.start()              one cosmetic pass hiding full-screen interstitials
//   app → JS : __mt.viewportMap()        {images:[{x,y,w,h,k}], overlays:[{x,y,w,h}], pending:n} in CSS px, viewport-relative
//                                        (k: the image's URL, so a patch can stay on its image when the layout moves):
//                                        where comic pixels are, and which controls float above them (fixed/sticky
//                                        ones, and drawn elements a reader lays on top of the art)
// Screen translation draws its patches natively over the WebView; nothing is written into the page.
(function () {
  if (window.__mt) return;
  var started = false;

  // Popunders and redirect ads live on window.open; comic sites need neither.
  // Locked, so a script cannot put it back; a new window that still gets through (target=_blank, a fresh iframe's
  // own open) is decided natively (NewWindows).
  try {
    Object.defineProperty(window, 'open', { value: function () { return null; }, writable: false, configurable: false });
  } catch (e) { try { window.open = function () { return null; }; } catch (e2) {} }

  // Whether el holds the comic itself: a large image or canvas of its own (not one inside an ad iframe). A reader
  // that shows its pages in a full-screen fixed layer must never be hidden as an "overlay".
  function holdsArt(el, vw, vh) {
    var media = el.querySelectorAll('img,canvas');
    for (var i = 0; i < media.length; i++) {
      var r = media[i].getBoundingClientRect();
      if (r.width * r.height > vw * vh * 0.2) return true;
    }
    return false;
  }

  function transparent(cs) {
    return !cs.backgroundColor || cs.backgroundColor === 'rgba(0, 0, 0, 0)' || cs.backgroundColor === 'transparent' ||
      parseFloat(cs.opacity) < 0.05;
  }

  // Cosmetic pass for fixed-position layers over most of the viewport:
  //   interstitials (an ad iframe, or a high z-index box that is not the comic), and
  //   tap-catchers (an empty, invisible layer whose only job is to send the first tap to an ad).
  // Hiding one also lifts the scroll lock such layers put on the page.
  // roots: the elements to look at (with their descendants); the whole body when omitted.
  function hideOverlays(roots) {
    var hid = false;
    try {
      var all = [];
      if (!roots) all = document.body ? document.body.getElementsByTagName('*') : [];
      else roots.forEach(function (n) {
        if (n.nodeType !== 1) return;
        all.push(n);
        var d = n.getElementsByTagName('*');
        for (var j = 0; j < d.length; j++) all.push(d[j]);
      });
      var vw = window.innerWidth, vh = window.innerHeight;
      for (var i = 0; i < all.length; i++) {
        var el = all[i];
        var cs = window.getComputedStyle(el);
        if (cs.position !== 'fixed' || cs.display === 'none') continue;
        var r = el.getBoundingClientRect();
        if (r.width * r.height < vw * vh * 0.6) continue;
        if (holdsArt(el, vw, vh)) continue;
        var z = parseInt(cs.zIndex, 10) || 0;
        var isAd = el.tagName === 'IFRAME' || el.getElementsByTagName('iframe').length > 0;
        var empty = (el.textContent || '').trim().length === 0 && !el.querySelector('img,svg,canvas,video,input,button');
        var catcher = z > 0 && transparent(cs) && empty;
        if (isAd || z > 1000 || catcher) { el.style.setProperty('display', 'none', 'important'); hid = true; }
      }
      if (hid) {
        [document.documentElement, document.body].forEach(function (n) {
          if (n && window.getComputedStyle(n).overflowY === 'hidden') n.style.setProperty('overflow', 'auto', 'important');
        });
      }
    } catch (e) {}
  }

  // Interstitials often arrive seconds after load: for the first minute, look at what gets added to the page (only
  // that, batched per 500 ms, at most 40 passes) so a long chapter is not rescanned on every change.
  function watchOverlays() {
    if (!window.MutationObserver || !document.body) return;
    var added = [], passes = 0;
    var mo = new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var nodes = records[i].addedNodes;
        for (var j = 0; j < nodes.length; j++) if (nodes[j].nodeType === 1) added.push(nodes[j]);
      }
      if (added.length === 0 || added.pending) return;
      added.pending = true;
      setTimeout(function () {
        var batch = added.slice(); added.length = 0; added.pending = false;
        hideOverlays(batch);
        if (++passes >= 40) mo.disconnect();
      }, 500);
    });
    mo.observe(document.body, { childList: true, subtree: true });
    setTimeout(function () { mo.disconnect(); }, 60000);
  }

  window.__mt = {
    start: function () {
      if (started) return; started = true;
      hideOverlays();
      watchOverlays();
    },
    // The parts of the viewport that are page images, and the DOM controls floating over them.
    // Nothing outside an image rect is comic art, and nothing under an overlay rect is either.
    viewportMap: function () {
      var vw = window.innerWidth, vh = window.innerHeight;
      var images = [], overlays = [], artEls = [], pending = 0;
      function rect(r, key) {
        var o = { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) };
        if (key) o.k = key;
        return o;
      }
      // What a page image is, whatever the layout does around it: its URL (a canvas: its place among canvases).
      function keyOf(el, n) {
        return el.tagName === 'IMG' ? (el.currentSrc || el.src || '') : 'canvas#' + n;
      }
      function visible(r) { return r.width > 0 && r.height > 0 && r.right > 0 && r.bottom > 0 && r.left < vw && r.top < vh; }
      function overArt(r) {
        for (var a = 0; a < images.length; a++) {
          var q = images[a];
          if (r.left < q.x + q.w && r.right > q.x && r.top < q.y + q.h && r.bottom > q.y) return true;
        }
        return false;
      }
      function holdsPage(el) {
        for (var a = 0; a < artEls.length; a++) if (el === artEls[a] || el.contains(artEls[a])) return true;
        return false;
      }
      // Watermark text (a reader ID printed across the art) is see-through: it is not a control.
      function faint(cs) {
        var m = /rgba\([^)]*,\s*([\d.]+)\)/.exec(cs.color || '');
        return parseFloat(cs.opacity) < 0.5 || (!!m && parseFloat(m[1]) < 0.5);
      }
      function ownText(el) {
        for (var n = el.firstChild; n; n = n.nextSibling) if (n.nodeType === 3 && n.nodeValue.trim().length > 0) return true;
        return false;
      }
      // Whether el is what the user sees at its own centre, i.e. it is drawn on top of the art there.
      function onTop(el, r) {
        var x = Math.min(vw - 1, Math.max(0, r.left + r.width / 2)), y = Math.min(vh - 1, Math.max(0, r.top + r.height / 2));
        var hit = document.elementFromPoint(x, y);
        return !!hit && (hit === el || el.contains(hit));
      }
      try {
        var media = document.querySelectorAll('img,canvas'), canvasN = 0;
        for (var i = 0; i < media.length; i++) {
          var r = media[i].getBoundingClientRect();
          var key = keyOf(media[i], media[i].tagName === 'CANVAS' ? canvasN++ : 0);
          if (visible(r) && r.width >= 96 && r.height >= 96) {
            images.push(rect(r, key)); artEls.push(media[i]);
            // A page still downloading is painted partly or not at all: capturing it reads half a caption.
            if (media[i].tagName === 'IMG' && !(media[i].complete && media[i].naturalWidth > 0)) pending++;
          }
        }
        // Controls floating over the art are small and drawn; full-size fixed wrappers (ad slots, drawers,
        // transparent containers) are neither and must not blank the page.
        var all = document.body ? document.body.getElementsByTagName('*') : [];
        for (var k = 0; k < all.length; k++) {
          var el = all[k];
          var cs = window.getComputedStyle(el);
          var pinned = cs.position === 'fixed' || cs.position === 'sticky';
          // A reader drawn in a full-screen fixed layer lays its bars over the page with absolute positioning, so
          // a drawn element on top of the art counts too, whatever its position (it is never the art itself).
          if (!pinned && (images.length === 0 || cs.position === 'static')) {
            if (images.length === 0 || !ownText(el)) continue;
          }
          if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) continue;
          var er = el.getBoundingClientRect();
          if (!visible(er) || er.width * er.height > vw * vh * 0.35) continue;
          if (pinned) {
            var drawn = !transparent(cs) || el.querySelector('img,svg,canvas,button,input') || (el.textContent || '').trim().length > 0;
            if (drawn) overlays.push(rect(er));
          } else if (overArt(er) && !holdsPage(el) && (!transparent(cs) || (ownText(el) && !faint(cs))) && onTop(el, er)) {
            overlays.push(rect(er));
          }
        }
      } catch (e) {}
      return JSON.stringify({ images: images, overlays: overlays, pending: pending });
    }
  };
})();
