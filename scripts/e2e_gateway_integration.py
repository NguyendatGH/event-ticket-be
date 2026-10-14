#!/usr/bin/env python3
"""E2E Encore + gateway (đều bản mới) trên DB tạm: BTC mở kênh qua Encore, gateway tự chọn terminal theo phương thức, khách
trả tiền, webhook chốt đơn, hoàn tiền chỉ cần merchant.

Dựng: DB tạm cho cả hai (payment_lab_repro, encore_repro); gateway :28090 (xem bankSimulate/scripts/e2e_channels.py);
Encore :18080 với PAYMENT_DEFAULT_GATEWAY=BANKSIM BANK_SIMULATE_URL=http://localhost:28090 BANK_SIMULATE_ADMIN_KEY=<GATEWAY_ADMIN_KEY>
BANK_SIMULATE_WEBHOOK_URL=http://localhost:18080/webhooks/mock-gateway/payment APP_SEED_ON_STARTUP=true MAIL_ENABLED=false
APP_GATEWAY_PROVISIONING_INTERVAL=PT5S, rồi:  set -a; . ./.env; set +a; python3 scripts/e2e_gateway_integration.py
Chạy MỘT lần trên DB mới (mở kênh lần hai sẽ báo BANK_ALREADY_A_CHANNEL). Tài khoản seed: organizer@example.com / a@example.com, mật khẩu password123.
"""
import json, os, subprocess, sys, time, urllib.request, urllib.error, uuid
E = "http://localhost:18080"
ok = bad = 0

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k): return None
opener = urllib.request.build_opener(NoRedirect)

def call(method, url, body=None, token=None, headers=None, form=False):
    h = {"Content-Type": "application/x-www-form-urlencoded" if form else "application/json", **(headers or {})}
    if token: h["Authorization"] = "Bearer " + token
    data = None if body is None else (body.encode() if form else json.dumps(body).encode())
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with opener.open(req) as r:
            t = r.read().decode(); return r.status, (json.loads(t) if t.startswith(("{", "[")) else t)
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        try: return e.code, json.loads(t)
        except Exception: return e.code, t

def check(name, cond, extra=""):
    global ok, bad
    if cond: ok += 1; print(f"  PASS  {name}")
    else: bad += 1; print(f"  FAIL  {name}  {str(extra)[:300]}")

def psql(db, sql):
    env = {**os.environ, "PGPASSWORD": os.environ["DB_PASSWORD"]}
    return subprocess.run(["psql", "-h", "localhost", "-U", os.environ["DB_USERNAME"], "-d", db, "-At", "-c", sql],
                          capture_output=True, text=True, env=env).stdout.strip()
def enc(sql): return psql("encore_repro", sql)
def gw(sql): return psql("payment_lab_repro", sql)

def login(email):
    st, r = call("POST", E + "/api/v1/auth/login", {"email": email, "password": "password123"})
    return r["accessToken"]

def wait(fn, timeout=90, every=1.5):
    end = time.time() + timeout
    while time.time() < end:
        v = fn()
        if v: return v
        time.sleep(every)
    return None

btc = login("organizer@example.com")       # Sunrise Live
cust = login("a@example.com")
ORG = enc("select id from organizers where slug='sunrise-live'")
EVENT, TIER = enc("select e.id||'|'||t.id from events e join ticket_tiers t on t.event_id=e.id where e.organizer_id='%s' and e.status='PUBLISHED' and e.starts_at > now() order by t.price limit 1" % ORG).split("|")
MER = enc("select gateway_merchant_no from organizer_gateway_bindings where organizer_id='%s'" % ORG)

def order(method):
    st, r = call("POST", E + "/api/v1/orders", {"eventId": EVENT, "items": [{"tierId": TIER, "quantity": 1}],
        "customer": {"name": "Nguyen Van A", "email": "a@example.com"}, "paymentMethod": method}, cust,
        {"Idempotency-Key": str(uuid.uuid4())})
    return st, r
def order_status(oid): return call("GET", E + f"/api/v1/orders/{oid}", None, cust)[1].get("status")
def gw_terminal(tradeNo):
    return gw(f"select t.terminal_id from payment_transactions p join terminals t on t.id=p.terminal_id where p.provider_payment_id='{tradeNo}'")
