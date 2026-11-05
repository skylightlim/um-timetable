// Runs on one of Spectrum's attendance activity pages (mod/attendance/view.php?id=…), signed in as the
// student, so its requests carry their session. Marks them in the session open for them now, the way
// the page's own "Submit attendance" link does: that link (attendance.php?sessid=…) either records the
// session's best status at once, or shows a form, where "Present" (else the first status offered) is
// chosen and sent. The app sends {"dry": true|false} over a message port and gets back {ok, status} or
// {error, detail, rows}. Dry, it only says what it found on this page and opens nothing: opening the link
// can already mark the student. rows: the page's sessions as text, for the app's log.
//
// Not marked (and dry), the answer also has images: the pictures on this page and on the session's own
// (its description), where a lecturer may have put the session's QR code. Each is {src, data} (base64,
// read with the student's session) or {src, url} (another site's, for the app to fetch). The app reads
// them for a code (AttendanceCode) and, finding one, sends {"dry": false, "link": …}: on any Spectrum page
// the script then opens that link instead, the session's link the code holds
// (attendance.php?qrpass=…&sessid=…), which marks the student at once or shows the form with the code's
// password filled in. Its answer also has cmid, the attendance activity Spectrum went back to, if it says.
//
// No link but today's session has a status already: {ok, already, status}.
// Errors: notopen (no link: no session open for self-marking), password (the lecturer's password),
// qr (a rotating QR code: the session takes its code only, or a scanned one has just expired), network
// (only from the campus network), closed, nostatus, failed.
(() => {
  const RECORDED = /attendance in this session has been recorded|already submitted your attendance/i;
  /** Answers a code on the page might get past (not a session that isn't open, nor the network). */
  const PICTURES_FOR = new Set(['password', 'qr', 'notopen', 'nostatus', 'failed']);
  /** The session pages opened on the way (the form), whose pictures count too. */
  const opened = [];

  // Spectrum's error pages (a QR code expired, a session already marked) come with HTTP 404 and say what's
  // wrong: they're read like any other page.
  async function text(url, init) {
    const r = await fetch(url, Object.assign({ credentials: 'include' }, init));
    return { url: r.url, status: r.status, html: await r.text() };
  }

  const page = html => new DOMParser().parseFromString(html, 'text/html');

  /** What a page Spectrum ended on says went wrong (or that it's marked already), or null when it doesn't say. */
  function trouble(doc) {
    const said = (doc.querySelector('[role=main], #region-main, .alert, .errorbox, .notifymessage') || doc.body || {}).textContent || '';
    const detail = said.replace(/\s+/g, ' ').trim().slice(0, 160);
    if (/attendance has already been set/i.test(said)) return { ok: true, already: true, status: '' };
    if (/certain locations|not on the allowed list/i.test(said)) return { error: 'network', detail };
    if (/qr code|qr session|scan again/i.test(said)) return { error: 'qr', detail };
    if (/incorrect password/i.test(said)) return { error: 'password', detail };
    if (/not currently available|closed|not open/i.test(said)) return { error: 'closed', detail };
    return null;
  }

  /** Where Spectrum sent the student back to: the attendance activity's id in view.php?id=…, or 0. */
  function cmid(url) {
    const u = new URL(url, location.href);
    return u.pathname === '/mod/attendance/view.php' ? Number(u.searchParams.get('id')) || 0 : 0;
  }

  async function mark(dry, link) {
    if (link) {
      if (new URL(link, location.href).origin !== location.origin) return { error: 'failed', detail: 'not a Spectrum link' };
      const first = await text(link);
      const answer = await settle(first, null);
      return Object.assign(answer, { cmid: answer.cmid || cmid(first.url) });
    }
    const anchors = [...document.querySelectorAll('a[href*="/mod/attendance/attendance.php"]')].filter(a => /[?&]sessid=\d+/.test(a.href));
    const links = anchors.map(a => a.href);
    if (dry) return { ok: false, dry: true, links: links.length, title: document.title, rows: rows(), images: await pictures([document]) };
    const answer = (links.length ? await settle(await text(links[0]), anchors[0]) : already()) || { error: 'notopen' };
    if (!answer.ok && PICTURES_FOR.has(answer.error)) answer.images = await pictures([document, ...opened]);
    return answer;
  }

  /**
   * The pictures in [docs]' main parts, at most 8, where a session's QR code may be: Spectrum's own (a
   * lecturer's upload, read with the student's session, or a picture written into the page) as base64,
   * another site's as its address. Moodle's icons and people's pictures are left out, and SVGs, which the
   * app can't read.
   */
  async function pictures(docs) {
    const out = [];
    const seen = new Set();
    for (const doc of docs) {
      const root = doc.querySelector('[role=main], #region-main') || doc.body;
      for (const img of root ? root.querySelectorAll('img') : []) {
        if (out.length >= 8) return out;
        let url;
        try { url = new URL(img.getAttribute('src') || '', location.href); } catch (_) { continue; }
        if (seen.has(url.href) || img.matches('.icon, .userpicture, .activityicon')) continue;
        seen.add(url.href);
        const src = url.protocol === 'data:' ? 'a picture in the page' : (url.origin === location.origin ? '' : url.host) + url.pathname.split('/').pop();
        try {
          if (url.protocol === 'data:') {
            const m = /^data:image\/(png|jpe?g|gif|webp|bmp);base64,(.*)$/is.exec(url.href);
            if (m) out.push({ src, data: m[2] });
          } else if (url.origin === location.origin) {
            if (/^\/(theme|pix|user\/pix\.php|lib\/)/.test(url.pathname)) continue;
            const r = await fetch(url.href, { credentials: 'include' });
            const blob = await r.blob();
            if (r.ok && /^image\/(?!svg)/.test(blob.type) && blob.size < 4e6) out.push({ src, data: await base64(blob) });
          } else if (/^https?:$/.test(url.protocol)) {
            out.push({ src, url: url.href });
          }
        } catch (_) { /* a picture that won't load isn't a code */ }
      }
    }
    return out;
  }

  const base64 = blob => new Promise((done, fail) => {
    const reader = new FileReader();
    reader.onload = () => done(String(reader.result).slice(String(reader.result).indexOf(',') + 1));
    reader.onerror = () => fail(reader.error);
    reader.readAsDataURL(blob);
  });

  /**
   * Opening a session's link ended on [first]: Spectrum recorded the student at once and went back to the
   * activity's page, or shows the form to send, or says why not. [anchor]: the link on this page, if it was.
   */
  async function settle(first, anchor) {
    const doc = page(first.html);
    opened.push(doc);
    if (RECORDED.test(first.html)) return { ok: true, status: (already(doc) || {}).status || '' };
    const form = doc.querySelector('form input[name="sessid"]')?.form;
    if (!form) return trouble(doc) || { error: 'failed', detail: 'no form at ' + new URL(first.url).pathname + (first.status === 200 ? '' : ' (HTTP ' + first.status + ')') };
    // The password box: empty unless a QR code's link filled it in.
    const password = form.querySelector('[name="studentpassword"]');
    if (password && !password.value) return { error: 'password' };

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
    if (RECORDED.test(sent.html) || (anchor && await taken(anchor))) return { ok: true, status: chosen.label, cmid: cmid(sent.url) };
    return trouble(page(sent.html)) || { error: 'failed', detail: 'no confirmation from Spectrum' };
  }

  /**
   * Today's session already has a status (the student marked it, or the lecturer did): its row starts
   * with today's date as Spectrum writes it ("Mon 19 Oct 2026") and has no "?" left. Else null.
   */
  function already(doc = document) {
    const d = new Date();
    const today = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][d.getDay()] + ' ' + d.getDate() + ' ' +
      ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][d.getMonth()] + ' ' + d.getFullYear();
    const row = allRows(doc).find(t => t.startsWith(today + ' ') && !t.includes('?'));
    if (!row) return null;
    return { ok: true, already: true, status: (row.match(/\b(present|late|excused|absent|hadir|lewat|tidak hadir)\b/i) || [''])[0] };
  }

  /** The session table's rows, condensed: date, time, what each says (a status, "Submit attendance", "?"). */
  const allRows = (doc = document) => [...doc.querySelectorAll('table tbody tr')]
    .map(tr => [...tr.children].map(td => td.textContent).join(' ').replace(/\s+/g, ' ').trim().slice(0, 140));
  /** For the log: the last dozen, where the sessions around today and the totals are. */
  const rows = () => allRows().slice(-12);

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
    const link = typeof ask.link === 'string' ? ask.link : null;
    // A failure on an attendance page carries the page's sessions too, for the app's log.
    mark(ask.dry, link).then(
      answer => port.postMessage(JSON.stringify(answer.ok || answer.rows || link ? answer : Object.assign(answer, { rows: rows() }))),
      err => port.postMessage(JSON.stringify({ error: 'failed', detail: String((err && err.message) || err) })),
    );
  });
})();
