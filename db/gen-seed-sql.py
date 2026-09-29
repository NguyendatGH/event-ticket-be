"""Sinh db/seed-dev.sql từ src/main/resources/seed/events.json. Chạy: python3 db/gen-seed-sql.py (từ bất kỳ đâu).

Một nguồn sự thật: psql chạy file này, DevDataSeeder / POST /dev/seed chạy đúng file này từ classpath
(pom.xml đưa db/seed-dev.sql vào classpath thành seed/seed-dev.sql). File KHÔNG có begin/commit để chạy được
trong transaction của Spring; psql dùng cờ -1 (--single-transaction).

Quy ước dữ liệu:
- quantity trong events.json = số vé CÒN LẠI (inventory.available). total_quantity = quantity + số vé seed đã bán.
- Đơn PAID rải trong 45 ngày gần nhất tính từ now() lúc chạy seed (sự kiện đã qua: trước giờ diễn), random có seed cố định.
- id: UUID v5 cố định (cùng namespace với bản cũ nên event/tier/user id không đổi). User/organizer tham chiếu qua email/slug
  (subselect) để chạy được cả trên DB có user/organizer do app tạo với id khác.
"""
import json, pathlib, random, unicodedata, uuid, re

BE = pathlib.Path(__file__).resolve().parents[1]
events = json.loads((BE / "src/main/resources/seed/events.json").read_text(encoding="utf-8"))
NS = uuid.UUID("6f1c4a0e-7f6d-4b1e-9d1a-2f4b5c6d7e8f")          # namespace cố định → id ổn định giữa các lần seed / các máy
# BCrypt của "password123" (cost 10). Đổi mật khẩu: python3 -c "import bcrypt;print(bcrypt.hashpw(b'...', bcrypt.gensalt(10)).decode())"
HASH = "$2b$10$EPFYj051vq.sjoof.eNRsuKUHo6JaszY7KGAJAfqvRu/hZSc/Gzsa"
FEE = 0                                                         # app.checkout.fee (không thu phí sàn)
# (họ tên, email, role, phone)
USERS = [("Nguyen Van A", "a@example.com", "CUSTOMER", "0912345678"),
         ("Tran Thi B", "b@example.com", "CUSTOMER", "0987654321"),
         ("Admin", "admin@example.com", "ADMIN", None),
         ("Le Hoang Nam", "organizer@example.com", "ORGANIZER", "0901111222"),
         ("Vo Thi Mai", "organizer2@example.com", "ORGANIZER", "0903333444")]
# Organizer có tài khoản + thông tin thêm (các BTC khác trong events.json không có user_id)
ORGANIZER_OWNERS = {
    "Sunrise Live": ("organizer@example.com", {"city": "Hà Nội", "website": "https://sunrise-live.example.com",
                                               "contact_email": "organizer@example.com", "contact_phone": "0901111222"}),
    "Saigon Jazz Club": ("organizer2@example.com", {"city": "TP.HCM", "website": "https://saigonjazz.example.com",
                                                    "contact_email": "organizer2@example.com", "contact_phone": "0903333444"}),
}
BUYERS = ["a@example.com", "b@example.com"]
# slug → số đơn PAID seed
PAST = {"sunrise-summer-sessions-2026", "jazz-nights-vol-7"}   # sự kiện đã diễn ra: đơn tính theo giờ diễn, không theo now()
ORDERS_PER_EVENT = {"the-lumiere-tour": 24, "sunrise-summer-sessions-2026": 10, "jazz-under-the-bridge": 8,
                    "jazz-nights-vol-7": 6, "saigon-sound-festival": 6, "dem-nhac-trinh": 4, "v-league-ha-noi-vs-hagl": 4}


def uid(kind, *parts): return str(uuid.uuid5(NS, kind + ":" + ":".join(parts)))
def q(s):  # text thường: nhân đôi dấu nháy đơn
    return "NULL" if s is None else "'" + str(s).replace("'", "''") + "'"
