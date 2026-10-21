// Runs on one of Spectrum's attendance activity pages (mod/attendance/view.php?id=…), signed in as the
// student, so its requests carry their session. Marks them in the session open for them now, the way
// the page's own "Submit attendance" link does: that link (attendance.php?sessid=…) either records the
// session's best status at once, or shows a form, where "Present" (else the first status offered) is
// chosen and sent. The app sends {"dry": true|false} over a message port and gets back {ok, status} or
// {error, detail, rows}. Dry, it only says what it found on this page and opens nothing: opening the link
// can already mark the student. rows: the page's sessions as text, for the app's log.
//
// Errors: notopen (no link: no session open for self-marking), password (the lecturer's password),
// qr (a rotating QR code), network (only from the campus network), closed, nostatus, failed.
(() => {
  const RECORDED = /attendance in this session has been recorded|already submitted your attendance/i;
  const PASSWORD = /password/i;

  async function text(url, init) {
    const r = await fetch(url, Object.assign({ credentials: 'include' }, init));
    if (!r.ok) throw new Error('Spectrum answered HTTP ' + r.status);
    return { url: r.url, html: await r.text() };
  }

  const page = html => new DOMParser().parseFromString(html, 'text/html');

  /** What a page Spectrum ended on says went wrong, or null when it doesn't say. */
  function trouble(doc) {
    const said = (doc.querySelector('[role=main], #region-main, .alert, .errorbox, .notifymessage') || doc.body || {}).textContent || '';
    if (/certain locations|not on the allowed list/i.test(said)) return { error: 'network', detail: said.trim().slice(0, 160) };
    if (/qr code|scan again/i.test(said)) return { error: 'qr', detail: said.trim().slice(0, 160) };
    if (/not currently available|closed|not open/i.test(said)) return { error: 'closed', detail: said.trim().slice(0, 160) };
    return null;
  }

  async function mark(dry) {
    const anchors = [...document.querySelectorAll('a[href*="/mod/attendance/attendance.php"]')].filter(a => /[?&]sessid=\d+/.test(a.href));
    const links = anchors.map(a => a.href);
    if (dry) return { ok: false, dry: true, links: links.length, title: document.title, rows: rows() };
    if (!links.length) return { error: 'notopen' };

    // The link itself: Spectrum may record the student at once and go back to this page.
    const first = await text(links[0]);
    if (RECORDED.test(first.html)) return { ok: true, status: '' };
    const doc = page(first.html);
    const form = doc.querySelector('form input[name="sessid"]')?.form;
    if (!form) return trouble(doc) || { error: 'failed', detail: 'no form at ' + new URL(first.url).pathname };
    if (form.querySelector('[name="studentpassword"]')) return { error: 'password' };

    // The statuses offered, by their labels; "Present" (or Malay "Hadir") if there is one.
    const options = [...form.querySelectorAll('input[type="radio"][name="status"]:not([disabled])')].map(input => ({
      input,
      label: ((input.closest('label') || input.parentElement || {}).textContent || '').trim(),
    }));
    if (!options.length) return { error: 'nostatus' };
    const chosen = options.find(o => /^(present|hadir)\b/i.test(o.label)) || options[0];

    const data = new FormData(form);
    data.set('status', chosen.input.value);
    const submit = form.querySelector('[type="submit"][name="submitbutton"]');
    if (submit) data.set('submitbutton', submit.value);
    const sent = await text(new URL(form.getAttribute('action') || first.url, first.url).href, { method: 'POST', body: data });
    if (RECORDED.test(sent.html) || await taken(anchors[0])) return { ok: true, status: chosen.label };
    return trouble(page(sent.html)) || { error: 'failed', detail: 'no confirmation from Spectrum' };
  }

  /** The session table's rows, condensed: date, time, what each says (a status, "Submit attendance", "?"). */
  const rows = () => [...document.querySelectorAll('table tbody tr')].slice(0, 8)
    .map(tr => tr.textContent.replace(/\s+/g, ' ').trim().slice(0, 140));

  const rowText = el => ((el && el.closest('tr')) || {}).textContent?.replace(/\s+/g, ' ').trim() || '';

  /**
   * This page read again, for when Spectrum's words differ: the session's link is gone, and its row (the
   * one starting with the same date and time, "Tue 20 Oct 2026 9AM - 11AM") says "Self-recorded", as UM's
   * marked sessions do.
   */
  async function taken(anchor) {
    const again = page((await text(location.href)).html);
    const sessid = new URL(anchor.href).searchParams.get('sessid');
    const links = [...again.querySelectorAll('a[href*="/mod/attendance/attendance.php"]')];
    if (links.some(a => new URL(a.href, location.href).searchParams.get('sessid') === sessid)) return false;
    const when = (rowText(anchor).match(/^\S+ \d+ \S+ \d{4} \S+ - \S+/) || [])[0];
    return !!when && [...again.querySelectorAll('table tbody tr')].some(tr => {
      const t = rowText(tr.firstElementChild);
      return t.startsWith(when) && /self-recorded/i.test(t);
    });
  }

  window.addEventListener('message', function handler(e) {
    if (e.data == null || e.ports.length !== 1) return;
    let ask;
    try { ask = JSON.parse(e.data); } catch (_) { return; }
    if (!ask || typeof ask.dry !== 'boolean') return;
    window.removeEventListener('message', handler);
    const port = e.ports[0];
    // A failure carries the page's sessions too, for the app's log.
    mark(ask.dry).then(
      answer => port.postMessage(JSON.stringify(answer.ok || answer.rows ? answer : Object.assign(answer, { rows: rows() }))),
      err => port.postMessage(JSON.stringify({ error: 'failed', detail: String((err && err.message) || err) })),
    );
  });
})();
