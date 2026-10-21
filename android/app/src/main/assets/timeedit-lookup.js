// Runs inside TimeEdit's own student page (cloud.timeedit.net/my_um/web/students/), so its
// requests carry that page's sign-in. The app sends the user's courses as {"WIX1002": 10, …}
// over a message port and gets back the weekly classes of those occurrences, or
// {error, signedOut}. Each class: code, module (TimeEdit's name), day, start, end, activity,
// room, weeks (how many weeks it's on in the period), term ("2026/S1") and lecturers. Also, per
// course, every occurrence TimeEdit lists in those weeks ({"WIX1002": [1, 2, 16]}), to choose from.
// With {"registered": {"GIG1003": 6}} too, that occurrence's classes come as well, marked registered
// (the user chose another occurrence; attendance stays with Spectrum's).
//
// Per course: search the module by code, list its classes for the next six weeks (a mid-semester
// break doesn't empty it) and keep those of the occurrence ("WIX1002/2026/S1/10"). One class per
// weekly slot is then opened: its Lecturer rows point at lecturer objects (data-object="11484.3"),
// whose info has the title and full name.
(() => {
  const BASE = '/my_um/web/students/';

  async function get(path) {
    const r = await fetch(BASE + path, { credentials: 'include' });
    // 412 is TimeEdit's login page.
    if (r.status === 412) throw Object.assign(new Error('signed out'), { signedOut: true });
    if (!r.ok) throw new Error('TimeEdit answered HTTP ' + r.status);
    return r;
  }

  const mostCommon = values => {
    const counts = new Map();
    for (const v of values) if (v) counts.set(v, (counts.get(v) || 0) + 1);
    return [...counts].sort((a, b) => b[1] - a[1]).map(e => e[0])[0] || '';
  };

  async function lookUp(courses, registered) {
    const lecturers = {};
    const slots = [];
    const offered = {};
    for (const [code, occ] of Object.entries(courses)) {
      const search = await (await get('objects.html?max=20&fr=t&partajax=t&im=f&sid=4&l=en_US&objects=&types=15&part=t&media=html&search_text=' + encodeURIComponent(code))).text();
      const module = [...new DOMParser().parseFromString(search, 'text/html').querySelectorAll('[data-idonly]')]
        .find(e => (e.getAttribute('data-name') || '').startsWith(code + ','));
      if (!module) continue;
      const moduleName = module.getAttribute('data-name').slice(code.length + 1).trim();
      const ri = await (await get('ri.json?h=f&sid=4&p=0.d,6.w&objects=' + module.getAttribute('data-idonly') + '.15&ox=0&types=0&fe=0&h2=f&l=en_EN')).json();
      const col = name => ri.columnheaders.findIndex(h => h.startsWith(name));
      const offering = ri.columnheaders.indexOf('Module Offering');
      const activity = ri.columnheaders.indexOf('Activity');
      const type = col('Activity Type');
      const room = ri.columnheaders.indexOf('Room');
      if (offering < 0 || activity < 0) continue;
      const of = n => o => { const p = o.trim().split('/'); return p.length === 4 && p[0] === code && p[3] === String(n); };
      // The occurrences whose classes to bring: the one asked for, and Spectrum's when it's another.
      const wanted = [[of(occ), false]];
      if (registered[code] && registered[code] !== occ) wanted.push([of(registered[code]), true]);
      const groups = new Map();
      const occs = new Set();
      for (const r of ri.reservations || []) {
        for (const o of r.columns[offering].split(',')) {
          const p = o.trim().split('/');
          if (p.length === 4 && p[0] === code && /^\d+$/.test(p[3])) occs.add(Number(p[3]));
        }
        if (/exam/i.test((type >= 0 ? r.columns[type] : '') + ' ' + r.columns[activity])) continue;
        for (const [mine, isRegistered] of wanted) {
          const offerings = r.columns[offering].split(',').filter(mine);
          if (!offerings.length) continue;
          const [d, m, y] = r.startdate.split('/').map(Number);
          const key = [new Date(y, m - 1, d).getDay(), r.starttime, r.endtime, r.columns[activity], isRegistered ? 'r' : ''].join('|');
          if (!groups.has(key)) groups.set(key, []);
          groups.get(key).push(Object.assign({}, r, { term: offerings[0].trim().split('/').slice(1, 3).join('/') }));
        }
      }
      offered[code] = [...occs].sort((a, b) => a - b);
      for (const [key, classes] of groups) {
        // A week can be missing its lecturer; look at up to three weeks of the slot.
        let ids = [];
        for (const r of classes.slice(0, 3)) {
          const detail = await (await get('ri.html?h=f&sid=3&types=4&fe=0&h2=f&l=en_EN&id=' + r.id + '&fr=t&step=0&ef=2&nocache=2')).text();
          ids = [...new Set([...detail.matchAll(/data-object="(\d+)\.3"/g)].map(m => m[1]))];
          if (ids.length) break;
        }
        const names = [];
        for (const id of ids) {
          if (!lecturers[id]) {
            const o = await (await get('objects/' + id + '/o.json?fr=t&types=15&sid=5&l=en_US')).json();
            lecturers[id] = { title: o['Title'] || '', name: o['Full Name'] || '' };
          }
          names.push(lecturers[id]);
        }
        const [day, start, end, act, reg] = key.split('|');
        slots.push({
          code, module: moduleName, day: Number(day), start, end, activity: act, registered: reg === 'r',
          room: room >= 0 ? mostCommon(classes.map(r => r.columns[room].trim())) : '',
          weeks: new Set(classes.map(r => r.startdate)).size,
          term: mostCommon(classes.map(r => r.term)),
          lecturers: names,
        });
      }
    }
    return { slots, offered };
  }

  window.addEventListener('message', function handler(e) {
    if (e.data == null || e.ports.length !== 1) return;
    let courses, registered;
    try { ({ courses, registered } = JSON.parse(e.data)); } catch (_) { return; }
    if (!courses) return;
    window.removeEventListener('message', handler);
    const port = e.ports[0];
    lookUp(courses, registered || {}).then(
      ({ slots, offered }) => port.postMessage(JSON.stringify({ slots, offered })),
      err => port.postMessage(JSON.stringify({ error: String((err && err.message) || err), signedOut: !!(err && err.signedOut) })),
    );
  });
})();