def j(obj):  # jsonb: dollar-quote để không phải escape gì, giữ nguyên key camelCase cho Hibernate/Jackson đọc lại
    return "$j$" + json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "$j$::jsonb"
def user(email): return f"(select id from users where email = {q(email)})"
def org(slug): return f"(select id from organizers where slug = {q(slug)})"
def slugify(name):  # giống V2__app_features.sql: bỏ dấu (đ → d), chữ thường, ký tự khác [a-z0-9] thành '-'
    s = unicodedata.normalize("NFD", name.replace("đ", "d").replace("Đ", "d"))
    s = "".join(c for c in s if unicodedata.category(c) != "Mn").lower()
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")


rnd = random.Random(20260927)
by_slug = {e["slug"]: e for e in events}

# ---------- sinh đơn trước để biết số vé đã bán mỗi tier ----------
orders = []          # dict: id, code, slug, email, when (biểu thức SQL), items [(tier, qty)], tickets [(ticket_id, tier)]
sold = {}            # (slug, tier name) → số vé
code = 1_000_000     # order_code seed: số nhỏ, không đụng mã thật (millis*1000)
for slug, n in ORDERS_PER_EVENT.items():
    e = by_slug[slug]
    past = slug in PAST
    for i in range(n):
        code += 1
        tier = rnd.choices(e["tiers"], weights=[max(t["quantity"], 5) for t in e["tiers"]])[0]
        qty = rnd.randint(1, min(3, tier["maxPerOrder"]))
        if past:   # sự kiện đã qua: trước giờ diễn 1–30 ngày
            when = f"(timestamptz {q(e['startsAt'])} - interval '{rnd.randint(1, 30)} days {rnd.randint(0, 23)} hours')"
        else:      # 0–44 ngày trước now(), không muộn hơn 1 ngày trước giờ diễn
            when = (f"least(now() - interval '{rnd.randint(0, 44)} days {rnd.randint(0, 23)} hours {rnd.randint(0, 59)} minutes', "
                    f"timestamptz {q(e['startsAt'])} - interval '1 day')")
        oid = uid("order", slug, str(i))
        o = {"id": oid, "code": code, "slug": slug, "email": rnd.choice(BUYERS), "when": when,
             "tier": tier, "qty": qty, "tickets": [uid("ticket", oid, str(k)) for k in range(qty)]}
        orders.append(o)
        sold[(slug, tier["name"])] = sold.get((slug, tier["name"]), 0) + qty

out = []
w = out.append
w("-- =====================================================================================================")
w("-- Seed dữ liệu dev (schema V1 + V2). Sinh tự động từ src/main/resources/seed/events.json bởi db/gen-seed-sql.py;")
w("-- sửa events.json / gen-seed-sql.py rồi chạy `python3 db/gen-seed-sql.py` thay vì sửa tay file này.")
w("--")
w("-- Cách chạy (schema phải có trước: app boot là Flyway tạo). -1 = một transaction:")
w("--   set -a; . ./.env; set +a")
w("--   PGPASSWORD=\"$DB_PASSWORD\" psql -h localhost -U \"$DB_USERNAME\" -d event-application-db -1 -v ON_ERROR_STOP=1 -f db/seed-dev.sql")
w("-- Hoặc gọi POST /dev/seed (profile dev): xóa event/đơn rồi chạy chính file này (classpath seed/seed-dev.sql).")
w("--")
w("-- Idempotent: id UUID v5 cố định, ON CONFLICT DO NOTHING nên chạy lại không nhân đôi dữ liệu.")
w("-- Thời gian đơn tính theo now() lúc chạy lần đầu (chạy lại không dời).")
w("-- Tài khoản (mật khẩu chung password123): a@example.com, b@example.com (CUSTOMER), admin@example.com (ADMIN),")
w("--   organizer@example.com (ORGANIZER, Sunrise Live), organizer2@example.com (ORGANIZER, Saigon Jazz Club)")
w("-- =====================================================================================================")
w("")
w("-- ---------- users ----------")
for name, email, role, phone in USERS:
    w(f"insert into users (id, full_name, email, password_hash, role, phone) values ({q(uid('user', email))}, {q(name)}, {q(email)}, {q(HASH)}, {q(role)}, {q(phone)}) on conflict (email) do nothing;")
