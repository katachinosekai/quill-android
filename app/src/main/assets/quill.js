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
    '.quill-arrow:active{box-shadow:inset 0 0 0 100px rgba(217,119,87,.4)}',
    'pre{background:#141413!important;border-radius:8px!important;padding:10px 12px!important}',
    '[data-testid=user-message]{background:#141413!important;border-radius:8px!important}',
    'textarea,[contenteditable=true]{caret-color:' + ACCENT + '}',
    '[data-quill-input]{background:#30302e!important;border-color:#444440!important;border-radius:8px!important}',
    '[data-quill-panel] [aria-current=page],[data-quill-panel] [aria-current=true],nav [aria-current=page],aside [aria-current=page]{background:transparent!important;outline:1.5px solid #eceae3!important;outline-offset:-1.5px;border-radius:8px!important;font-weight:700!important}',
    '*{scrollbar-width:thin;scrollbar-color:#444440 transparent}'
  ].join('\n');
  var CENTER_CHAT = [
    'main p,main li,main h1,main h2,main h3,main h4,main h5,main blockquote,main [data-testid=user-message],main [data-testid=user-message] *{text-align:center!important}',
    'main ul,main ol{list-style-position:inside!important;padding-left:0!important}',
    'main pre,main pre *,main table,main table *,main details *{text-align:left!important}',
    'main pre{margin-left:auto!important;margin-right:auto!important}'
  ].join('\n');
  var CENTER_INPUT = 'textarea,[contenteditable=true],[contenteditable=true] *{text-align:center!important}';
  var CENTER_STATUS = '[role=status],[aria-live=polite],[aria-live=assertive]{text-align:center!important;justify-content:center!important}';

  function pct(v) {
    v = Number(v);
    return v > 0 && v < 100 ? v : 100;
  }

  function style() {
    var s = document.getElementById('quill-style');
    if (!s) {
      s = document.createElement('style');
      s.id = 'quill-style';
      (document.head || document.documentElement).appendChild(s);
    } else if (s.parentNode !== document.head && document.head) {
      document.head.appendChild(s);
    }
    var p = Q.prefs, c = p.center || {};
    var parts = [BASE];
    if (c.chat) parts.push(CENTER_CHAT);
    if (c.input) parts.push(CENTER_INPUT);
    if (c.status) parts.push(CENTER_STATUS);
    var cw = pct(p.chatWidth), iw = pct(p.inputWidth);
    if (cw < 100) parts.push('main{padding-left:calc((100% - ' + cw + '%) / 2)!important;padding-right:calc((100% - ' + cw + '%) / 2)!important}');
    if (iw < 100) parts.push('[data-quill-input]{width:' + iw + '%!important;max-width:100%!important;margin-left:auto!important;margin-right:auto!important}');
    var css = parts.join('\n');
    if (s.textContent !== css) s.textContent = css;
  }

  function tagInput() {
    document.querySelectorAll('textarea,[contenteditable=true]').forEach(function (el) {
      var box = el.closest('fieldset') || el.closest('form');
      if (box && !box.hasAttribute('data-quill-input') && box.getBoundingClientRect().width > window.innerWidth * 0.5) {
        box.setAttribute('data-quill-input', '');
      }
    });
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
      if (cs.position === 'fixed' || cs.position === 'absolute') return;
      if (el.closest('[role=dialog],[aria-modal=true]')) return;
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
    tagInput();
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
