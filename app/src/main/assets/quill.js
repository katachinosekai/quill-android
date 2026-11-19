(function () {
  var prefs = window.__quillPrefs || {};
  if (window.__quill) {
    window.__quill.setPrefs(prefs);
    return;
  }
  var Q = { prefs: prefs };
  window.__quill = Q;

  var ACCENT = '#d97757';
  var BASE = [
    ':root{color-scheme:dark;--quill-accent:' + ACCENT + '}',
    'html{-webkit-text-size-adjust:100%!important;text-size-adjust:100%!important}',
    'body{-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility}',
    'p,li,blockquote{line-height:1.65!important}',
    'li{margin-top:.25em!important;margin-bottom:.25em!important}',
    'h1,h2,h3,h4{line-height:1.3!important}',
    'pre,pre code{white-space:pre-wrap!important;word-break:break-word!important;overflow-wrap:anywhere!important;font-size:.88em!important;line-height:1.5!important}',
    ':not(pre)>code{overflow-wrap:anywhere!important}',
    'table{display:block;overflow-x:auto;max-width:100%}',
    'img,video{max-width:100%;height:auto}',
    'textarea,input,[contenteditable=true]{font-size:max(16px,1em)!important;line-height:1.5!important}',
    'a{color:' + ACCENT + '}',
    '::selection{background:rgba(217,119,87,.4)}',
    '*{-webkit-tap-highlight-color:rgba(217,119,87,.25)}',
    '[data-quill-panel]{transition:transform .18s ease-in-out,opacity .18s ease-in-out}',
    '[data-quill-panel][data-quill-sliding=left]{transform:translateX(-100%)!important;opacity:0!important}',
    '[data-quill-panel][data-quill-sliding=right]{transform:translateX(100%)!important;opacity:0!important}',
    '[data-quill-panel][data-quill-off]{display:none!important}',
    '.quill-arrow{position:fixed;z-index:2147483646;width:34px;height:34px;border-radius:7px;border:0;padding:0;margin:0;',
    'display:flex;align-items:center;justify-content:center;background:rgba(31,31,29,.85);',
    'box-shadow:inset 0 0 0 100px rgba(217,119,87,.19);color:' + ACCENT + ';touch-action:manipulation}',
    '.quill-arrow svg{width:18px;height:18px;stroke:currentColor;fill:none;stroke-width:2.4;stroke-linecap:round;stroke-linejoin:round}',
    '.quill-arrow:active{box-shadow:inset 0 0 0 100px rgba(217,119,87,.4)}'
  ].join('\n');
  var CENTER = [
    'main p,main li,main h1,main h2,main h3,main h4,main blockquote,[data-testid=user-message],[data-testid=user-message] *{text-align:center!important}',
    'main ul,main ol{list-style-position:inside!important;padding-left:0!important}'
  ].join('\n');

  function style() {
    var s = document.getElementById('quill-style');
    if (!s) {
      s = document.createElement('style');
      s.id = 'quill-style';
      (document.head || document.documentElement).appendChild(s);
    } else if (s.parentNode !== document.head && document.head) {
      document.head.appendChild(s);
    }
    var css = BASE + (Q.prefs.center ? '\n' + CENTER : '');
    if (s.textContent !== css) s.textContent = css;
  }

  var SEL = 'aside,nav,[role=complementary],[role=navigation],[data-testid*=sidebar i],[data-testid*=panel i],[class*=sidebar i],[class*=side-panel i],[class*=drawer i]';

  function stored(side) {
    try { return localStorage.getItem('quill.' + side + 'Off') === '1'; } catch (e) { return false; }
  }
  function store(side, off) {
    try { localStorage.setItem('quill.' + side + 'Off', off ? '1' : '0'); } catch (e) {}
  }

  function panels(side) {
    return Array.prototype.filter.call(document.querySelectorAll('[data-quill-panel=' + side + ']'), function (el) {
      return el.isConnected;
    });
  }

  function scan() {
    var vw = window.innerWidth, vh = window.innerHeight;
    var found = [];
    document.querySelectorAll(SEL).forEach(function (el) {
      if (el.hasAttribute('data-quill-panel') || el.closest('.quill-arrow')) return;
      if (el.parentElement && el.parentElement.closest('[data-quill-panel]')) return;
      var r = el.getBoundingClientRect();
      if (r.width < 48 || r.height < vh * 0.5 || r.width > vw * 0.92) return;
      var cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') return;
      var side = r.left + r.width / 2 > vw / 2 ? 'right' : 'left';
      if (side === 'right' && r.right < vw - 8) return;
      if (side === 'left' && r.left > 8) return;
      found.push({ el: el, side: side, area: r.width * r.height });
    });
    found.sort(function (a, b) { return b.area - a.area; });
    found.forEach(function (f) {
      if (f.el.closest('[data-quill-panel]') || f.el.querySelector('[data-quill-panel]')) return;
      f.el.setAttribute('data-quill-panel', f.side);
      if (stored(f.side)) f.el.setAttribute('data-quill-off', '');
    });
    arrows();
  }

  var ICON = {
    left: '<svg viewBox="0 0 24 24"><polyline points="15 5 8 12 15 19"/></svg>',
    right: '<svg viewBox="0 0 24 24"><polyline points="9 5 16 12 9 19"/></svg>'
  };

  function arrow(side) {
    var id = 'quill-arrow-' + side;
    var b = document.getElementById(id);
    var list = panels(side);
    if (!list.length) {
      if (b) b.remove();
      return;
    }
    if (!b) {
      b = document.createElement('button');
      b.id = id;
      b.type = 'button';
      b.className = 'quill-arrow';
      b.setAttribute('aria-label', side + ' panel');
      b.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        toggle(side);
      }, true);
      document.body.appendChild(b);
    }
    var off = list.every(function (el) { return el.hasAttribute('data-quill-off'); });
    var showIcon = side === 'right' ? (off ? 'left' : 'right') : (off ? 'right' : 'left');
    if (b.getAttribute('data-icon') !== showIcon) {
      b.innerHTML = ICON[showIcon];
      b.setAttribute('data-icon', showIcon);
    }
    var top = 8;
    var r = list[0].getBoundingClientRect();
    if (!off && r.height) top = Math.max(8, r.top + 8);
    b.style.top = top + 'px';
    if (side === 'right') {
      b.style.right = off || !r.width ? '6px' : Math.min(window.innerWidth - 40, r.width + 6) + 'px';
      b.style.left = '';
    } else {
      b.style.left = off || !r.width ? '6px' : Math.min(window.innerWidth - 40, r.width + 6) + 'px';
      b.style.right = '';
    }
  }

  function arrows() {
    arrow('left');
    arrow('right');
  }

  function toggle(side) {
    var list = panels(side);
    var off = list.every(function (el) { return el.hasAttribute('data-quill-off'); });
    store(side, !off);
    list.forEach(function (el) {
      if (off) {
        el.setAttribute('data-quill-sliding', side);
        el.removeAttribute('data-quill-off');
        requestAnimationFrame(function () {
          requestAnimationFrame(function () {
            el.removeAttribute('data-quill-sliding');
            setTimeout(arrows, 200);
          });
        });
      } else {
        el.setAttribute('data-quill-sliding', side);
        setTimeout(function () {
          el.setAttribute('data-quill-off', '');
          el.removeAttribute('data-quill-sliding');
          arrows();
        }, 190);
      }
    });
    arrows();
  }

  var timer = 0;
  function soon() {
    if (timer) return;
    timer = setTimeout(function () {
      timer = 0;
      style();
      scan();
    }, 250);
  }

  Q.setPrefs = function (p) {
    Q.prefs = p || {};
    style();
    soon();
  };
  Q.toggle = toggle;

  function start() {
    style();
    scan();
    new MutationObserver(soon).observe(document.documentElement, { childList: true, subtree: true });
    window.addEventListener('resize', soon);
  }

  if (document.body) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
