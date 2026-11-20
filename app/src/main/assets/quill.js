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
    '[data-quill-hidden]{display:none!important}',
    'aside.dframe-sidebar{display:none!important}',
    '.dframe-root{--df-sidebar-width:0px!important}',
    '.dframe-content-inner{padding-left:0!important}',
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
    var parts = [BASE, DRAWER_CSS];
    if (c.chat) parts.push(CENTER_CHAT);
    if (c.input) parts.push(CENTER_INPUT);
    if (c.status) parts.push(CENTER_STATUS);
    if (p.drawer === false) parts.push('#quill-drawer,#quill-drawer-shade,#quill-drawer-arrow{display:none!important}');
    if (p.edgeArrow === false) parts.push('#quill-drawer-arrow{display:none!important}');
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
      if (el.hasAttribute('data-quill-panel') || el.closest('.quill-arrow,.quill-ui')) return;
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
      f.el.setAttribute('data-quill-off', '');
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
    ['left', 'right'].forEach(function (side) {
      var el = document.getElementById('quill-arrow-' + side);
      if (el) el.remove();
    });
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

  var BUILTIN_TOP = ['Active'];
  var BUILTIN_END = ['Inactive', 'Archive', 'Created by Claude'];
  var OTHER = 'Other';
  var FOLDER_SVG = '<svg viewBox="0 0 24 24"><path d="M3 6.5A1.5 1.5 0 0 1 4.5 5H9l2 2h8.5A1.5 1.5 0 0 1 21 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5z"/></svg>';
  var OPEN_SVG = '<svg viewBox="0 0 24 24"><path d="M3 7.5A1.5 1.5 0 0 1 4.5 6H9l2 2h8.5A1.5 1.5 0 0 1 21 9.5V10H7.2a1.5 1.5 0 0 0-1.4 1L3 18.5z"/><path d="M5.8 11h16l-2.8 8H3z"/></svg>';
  var lastSig = '';

  function sessionLinks() {
    var seen = {}, out = [];
    document.querySelectorAll('a[href*="/code/session_"]').forEach(function (a) {
      if (a.closest('.quill-ui')) return;
      var m = (a.getAttribute('href') || '').match(/\/code\/(session_[A-Za-z0-9]+)/);
      if (!m || seen[m[1]]) return;
      var text = (a.innerText || a.textContent || '').split('\n').map(function (s) { return s.trim(); }).filter(Boolean)[0] || '';
      if (!text) return;
      seen[m[1]] = true;
      var map = Q.prefs.folders || {};
      var folder = Object.prototype.hasOwnProperty.call(map, m[1]) ? String(map[m[1]]) : OTHER;
      out.push({ id: m[1], href: '/code/' + m[1], folder: folder, title: text, link: a });
    });
    return out;
  }

  function folderOrder(list) {
    var given = Q.prefs.order;
    if (given && given.length) {
      var out = given.filter(function (f) { return f !== OTHER; });
      list.forEach(function (s) {
        if (s.folder !== OTHER && out.indexOf(s.folder) < 0) out.push(s.folder);
      });
      out.push(OTHER);
      return out;
    }
    var custom = [];
    list.forEach(function (s) {
      if (BUILTIN_TOP.indexOf(s.folder) < 0 && BUILTIN_END.indexOf(s.folder) < 0 && s.folder !== OTHER && custom.indexOf(s.folder) < 0) custom.push(s.folder);
    });
    return BUILTIN_TOP.concat(custom, BUILTIN_END, [OTHER]);
  }

  function folderOpen(name) {
    try {
      var v = localStorage.getItem('quill.folder.' + name);
      if (v !== null) return v === '1';
    } catch (e) {}
    return name !== 'Archive' && name !== 'Created by Claude';
  }

  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html !== undefined) e.innerHTML = html;
    return e;
  }

  var drawerIsOpen = false;
  function drawerOpen() {
    return drawerIsOpen;
  }

  function setDrawer(open) {
    drawerIsOpen = open;
    var d = document.getElementById('quill-drawer');
    var b = document.getElementById('quill-drawer-shade');
    if (d) {
      d.classList.toggle('open', open);
      d.setAttribute('aria-hidden', open ? 'false' : 'true');
    }
    if (b) b.classList.toggle('open', open);
    drawerArrow();
  }

  function drawerArrow() {
    var a = document.getElementById('quill-drawer-arrow');
    if (!a) return;
    var open = drawerOpen();
    var icon = open ? 'left' : 'right';
    if (a.getAttribute('data-icon') !== icon) {
      a.innerHTML = ICON[icon];
      a.setAttribute('data-icon', icon);
    }
    a.classList.toggle('open', open);
  }

  function sessions() {
    if (!document.body) return;
    var list = sessionLinks();
    var path = location.pathname;
    var sig = path + '|' + list.map(function (s) { return s.id + s.folder + s.title; }).join(';');
    var d = document.getElementById('quill-drawer');
    if (!d) {
      var shade = el('div', 'quill-ui');
      shade.id = 'quill-drawer-shade';
      shade.addEventListener('click', function () { setDrawer(false); });
      d = el('div', 'quill-ui');
      d.id = 'quill-drawer';
      var a = el('button', 'quill-ui quill-arrow');
      a.id = 'quill-drawer-arrow';
      a.type = 'button';
      a.setAttribute('aria-label', 'Sessions');
      a.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        setDrawer(!drawerOpen());
      }, true);
      document.body.appendChild(shade);
      document.body.appendChild(d);
      document.body.appendChild(a);
      lastSig = '';
      setDrawer(drawerOpen());
    } else if (!d.isConnected || !document.getElementById('quill-drawer-arrow')) {
      ['quill-drawer', 'quill-drawer-shade', 'quill-drawer-arrow'].forEach(function (id) {
        var x = document.getElementById(id);
        if (x) x.remove();
      });
      return sessions();
    }
    if (sig === lastSig) return;
    lastSig = sig;
    if (window.QuillNative && QuillNative.sessions) {
      try {
        QuillNative.sessions(JSON.stringify({
          path: path,
          items: list.map(function (s) { return { id: s.id, folder: s.folder, title: s.title }; }),
          order: folderOrder(list)
        }));
      } catch (e) {}
    }
    d.innerHTML = '';
    d.appendChild(el('div', 'quill-drawer-title', 'Sessions'));
    if (!list.length) {
      d.appendChild(el('div', 'quill-drawer-empty', 'No sessions found on this page yet. Open claude.ai\'s own session list once, then they show up here.'));
      return;
    }
    var groups = {};
    list.forEach(function (s) { (groups[s.folder] = groups[s.folder] || []).push(s); });
    folderOrder(list).forEach(function (name) {
      var items = groups[name];
      if (!items) return;
      var open = folderOpen(name);
      var head = el('button', 'quill-folder', (open ? OPEN_SVG : FOLDER_SVG) + '<span></span><em></em>');
      head.type = 'button';
      head.querySelector('span').textContent = name;
      head.querySelector('em').textContent = items.length;
      head.addEventListener('click', function () {
        try { localStorage.setItem('quill.folder.' + name, open ? '0' : '1'); } catch (e) {}
        lastSig = '';
        sessions();
      });
      d.appendChild(head);
      if (!open) return;
      items.forEach(function (s) {
        var row = el('a', 'quill-session' + (path.indexOf(s.href) === 0 ? ' current' : ''));
        row.href = s.href;
        row.textContent = s.title;
        row.addEventListener('click', function (e) {
          e.preventDefault();
          setDrawer(false);
          if (s.link && s.link.isConnected) s.link.click();
          else location.href = s.href;
        });
        d.appendChild(row);
      });
    });
  }

  var DRAWER_CSS = [
    '#quill-drawer{position:fixed;top:0;bottom:0;left:0;width:min(330px,86vw);z-index:2147483645;background:#1f1f1d;border-right:1px solid #3a3a37;',
    'overflow-y:auto;padding:12px 10px 24px;box-sizing:border-box;transform:translateX(-102%);visibility:hidden;transition:transform .22s ease-in-out,visibility 0s linear .22s;color:#eceae3;font-size:15px;line-height:1.35}',
    '#quill-drawer.open{transform:none;visibility:visible;transition:transform .22s ease-in-out,visibility 0s}',
    '#quill-drawer-shade{position:fixed;inset:0;z-index:2147483644;background:rgba(0,0,0,.45);opacity:0;pointer-events:none;transition:opacity .22s ease-in-out}',
    '#quill-drawer-shade.open{opacity:1;pointer-events:auto}',
    '#quill-drawer-arrow{top:42vh;left:0;width:26px;height:44px;border-radius:0 8px 8px 0;transition:left .22s ease-in-out}',
    '#quill-drawer-arrow.open{left:calc(min(330px,86vw) - 1px)}',
    '.quill-drawer-title{font-weight:700;font-size:16px;padding:4px 8px 10px;color:#eceae3}',
    '.quill-drawer-empty{color:#9c9a92;padding:8px;line-height:1.5}',
    '.quill-folder{display:flex;align-items:center;gap:8px;width:100%;min-height:40px;padding:6px 8px;margin-top:6px;border:0;background:transparent;color:#eceae3;font:inherit;font-weight:700;text-align:left}',
    '.quill-folder svg{width:18px;height:18px;flex:none;fill:none;stroke:#9c9a92;stroke-width:1.7;stroke-linejoin:round}',
    '.quill-folder span{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '.quill-folder em{font-style:normal;font-weight:400;color:#9c9a92;font-size:13px}',
    '.quill-session{display:block;min-height:44px;box-sizing:border-box;padding:11px 10px 11px 34px;margin:1px 0;border-radius:8px;color:#eceae3!important;text-decoration:none;',
    'overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '.quill-session:active{background:rgba(255,255,255,.07)}',
    '.quill-session.current{outline:1.5px solid #eceae3;outline-offset:-1.5px;font-weight:700}'
  ].join('\n');

  var timer = 0;
  var JUNK = ['artifacts', 'customize', 'more', 'scheduled', 'edit sidebar'];
  var CLOSERS = ['close', 'dismiss', 'got it', 'not now', 'no thanks', 'maybe later', 'ok'];
  function label(el) {
    return ((el.getAttribute && el.getAttribute('aria-label')) || el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ').toLowerCase();
  }
  function hideEl(el) {
    if (el && !el.hasAttribute('data-quill-hidden')) el.setAttribute('data-quill-hidden', '');
  }
  function tidy() {
    document.querySelectorAll('a,button,[role=menuitem],[role=link],[role=button],[role=tab]').forEach(function (el) {
      if (el.closest('.quill-ui,main,form,pre,[data-quill-hidden]')) return;
      var t = (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ').toLowerCase();
      if (JUNK.indexOf(t) >= 0) hideEl(el.closest('li') || el);
    });
    document.querySelectorAll('a,span,div,h1,h2,button').forEach(function (el) {
      if (el.children.length > 1 || el.closest('main,form,pre,.quill-ui,[data-quill-hidden]')) return;
      if ((el.innerText || '').trim() !== 'Code') return;
      var r = el.getBoundingClientRect();
      if (r.top < 90 && r.left < 320 && r.height < 80) hideEl(el.closest('a,button') || el);
    });
    document.querySelectorAll('a,button').forEach(function (el) {
      if (label(el) !== 'learn more' || el.closest('[data-quill-hidden]')) return;
      var box = el.parentElement, n = 0;
      while (box && n < 6 && !/remote control/i.test(box.innerText || '')) { box = box.parentElement; n++; }
      if (!box || !/remote control/i.test(box.innerText || '') || box === document.body || box.contains(document.querySelector('main textarea'))) return;
      var host = el.closest('[role=dialog],[role=alertdialog],[role=alert],[role=status]') || box;
      var closer = Array.prototype.filter.call(host.querySelectorAll('button,[role=button]'), function (b) {
        return CLOSERS.indexOf(label(b)) >= 0;
      })[0];
      if (closer) closer.click();
      else hideEl(host);
    });
  }

  var shiftDown = false;
  function msgBox(t) {
    var box = t && t.closest ? t.closest('textarea,[contenteditable=true],[contenteditable=""]') : null;
    return box && !box.closest('.quill-ui') ? box : null;
  }
  function sendFrom(t) {
    var box = msgBox(t);
    if (!box) return false;
    var text = box.value !== undefined ? box.value : box.innerText;
    if (!text || !text.trim()) return true;
    var scope = box.closest('form,fieldset') || document;
    var btns = Array.prototype.filter.call(scope.querySelectorAll('button'), function (b) {
      var l = label(b);
      return !b.disabled && (b.type === 'submit' || /send|submit/.test(l)) && b.offsetParent !== null;
    });
    if (!btns.length && scope !== document) {
      btns = Array.prototype.filter.call(document.querySelectorAll('button[aria-label]'), function (b) {
        return !b.disabled && /send/.test(label(b)) && b.offsetParent !== null;
      });
    }
    if (Q.prefs.debugKeys) console.log('quillkey send buttons=' + btns.length);
    if (btns.length) { btns[btns.length - 1].click(); return true; }
    var f = box.closest('form');
    if (f && f.requestSubmit) { f.requestSubmit(); return true; }
    return false;
  }
  function keys() {
    if (Q.keysOn) return;
    Q.keysOn = true;
    document.addEventListener('keydown', function (e) {
      if (Q.prefs.debugKeys) console.log('quillkey keydown key=' + e.key + ' code=' + e.keyCode + ' comp=' + e.isComposing + ' box=' + !!msgBox(e.target));
      if (e.key === 'Shift') shiftDown = true;
      if (!msgBox(e.target)) return;
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
        if (sendFrom(e.target)) { e.preventDefault(); e.stopPropagation(); }
      } else if (e.key === 'Tab' && !e.shiftKey && Q.prefs.hwkb) {
        var opt = document.querySelector('[role=listbox] [aria-selected=true],[role=option][aria-selected=true]');
        if (opt) { e.preventDefault(); e.stopPropagation(); opt.click(); }
      }
    }, true);
    document.addEventListener('keyup', function (e) { if (e.key === 'Shift') shiftDown = false; }, true);
    document.addEventListener('beforeinput', function (e) {
      if (Q.prefs.debugKeys) console.log('quillkey beforeinput type=' + e.inputType + ' box=' + !!msgBox(e.target));
      if ((e.inputType === 'insertLineBreak' || e.inputType === 'insertParagraph') && !shiftDown && msgBox(e.target)) {
        if (sendFrom(e.target)) { e.preventDefault(); e.stopPropagation(); }
      }
    }, true);
  }
  function hints() {
    document.querySelectorAll('textarea,[contenteditable=true]').forEach(function (el) {
      if (!el.closest('.quill-ui') && el.getAttribute('enterkeyhint') !== 'send') el.setAttribute('enterkeyhint', 'send');
    });
  }

  function soon(records) {
    if (records && records.length && records.every(function (r) {
      var t = r.target.nodeType === 1 ? r.target : r.target.parentElement;
      return t && t.closest && t.closest('.quill-ui,.quill-arrow');
    })) return;
    if (timer) return;
    timer = setTimeout(function () {
      timer = 0;
      style();
      scan();
      sessions();
      tidy();
      hints();
    }, 250);
  }

  Q.setPrefs = function (p) {
    Q.prefs = p || {};
    style();
    soon();
  };
  Q.toggle = toggle;
  Q.sessions = function () { setDrawer(!drawerOpen()); };
  Q.open = function (id) {
    setDrawer(false);
    var s = sessionLinks().filter(function (x) { return x.id === id; })[0];
    if (s && s.link && s.link.isConnected) s.link.click();
    else location.href = '/code/' + id;
  };

  function start() {
    keys();
    style();
    scan();
    sessions();
    new MutationObserver(soon).observe(document.documentElement, { childList: true, subtree: true });
    window.addEventListener('resize', function () { soon(); });
    window.addEventListener('popstate', function () { soon(); });
  }

  if (document.body) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