w("")

w("-- ---------- organizers: mỗi tên BTC trong events.json một dòng; trùng slug (vd đã backfill ở V2) thì chỉ điền chỗ trống ----------")
seen = []
for e in events:
    o = e["organizer"]
    if o["name"] in seen: continue
    seen.append(o["name"])
    slug = slugify(o["name"])
    extra = ORGANIZER_OWNERS.get(o["name"], (None, {}))[1]
    w("insert into organizers (id, slug, name, description, verified, city, website, contact_email, contact_phone)")
    w(f"values ({q(uid('organizer', slug))}, {q(slug)}, {q(o['name'])}, {q(o.get('description'))}, {'true' if o.get('verified') else 'false'}, "
      f"{q(extra.get('city'))}, {q(extra.get('website'))}, {q(extra.get('contact_email'))}, {q(extra.get('contact_phone'))})")
    w("on conflict (slug) do update set description = coalesce(organizers.description, excluded.description),")
    w("  city = coalesce(organizers.city, excluded.city), website = coalesce(organizers.website, excluded.website),")
    w("  contact_email = coalesce(organizers.contact_email, excluded.contact_email), contact_phone = coalesce(organizers.contact_phone, excluded.contact_phone);")
for name, (email, _) in ORGANIZER_OWNERS.items():
    w(f"update organizers set user_id = {user(email)} where slug = {q(slugify(name))} and user_id is null")
    w(f"  and not exists (select 1 from organizers x where x.user_id = {user(email)});")
w("")

for e in events:
    eid = uid("event", e["slug"])
    listed = e["status"] in ("PUBLISHED", "UPCOMING")
    w(f"-- ---------- {e['name']} ({e['slug']}, {e['status']}) ----------")
    w("insert into events (id, slug, name, category, starts_at, ends_at, status, venue, cover_image_url, cover_image_alt, tagline, description, schedule, organizer_id, featured, published_at)")
    w("values (")
    w(f"  {q(eid)}, {q(e['slug'])}, {q(e['name'])}, {q(e['category'])},")
    w(f"  {q(e['startsAt'])}::timestamptz, {q(e.get('endsAt'))}{'::timestamptz' if e.get('endsAt') else ''}, {q(e['status'])},")
    w(f"  {j(e['venue'])},")
    w(f"  {q(e.get('coverImageUrl'))}, {q(e.get('coverImageAlt'))}, {q(e.get('tagline'))},")
    w(f"  {j(e.get('description', []))},")
    w(f"  {j(e.get('schedule', []))},")
    w(f"  {org(slugify(e['organizer']['name']))}, {'true' if e.get('featured') else 'false'},")
    w(f"  {'least(now(), ' + q(e['startsAt']) + '::timestamptz) - interval ' + q('60 days') if listed else 'NULL'}")
    w(") on conflict (slug) do nothing;")
    for t in e["tiers"]:
        tid = uid("tier", e["slug"], t["name"])
        total = t["quantity"] + sold.get((e["slug"], t["name"]), 0)
        # Tier đã có từ seed cũ (total = quantity) thì nâng total để total = available + đã bán
        w(f"insert into ticket_tiers (id, event_id, name, description, price, total_quantity, max_per_order) values ({q(tid)}, {q(eid)}, {q(t['name'])}, {q(t.get('description'))}, {t['price']}, {total}, {t['maxPerOrder']})"
          f" on conflict (id) do update set total_quantity = excluded.total_quantity where ticket_tiers.total_quantity < excluded.total_quantity;")
        w(f"insert into inventory (ticket_tier_id, available) values ({q(tid)}, {t['quantity']}) on conflict (ticket_tier_id) do nothing;")
    w("")