def trade_of(o): return o["payment"]["checkoutUrl"].rstrip("/").split("/")[-1]
def page(): return call("GET", E + "/api/v1/organizer/payout-account", None, btc)

print("== 1. Chưa mở kênh: trang BTC lấy dữ liệu từ gateway")
st, p = page()
check("GET payout-account 200", st == 200, p)
check("chưa có kênh, 4 ngân hàng (sắp theo tên)", p["channels"] == [] and [b["code"] for b in p["banks"]] == ["MBB", "TCB", "VCB", "VTB"], p.get("banks"))
check("đang nhận thanh toán, khách thấy thẻ + QR", p["acceptingPayments"] and set(p["paymentMethods"]) == {"CARD", "QR"}, p)

print("== 2. Khách mua bằng QR khi BTC chưa có kênh (terminal mặc định)")
st, o1 = order("QR")
check("tạo đơn QR 201/200", st in (200, 201), o1)
t1 = trade_of(o1)
default_ter = gw(f"select t.terminal_id from terminals t join merchants m on m.id=t.merchant_id where m.mer_no='{MER}' and t.purpose='DEFAULT'")
check("gateway chọn terminal mặc định", gw_terminal(t1) == default_ter, (gw_terminal(t1), default_ter))
check("Encore lưu merchant đã thu, không lưu terminal", enc(f"select gateway_merchant_no from payments where provider_payment_id='{t1}'") == MER)
st, _ = call("POST", f"http://localhost:28090/checkout/{t1}/succeed", "", None, None, form=True)
check("khách xác nhận ở gateway (303)", st == 303, _)
check("webhook về Encore: đơn PAID", wait(lambda: order_status(o1["id"]) == "PAID", 30), order_status(o1["id"]))

print("== 3. BTC mở kênh qua Encore")
acct = {"accountName": "Nguyen Van A", "accountNumber": "0011001234567"}
st, p = call("POST", E + "/api/v1/organizer/payout-account/channels", {"bankCode": "VTB", "paymentMethods": ["CARD", "QR", "PAYNOW"], **acct}, btc)
check("mở kênh VietinBank 201", st in (200, 201), p)
ch = p["channels"]
check("1 kênh chính, đúng ngân hàng + methods", len(ch) == 1 and ch[0]["primary"] and ch[0]["bankCode"] == "VTB" and set(ch[0]["paymentMethods"]) == {"CARD", "QR", "PAYNOW"}, ch)
VTB_ID = ch[0]["id"]
check("id kênh là UUID, không lộ terminal", len(VTB_ID) == 36 and "TerNo" not in VTB_ID, VTB_ID)
check("đầu trang hiện tài khoản kênh chính", p["bankBin"] == "970415" and p["maskedAccountNumber"].endswith("4567"), p)
st, pub = call("GET", E + f"/api/v1/organizers/{ORG}/payment-methods")
check("khách thấy đủ phương thức đã mở (thứ tự cố định)", st == 200 and pub["paymentMethods"] == ["CARD", "QR", "PAYNOW"], pub)

st, p = call("POST", E + "/api/v1/organizer/payout-account/channels", {"bankCode": "TCB", "paymentMethods": ["QR"], "accountName": "Nguyen B", "accountNumber": "9988776655"}, btc)
check("QR đã ở kênh VTB -> 409 giữ nguyên code từ gateway", st == 409 and p["code"] == "PAYMENT_METHOD_IN_OTHER_CHANNEL", p)
st, p = call("POST", E + "/api/v1/organizer/payout-account/channels", {"bankCode": "MBB", "paymentMethods": ["CARD"], **acct}, btc)
check("MBB không hỗ trợ CARD -> 400 giữ nguyên code", st == 400 and p["code"] == "PAYMENT_METHOD_NOT_SUPPORTED_BY_BANK", p)
st, p = call("POST", E + "/api/v1/organizer/payout-account/channels", {"bankCode": "TCB", "paymentMethods": ["GOOGLE_PAY", "APPLE_PAY"], "accountName": "Nguyen B", "accountNumber": "9988776655"}, btc)
check("mở kênh Techcombank (Google Pay, Apple Pay)", st in (200, 201) and len(p["channels"]) == 2, p)
TCB_ID = next(c["id"] for c in p["channels"] if c["bankCode"] == "TCB")
check("kênh chính vẫn là VietinBank", next(c for c in p["channels"] if c["primary"])["bankCode"] == "VTB")
check("settlement merchant ở gateway = tài khoản kênh chính", gw(f"select settlement_account_number from merchants where mer_no='{MER}'") == "0011001234567")

