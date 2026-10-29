// The kiosk's own layer over every interactive page (KioskSession): an on-screen keyboard with no
// suggestions and nothing learned, the "starting over" countdown, the PIN pad the corner gesture asks
// for, and a note to the agent on each touch. Drawn in a closed shadow root so the page's styles
// cannot reach it, and built without innerHTML so pages that enforce Trusted Types allow it.
(() => {
  if (window.__cpKiosk || window.top !== window) return;
  const send = (message) => { try { window.cpKiosk(JSON.stringify(message)); } catch (e) { /* binding not yet there */ } };

  const host = document.createElement('cp-kiosk');
  const root = host.attachShadow({ mode: 'closed' });
  const style = document.createElement('style');
  style.textContent = `
    :host { all: initial; }
    * { box-sizing: border-box; font-family: system-ui, sans-serif; user-select: none; -webkit-user-select: none; }
    .kb { position: fixed; left: 0; right: 0; bottom: 0; z-index: 2147483647; background: #1f1f23;
          padding: 8px; display: none; flex-direction: column; gap: 8px; touch-action: none; }
    .kb.open { display: flex; }
    .row { display: flex; gap: 8px; justify-content: center; }
    button { flex: 1 1 0; max-width: 110px; height: 64px; border: 0; border-radius: 10px; background: #3a3a40;
             color: #fff; font-size: 26px; touch-action: manipulation; }
    button:active { background: #5a5a62; }
    button.wide { max-width: 260px; flex-grow: 2; }
    button.space { max-width: 520px; flex-grow: 6; }
    button.on { background: #2f6fde; }
    .kbtoggle { position: fixed; right: 16px; bottom: 16px; z-index: 2147483646; width: 72px; height: 72px;
                max-width: none; border-radius: 36px; background: rgba(31, 31, 35, 0.85); padding: 0;
                display: flex; align-items: center; justify-content: center; box-shadow: 0 2px 10px rgba(0,0,0,.35); }
    .kbtoggle.hidden { display: none; }
    .kbtoggle svg { width: 40px; height: 40px; fill: none; stroke: #fff; stroke-width: 1.6; stroke-linecap: round; }
    .strip { position: fixed; top: 0; left: 0; right: 0; z-index: 2147483647; display: none; padding: 18px;
             text-align: center; font-size: 26px; color: #fff; background: rgba(20, 20, 24, 0.92); }
    .strip.open { display: block; }
    .pin { position: fixed; inset: 0; z-index: 2147483647; display: none; align-items: center; justify-content: center;
           background: rgba(0, 0, 0, 0.88); }
    .pin.open { display: flex; }
    .pad { width: 360px; display: flex; flex-direction: column; gap: 12px; }
    .dots { height: 56px; text-align: center; font-size: 40px; color: #fff; letter-spacing: 10px; }
    .note { text-align: center; color: #ddd; font-size: 22px; min-height: 28px; }
    .pad .row button { max-width: none; height: 72px; }
  `;
  root.append(style);

  const make = (tag, cls, text) => {
    const el = document.createElement(tag);
    if (cls) el.className = cls;
    if (text !== undefined) el.textContent = text;
    return el;
  };
  // A press on the kiosk's own controls must not take focus from the field being typed in.
  const press = (el, act) => {
    el.addEventListener('pointerdown', (e) => { e.preventDefault(); e.stopPropagation(); act(); });
    el.addEventListener('mousedown', (e) => e.preventDefault());
    return el;
  };

  // ---- Keyboard ----
  const kb = make('div', 'kb');
  let shift = false;
  let symbols = false;
  let mode = 'text';
  const LETTERS = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'];
  const SYMBOLS = ['1234567890', '-/:;()$&@"', '.,?!\'#%+='];
  const DIGITS = ['123', '456', '789'];
  const key = (k) => send({ t: 'key', k });

  const build = () => {
    kb.replaceChildren();
    const rows = mode === 'digits' ? DIGITS : (symbols ? SYMBOLS : LETTERS);
    rows.forEach((chars, i) => {
      const row = make('div', 'row');
      if (mode !== 'digits' && i === 2 && !symbols) {
        row.append(press(make('button', 'wide' + (shift ? ' on' : ''), 'Shift'), () => { shift = !shift; build(); }));
      }
      [...chars].forEach((c) => {
        const shown = shift ? c.toUpperCase() : c;
        row.append(press(make('button', '', shown), () => {
          key(shown);
          if (shift) { shift = false; build(); }
        }));
      });
      if (i === rows.length - 1) {
        row.append(press(make('button', 'wide', 'Delete'), () => key('Backspace')));
      }
      kb.append(row);
    });
    const last = make('div', 'row');
    if (mode === 'digits') {
      last.append(press(make('button', '', '+'), () => key('+')));
      last.append(press(make('button', '', '0'), () => key('0')));
      last.append(press(make('button', '', '-'), () => key('-')));
    } else {
      last.append(press(make('button', 'wide', symbols ? 'ABC' : '123'), () => { symbols = !symbols; build(); }));
      if (mode === 'email') last.append(press(make('button', '', '@'), () => key('@')));
      last.append(press(make('button', 'space', 'space'), () => key(' ')));
      last.append(press(make('button', '', '.'), () => key('.')));
      if (mode === 'email') last.append(press(make('button', 'wide', '.com'), () => key('.com')));
    }
    last.append(press(make('button', 'wide', 'Next'), () => key('Tab')));
    last.append(press(make('button', 'wide', 'Hide'), () => close()));
    kb.append(last);
  };

  const spacer = make('div');
  const NO_KEYBOARD = new Set(['button', 'checkbox', 'color', 'file', 'hidden', 'image', 'radio', 'range', 'reset', 'submit']);
  const editable = (el) => {
    if (!el) return false;
    if (el.tagName === 'IFRAME') return true; // a field in another site's frame (a card form, say)
    if (el.isContentEditable || el.tagName === 'TEXTAREA') return true;
    return el.tagName === 'INPUT' && !NO_KEYBOARD.has((el.type || 'text').toLowerCase());
  };
  const modeOf = (el) => {
    const type = (el && el.type || '').toLowerCase();
    const inputMode = (el && el.inputMode || '').toLowerCase();
    if (type === 'email' || inputMode === 'email') return 'email';
    if (type === 'tel' || type === 'number' || inputMode === 'numeric' || inputMode === 'tel' || inputMode === 'decimal') return 'digits';
    return 'text';
  };
  // The field with focus, inside open shadow roots too (custom form elements keep their fields there).
  const deepActive = () => {
    let el = document.activeElement;
    while (el && el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
    return el;
  };
  // Opened with the corner button: stays up until Hide, whatever has focus.
  let manual = false;
  const open = (el) => {
    const next = modeOf(el);
    if (!kb.classList.contains('open') || next !== mode) { mode = next; symbols = false; shift = false; build(); }
    kb.classList.add('open');
    spacer.style.height = kb.offsetHeight + 'px';
    if (!spacer.isConnected && document.body) document.body.append(spacer);
    toggle.classList.add('hidden');
    if (!el || !el.getBoundingClientRect) return;
    // Keep the field above the keyboard.
    const box = el.getBoundingClientRect();
    const room = window.innerHeight - kb.offsetHeight;
    if (box.bottom > room - 16 || box.top < 0) window.scrollBy({ top: box.top - room / 3, behavior: 'smooth' });
  };
  const hide = () => {
    kb.classList.remove('open');
    spacer.remove();
    toggle.classList.remove('hidden');
    manual = false;
  };
  const close = () => {
    hide();
    if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
  };
  document.addEventListener('focusin', (e) => {
    const el = e.composedPath ? e.composedPath()[0] : e.target;
    if (editable(el)) open(el);
  }, true);
  // Tapping the field that has focus (after Hide, say) brings the keyboard back.
  document.addEventListener('pointerup', () => {
    if (editable(deepActive()) && !kb.classList.contains('open')) open(deepActive());
  }, true);
  document.addEventListener('focusout', () => {
    setTimeout(() => { if (!manual && !editable(deepActive())) hide(); }, 150);
  }, true);
  // A field in another site's frame takes focus without telling this page; the window's blur does.
  window.addEventListener('blur', () => {
    setTimeout(() => {
      if (document.activeElement && document.activeElement.tagName === 'IFRAME') open(document.activeElement);
    }, 50);
  });

  // ---- The keyboard button, for fields that do not bring the keyboard up themselves ----
  const toggle = make('button', 'kbtoggle');
  const SVG = 'http://www.w3.org/2000/svg';
  const icon = document.createElementNS(SVG, 'svg');
  icon.setAttribute('viewBox', '0 0 24 24');
  const shape = (tag, attrs) => {
    const el = document.createElementNS(SVG, tag);
    Object.entries(attrs).forEach(([k, v]) => el.setAttribute(k, v));
    icon.append(el);
  };
  shape('rect', { x: '2', y: '6', width: '20', height: '12', rx: '2' });
  [6, 10, 14, 18].forEach((x) => shape('path', { d: `M${x} 10h0.01` }));
  [6, 10, 14, 18].forEach((x) => shape('path', { d: `M${x} 13h0.01` }));
  shape('path', { d: 'M8 15.5h8' });
  toggle.append(icon);
  toggle.setAttribute('aria-label', 'Keyboard');
  press(toggle, () => {
    manual = true;
    const el = deepActive();
    open(editable(el) ? el : null);
  });

  // ---- Countdown ----
  const strip = make('div', 'strip');
  const countdown = (seconds) => {
    if (seconds > 0) {
      strip.textContent = `Starting over in ${seconds} s. Tap anywhere to keep going.`;
      strip.classList.add('open');
    } else {
      strip.classList.remove('open');
    }
  };

  // ---- PIN pad ----
  const pin = make('div', 'pin');
  const pad = make('div', 'pad');
  const dots = make('div', 'dots');
  const note = make('div', 'note', 'Enter the PIN');
  let typed = '';
  const show = () => { dots.textContent = '•'.repeat(typed.length); };
  const padRow = (labels) => {
    const row = make('div', 'row');
    labels.forEach(([label, act]) => row.append(press(make('button', '', label), act)));
    return row;
  };
  const digit = (d) => [d, () => { if (typed.length < 8) { typed += d; show(); } }];
  pad.append(dots, note,
    padRow([digit('1'), digit('2'), digit('3')]),
    padRow([digit('4'), digit('5'), digit('6')]),
    padRow([digit('7'), digit('8'), digit('9')]),
    padRow([['Delete', () => { typed = typed.slice(0, -1); show(); }], digit('0'), ['OK', () => send({ t: 'pin', v: typed })]]),
    padRow([['Cancel', () => send({ t: 'pinCancel' })]]));
  pin.append(pad);
  const pinPad = (wanted) => {
    typed = '';
    show();
    note.textContent = 'Enter the PIN';
    pin.classList.toggle('open', wanted);
  };
  const pinResult = (right) => {
    if (right) { pinPad(false); return; }
    typed = '';
    show();
    note.textContent = 'Wrong PIN';
  };

  root.append(toggle, kb, strip, pin);

  // A mouse's pointer shows while the mouse moves, and goes on a touch, after it has stood still a
  // few seconds, and on every new page (with no mouse, cage's own pointer is blank: BlankCursor).
  const noCursor = document.createElement('style');
  noCursor.textContent = '*, *::before, *::after { cursor: none !important; }';
  // Chromium changes the pointer only on mouse input, so the agent then moves the mouse to where it
  // already is; the move that brings is not the mouse's.
  let cursorTimer = 0;
  let cursorHidden = false;
  let cursorStarted = false;
  let ignoreMovesUntil = 0;
  let mouseX = null;
  let mouseY = null;
  const hideCursor = () => {
    clearTimeout(cursorTimer);
    cursorHidden = true;
    const top = document.documentElement;
    if (!top || noCursor.isConnected) return;
    top.append(noCursor);
    ignoreMovesUntil = Date.now() + 500;
    // Not moved on this page: the agent uses where it last was, or the middle, where cage starts it.
    const middle = { cx: innerWidth / 2, cy: innerHeight / 2 };
    send(mouseX === null ? { t: 'cursor', ...middle } : { t: 'cursor', x: mouseX, y: mouseY });
  };
  addEventListener('pointermove', (e) => {
    if (e.pointerType !== 'mouse' || Date.now() < ignoreMovesUntil) return;
    mouseX = e.clientX;
    mouseY = e.clientY;
    cursorHidden = false;
    noCursor.remove();
    clearTimeout(cursorTimer);
    cursorTimer = setTimeout(hideCursor, 3000);
  }, true);
  addEventListener('pointerdown', (e) => { if (e.pointerType === 'touch') hideCursor(); }, true);

  // The script runs before the page has any element; the layer goes in once <html> is there, and
  // back in if the page replaces it.
  const attach = () => {
    const top = document.documentElement;
    if (!top) return;
    if (!host.isConnected) top.append(host);
    if (!cursorStarted) {
      cursorStarted = true;
      hideCursor();
    } else if (cursorHidden && !noCursor.isConnected) {
      top.append(noCursor);
    }
  };
  attach();
  document.addEventListener('DOMContentLoaded', attach);
  window.addEventListener('load', attach);
  new MutationObserver(attach).observe(document, { childList: true });

  // ---- Touches, and staying on one page ----
  let lastSent = 0;
  const activity = () => {
    countdown(0);
    const now = Date.now();
    if (now - lastSent > 1000) { lastSent = now; send({ t: 'act' }); }
  };
  ['pointerdown', 'keydown', 'input', 'scroll', 'touchstart'].forEach((type) =>
    window.addEventListener(type, activity, { capture: true, passive: true }));
  // Pop-ups and new windows open in this one view, where the agent can still see where they go.
  window.open = (url) => { if (url) location.href = url; return null; };
  document.addEventListener('click', (e) => {
    const link = e.target && e.target.closest && e.target.closest('a[target]');
    if (link) link.removeAttribute('target');
  }, true);

  Object.defineProperty(window, '__cpKiosk', { value: Object.freeze({ countdown, pinPad, pinResult }) });
  send({ t: 'ready' });
})();