w("-- ---------- đơn PAID + payment + vé (owner a@/b@) ----------")
names = {u[1]: u[0] for u in USERS}
phones = {u[1]: u[3] for u in USERS}
for o in orders:
    e, t = by_slug[o["slug"]], o["tier"]
    tid = uid("tier", o["slug"], t["name"])
    sub = t["price"] * o["qty"]
    total = sub + FEE
    when, paid = o["when"], f"{o['when']} + interval '2 minutes'"
    w(f"insert into orders (id, order_code, event_id, status, subtotal_amount, fee_amount, total_amount, paid_amount, customer_name, customer_email, customer_phone, expires_at, created_at, updated_at, user_id, paid_at)")
    w(f"values ({q(o['id'])}, {o['code']}, {q(uid('event', o['slug']))}, 'PAID', {sub}, {FEE}, {total}, {total}, {q(names[o['email']])}, {q(o['email'])}, {q(phones[o['email']])},")
    w(f"  {when} + interval '15 minutes', {when}, {paid}, {user(o['email'])}, {paid}) on conflict (id) do nothing;")
    w(f"insert into order_items (id, order_id, ticket_tier_id, tier_name, quantity, unit_price) values ({q(uid('order-item', o['id']))}, {q(o['id'])}, {q(tid)}, {q(t['name'])}, {o['qty']}, {t['price']}) on conflict (id) do nothing;")
    w(f"insert into payments (id, order_id, provider, provider_payment_id, payment_link_id, provider_transaction_ref, amount, status, paid_at, created_at, updated_at)")
    w(f"values ({q(uid('payment', o['id']))}, {q(o['id'])}, 'MOCK', 'seed-{o['code']}', 'seed-{o['code']}', 'SEEDTX{o['code']}', {total}, 'PAID', {paid}, {when}, {paid}) on conflict (id) do nothing;")
    for tk in o["tickets"]:
        w(f"insert into tickets (id, order_id, ticket_tier_id, price, ticket_code, status, issued_at, updated_at, owner_id) values ({q(tk)}, {q(o['id'])}, {q(tid)}, {t['price']}, {q(uid('ticket-code', tk))}, 'ACTIVE', {paid}, {paid}, {user(o['email'])}) on conflict (id) do nothing;")
    # Bút toán thu, đúng bộ mà LedgerService.recordOrderPaid ghi. Không có nó thì đơn seed coi như chưa thu
    # đồng nào và RefundResultHandler.assertNotOverRefunded tự tắt -> hoàn tiền mất chặn trần.
    for acct, dr, amt in (("BANK_COLLECTION", "DEBIT", total), ("CUSTOMER_LIABILITY", "CREDIT", total - FEE), ("FEES", "CREDIT", FEE)):
        if amt > 0:
            w(f"insert into ledger_entries (id, account, direction, amount, ref_type, ref_id, occurred_at) values ({q(uid('ledger', o['id'], acct))}, {q(acct)}, {q(dr)}, {amt}, 'ORDER', {q(o['id'])}, {paid}) on conflict (id) do nothing;")
w("")

w("-- Kiểm tra nhanh:")
w("--   select (select count(*) from users) users, (select count(*) from organizers) organizers, (select count(*) from events) events,")
w("--          (select count(*) from orders where status = 'PAID') paid_orders, (select count(*) from tickets) tickets;")
(BE / "db/seed-dev.sql").write_text("\n".join(out) + "\n", encoding="utf-8")
print(f"wrote db/seed-dev.sql: {len(out)} lines, {len(events)} events, {sum(len(e['tiers']) for e in events)} tiers, "
      f"{len(orders)} orders, {sum(len(o['tickets']) for o in orders)} tickets")