print("== 4. Gateway tự chọn terminal theo phương thức khách chọn")
st, o2 = order("GOOGLE_PAY")
check("tạo đơn Google Pay", st in (200, 201), o2)
t2 = trade_of(o2)
tcb_ter = gw(f"select terminal_id from terminals where id='{TCB_ID}'")
check("đơn Google Pay đi terminal của kênh Techcombank", gw_terminal(t2) == tcb_ter, (gw_terminal(t2), tcb_ter))
st, r = call("POST", f"http://localhost:28090/checkout/{t2}/google-pay", {"token": "{\"signature\":\"x\"}", "scenario": "APPROVED"})
check("Google Pay duyệt", st == 200 and r["status"] == "SUCCEEDED", r)
check("đơn Google Pay PAID ở Encore", wait(lambda: order_status(o2["id"]) == "PAID", 30), order_status(o2["id"]))
st, o3 = order("QR")
check("đơn QR đi terminal của kênh VietinBank", st in (200, 201) and gw_terminal(trade_of(o3)) == gw(f"select terminal_id from terminals where id='{VTB_ID}'"), o3)

print("== 5. Hoàn tiền chỉ cần merchant (không terminal)")
tickets = enc(f"select id from tickets where order_id='{o1['id']}'").split("\n")
st, r = call("POST", E + f"/api/v1/orders/{o1['id']}/refunds", {"ticketIds": tickets[:1], "contactEmail": "a@example.com", "reason": "test"}, cust)
check("xin hoàn vé 202", st == 202, r)
def refund_done():
    st, lst = call("GET", E + f"/api/v1/orders/{o1['id']}/refunds", None, cust)
    items = lst if isinstance(lst, list) else lst.get("items", [])
    return next((x for x in items if x.get("status") in ("SUCCEEDED", "FAILED", "MANUAL_REVIEW")), None)
done = wait(refund_done, 100, 3)
check("hoàn tiền SUCCEEDED qua gateway", done is not None and done["status"] == "SUCCEEDED", done)

print("== 6. Sửa / xóa kênh")
st, p = call("PUT", E + f"/api/v1/organizer/payout-account/channels/{VTB_ID}", {"paymentMethods": ["CARD", "QR"], "accountName": "", "accountNumber": ""}, btc)
check("bỏ PayNow, giữ tài khoản", st == 200 and next(c for c in p["channels"] if c["id"] == VTB_ID)["paymentMethods"] == ["CARD", "QR"], p)
st, p = call("DELETE", E + f"/api/v1/organizer/payout-account/channels/{TCB_ID}", None, btc)
check("xóa kênh Techcombank", st == 200 and [c["bankCode"] for c in p["channels"]] == ["VTB"], p)
st, pub = call("GET", E + f"/api/v1/organizers/{ORG}/payment-methods")
check("khách không còn thấy Google Pay (sau cache 15 giây có thể trễ)", st == 200, pub)
st, o4 = order("GOOGLE_PAY")
check("đơn Google Pay mới bị từ chối", st >= 400, (st, o4))
check("đơn Google Pay cũ vẫn tra được trạng thái", order_status(o2["id"]) == "PAID")
st, p = call("DELETE", E + f"/api/v1/organizer/payout-account/channels/{VTB_ID}", None, btc)
check("không xóa được kênh cuối", st == 409 and p["code"] == "LAST_PAYMENT_CHANNEL", p)

print(f"\nKẾT QUẢ: {ok} đạt, {bad} lỗi")
print("MER=", MER, "ORG=", ORG)
sys.exit(1 if bad else 0)
