import hashlib
import re
import datetime
import oracledb
from flask import Flask, request, session, jsonify, send_from_directory

app = Flask(__name__, static_folder='web', static_url_path='')
app.secret_key = 'bden-semester-project-secret'
DSN = 'localhost:1521/orclpdb'
DB_USER, DB_PASS = 'bden', 'bden123'
GROUPS = {'A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'}


class ApiError(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code, self.msg = code, msg


# ---------- database helpers ----------
def db():
    return oracledb.connect(user=DB_USER, password=DB_PASS, dsn=DSN)


def _sql(sql):
    n = iter(range(1, 100))
    return re.sub(r'\?', lambda m: ':%d' % next(n), sql)


def conv(v):
    if isinstance(v, datetime.datetime):
        return v.strftime('%Y-%m-%d %H:%M')
    if isinstance(v, datetime.date):
        return v.isoformat()
    return v


def rows(cur):
    cols = [d[0].lower() for d in cur.description]
    return [dict(zip(cols, map(conv, r))) for r in cur]


def q(sql, *a):
    with db() as c:
        cur = c.cursor()
        cur.execute(_sql(sql), list(a))
        return rows(cur)


def u(sql, *a):
    with db() as c:
        cur = c.cursor()
        cur.execute(_sql(sql), list(a))
        c.commit()
        return cur.rowcount


def log(uid, action, details):
    u("INSERT INTO system_logs(user_id,action,details) VALUES(?,?,?)", uid, action, details)


def nz(s):
    return s if s else None


def s(k):
    return (request.values.get(k) or '').strip()


def bg():
    return s('blood_group').replace(' ', '+').upper()


def n(k):
    try:
        return int(s(k))
    except ValueError:
        raise ApiError(400, 'Missing or invalid ' + k)


def uid():
    return session['user']['user_id']


def hash_pw(p):
    return hashlib.sha256(p.encode('utf-8')).hexdigest()


def sql_msg(e):
    m = str(e)
    i = m.find('ORA-200')
    if i >= 0:
        return m[i + 10:].strip().split('\n')[0]
    if 'ORA-00001' in m:
        return 'That value already exists (duplicate), for example the email is already registered.'
    if 'ORA-02290' in m:
        return 'A value is not allowed by the database rules (check blood group, units or weight).'
    if 'ORA-02291' in m:
        return 'A referenced record does not exist.'
    return 'Database error: ' + m.split('\n')[0]


def api(path, method='GET', auth=None):
    def deco(f):
        def wrapper():
            user = session.get('user')
            if auth and not user:
                raise ApiError(401, 'Please log in first')
            if auth == 'ADMIN' and user['role'] != 'ADMIN':
                raise ApiError(403, 'Admins only')
            return jsonify(f())
        wrapper.__name__ = f.__name__
        app.route('/api/' + path, methods=[method])(wrapper)
        return f
    return deco


@app.errorhandler(ApiError)
def on_api_error(e):
    return jsonify(error=e.msg), e.code


@app.errorhandler(oracledb.DatabaseError)
def on_db_error(e):
    return jsonify(error=sql_msg(e)), 400


@app.errorhandler(Exception)
def on_error(e):
    from werkzeug.exceptions import HTTPException
    if isinstance(e, HTTPException):
        return jsonify(error=e.description), e.code
    app.logger.exception(e)
    return jsonify(error='Server error'), 500


@app.route('/')
def home():
    return send_from_directory('web', 'index.html')


# ---------- auth ----------
@api('register', 'POST')
def register():
    role = 'PATIENT' if s('role') == 'PATIENT' else 'DONOR'
    if not s('full_name') or '@' not in s('email') or len(s('password')) < 6:
        raise ApiError(400, 'Name, a valid email and a password of at least 6 characters are required')
    w = dob = g = None
    if role == 'DONOR':
        if bg() not in GROUPS:
            raise ApiError(400, 'Choose a valid blood group')
        try:
            w = float(s('weight_kg'))
        except ValueError:
            raise ApiError(400, 'Enter your weight in kg')
        if w < 50:
            raise ApiError(400, 'Donors must weigh at least 50 kg')
        if s('date_of_birth'):
            try:
                dob = datetime.date.fromisoformat(s('date_of_birth'))
            except ValueError:
                raise ApiError(400, 'Invalid date of birth')
        if s('gender').upper() in ('MALE', 'FEMALE', 'OTHER'):
            g = s('gender').upper()
    u("INSERT INTO users(full_name,email,password_hash,phone,city,role) VALUES(?,?,?,?,?,?)",
      s('full_name'), s('email'), hash_pw(s('password')), nz(s('phone')), nz(s('city')), role)
    if role == 'DONOR':
        u("INSERT INTO donors(user_id,blood_group,gender,date_of_birth,weight_kg) "
          "VALUES((SELECT user_id FROM users WHERE LOWER(email)=LOWER(?)),?,?,?,?)",
          s('email'), bg(), g, dob, w)
    return {'ok': True, 'message': 'Registered. An admin will verify your donor profile.' if role == 'DONOR'
            else 'Registered. You can log in now.'}


@api('login', 'POST')
def login():
    r = q("SELECT user_id,full_name,role,status,password_hash FROM users WHERE LOWER(email)=LOWER(?)", s('email'))
    if not r or hash_pw(s('password')) != r[0]['password_hash']:
        raise ApiError(401, 'Invalid email or password')
    r = r[0]
    if r['status'] == 'BLOCKED':
        raise ApiError(403, 'Your account is blocked. Contact the admin.')
    user = {'user_id': r['user_id'], 'full_name': r['full_name'], 'role': r['role']}
    session['user'] = user
    log(user['user_id'], 'LOGIN', 'User logged in')
    return {'user': user}


@api('logout', 'POST')
def logout():
    session.clear()
    return {'ok': True}


@api('me')
def me():
    return {'user': session.get('user')}


@api('hospitals', auth='USER')
def hospitals():
    return q("SELECT hospital_id,name,city,address,phone FROM hospitals WHERE status='ACTIVE' ORDER BY name")


# ---------- user modules ----------
@api('dashboard', auth='USER')
def dashboard():
    d = q("SELECT blood_group,last_donation_date,fn_next_eligible_date(donor_id) AS next_eligible,verified,is_available,"
          "(SELECT COUNT(*) FROM donations x WHERE x.donor_id=d.donor_id) AS total_donations FROM donors d WHERE user_id=?", uid())
    return {'name': session['user']['full_name'], 'role': session['user']['role'], 'donor': d[0] if d else None,
            'requestsByStatus': q("SELECT status, COUNT(*) AS n FROM blood_requests WHERE requester_id=? GROUP BY status", uid()),
            'openEmergencies': q("SELECT COUNT(*) AS n FROM blood_requests WHERE status IN ('PENDING','APPROVED') AND urgency='CRITICAL'")[0]['n']}


@api('profile', auth='USER')
def profile_get():
    d = q("SELECT blood_group,gender,weight_kg,is_available,verified FROM donors WHERE user_id=?", uid())
    return {'user': q("SELECT user_id,full_name,email,phone,city,role FROM users WHERE user_id=?", uid())[0],
            'donor': d[0] if d else None}


@api('profile', 'POST', 'USER')
def profile_set():
    u("UPDATE users SET full_name=?, phone=?, city=? WHERE user_id=?", s('full_name'), nz(s('phone')), nz(s('city')), uid())
    session['user'] = dict(session['user'], full_name=s('full_name'))
    if s('weight_kg'):
        u("UPDATE donors SET weight_kg=?, is_available=? WHERE user_id=?", float(s('weight_kg')), 0 if s('is_available') == '0' else 1, uid())
    log(uid(), 'PROFILE_UPDATE', 'Profile updated')
    return {'ok': True}


@api('donors/search', auth='USER')
def donor_search():
    b, c = nz(bg()), nz(s('city'))
    return q("SELECT donor_id,full_name,phone,city,blood_group,next_eligible_date FROM v_available_donors "
             "WHERE (? IS NULL OR blood_group=?) AND (? IS NULL OR UPPER(city) LIKE '%'||UPPER(?)||'%') ORDER BY full_name", b, b, c, c)


@api('requests', 'POST', 'USER')
def request_add():
    if bg() not in GROUPS:
        raise ApiError(400, 'Choose a valid blood group')
    if not s('patient_name') or not s('city'):
        raise ApiError(400, 'Patient name and city are required')
    u("INSERT INTO blood_requests(requester_id,hospital_id,patient_name,blood_group,units_needed,urgency,city) VALUES(?,?,?,?,?,?,?)",
      uid(), n('hospital_id'), s('patient_name'), bg(), n('units_needed'), s('urgency') or 'NORMAL', s('city'))
    return {'ok': True, 'message': 'Request submitted. An admin will review it shortly.'}


@api('requests/mine', auth='USER')
def requests_mine():
    return q("SELECT r.request_id,r.patient_name,r.blood_group,r.units_needed,r.urgency,r.status,r.created_at,h.name AS hospital "
             "FROM blood_requests r JOIN hospitals h ON h.hospital_id=r.hospital_id WHERE r.requester_id=? ORDER BY r.request_id DESC", uid())


@api('donations/mine', auth='USER')
def donations_mine():
    return q("SELECT n.donation_id,n.donation_date,n.units,h.name AS hospital FROM donations n JOIN donors d ON d.donor_id=n.donor_id "
             "JOIN hospitals h ON h.hospital_id=n.hospital_id WHERE d.user_id=? ORDER BY n.donation_date DESC", uid())


# ---------- admin modules ----------
@api('admin/donors', auth='ADMIN')
def admin_donors():
    return q("SELECT d.donor_id,u.user_id,u.full_name,u.email,u.phone,u.city,d.blood_group,d.verified,d.is_available,d.last_donation_date,u.status "
             "FROM donors d JOIN users u ON u.user_id=d.user_id ORDER BY d.donor_id DESC")


@api('admin/donors/verify', 'POST', 'ADMIN')
def admin_verify():
    u("UPDATE donors SET verified=? WHERE donor_id=?", 1 if s('verified') == '1' else 0, n('donor_id'))
    log(uid(), 'ADMIN_ACTION', 'Donor #%d verified=%s' % (n('donor_id'), s('verified')))
    return {'ok': True}


@api('admin/users/status', 'POST', 'ADMIN')
def admin_user_status():
    u("UPDATE users SET status=? WHERE user_id=? AND role<>'ADMIN'", s('status'), n('user_id'))
    log(uid(), 'ADMIN_ACTION', 'User #%d set to %s' % (n('user_id'), s('status')))
    return {'ok': True}


@api('admin/hospitals', auth='ADMIN')
def admin_hospitals():
    return q("SELECT hospital_id,name,city,address,phone,status FROM hospitals ORDER BY hospital_id")


@api('admin/hospitals/add', 'POST', 'ADMIN')
def admin_hospital_add():
    if not s('name') or not s('city'):
        raise ApiError(400, 'Hospital name and city are required')
    u("INSERT INTO hospitals(name,city,address,phone) VALUES(?,?,?,?)", s('name'), s('city'), nz(s('address')), nz(s('phone')))
    log(uid(), 'ADMIN_ACTION', 'Hospital added: ' + s('name'))
    return {'ok': True}


@api('admin/hospitals/status', 'POST', 'ADMIN')
def admin_hospital_status():
    u("UPDATE hospitals SET status=? WHERE hospital_id=?", s('status'), n('hospital_id'))
    return {'ok': True}


@api('admin/requests', auth='ADMIN')
def admin_requests():
    st = nz(s('status'))
    return q("SELECT r.request_id,r.patient_name,r.blood_group,r.units_needed,r.urgency,r.city,r.status,r.created_at,h.name AS hospital,u.full_name AS requested_by "
             "FROM blood_requests r JOIN hospitals h ON h.hospital_id=r.hospital_id JOIN users u ON u.user_id=r.requester_id "
             "WHERE (? IS NULL OR r.status=?) ORDER BY r.request_id DESC", st, st)


@api('admin/requests/status', 'POST', 'ADMIN')
def admin_request_status():
    if s('status') not in ('PENDING', 'APPROVED', 'REJECTED', 'FULFILLED'):
        raise ApiError(400, 'Invalid status')
    u("BEGIN sp_update_request_status(?,?,?); END;", n('request_id'), s('status'), uid())
    return {'ok': True}


@api('admin/requests/matches', auth='ADMIN')
def admin_matches():
    with db() as c:
        out = c.cursor()
        c.cursor().callproc('sp_match_donors', [n('request_id'), out])
        return rows(out)


@api('admin/donations/add', 'POST', 'ADMIN')
def admin_donation_add():
    req = n('request_id') if s('request_id') else None
    u("INSERT INTO donations(donor_id,hospital_id,request_id,units) VALUES(?,?,?,?)",
      n('donor_id'), n('hospital_id'), req, n('units') if s('units') else 1)
    log(uid(), 'DONATION_RECORDED', 'Donor #%d donated at hospital #%d' % (n('donor_id'), n('hospital_id')))
    return {'ok': True, 'message': 'Donation recorded and stock updated.'}


@api('admin/stock', auth='ADMIN')
def admin_stock():
    return q("SELECT s.hospital_id,h.name AS hospital,s.blood_group,s.units_available FROM blood_stock s "
             "JOIN hospitals h ON h.hospital_id=s.hospital_id ORDER BY h.name,s.blood_group")


@api('admin/stock/set', 'POST', 'ADMIN')
def admin_stock_set():
    u("MERGE INTO blood_stock s USING (SELECT ? AS hid, ? AS bg FROM dual) x ON (s.hospital_id=x.hid AND s.blood_group=x.bg) "
      "WHEN MATCHED THEN UPDATE SET s.units_available=? WHEN NOT MATCHED THEN INSERT(hospital_id,blood_group,units_available) VALUES(x.hid,x.bg,?)",
      n('hospital_id'), bg(), n('units'), n('units'))
    log(uid(), 'STOCK_UPDATE', 'Stock set: hospital #%d %s = %d' % (n('hospital_id'), bg(), n('units')))
    return {'ok': True}


@api('admin/reports', auth='ADMIN')
def admin_reports():
    return {
        'totals': q("SELECT (SELECT COUNT(*) FROM users WHERE role<>'ADMIN') AS total_users, (SELECT COUNT(*) FROM donors) AS total_donors, "
                    "(SELECT COUNT(*) FROM hospitals) AS total_hospitals, (SELECT COUNT(*) FROM blood_requests) AS total_requests, "
                    "(SELECT COUNT(*) FROM donations) AS total_donations FROM dual")[0],
        'requestsByStatus': q("SELECT status, COUNT(*) AS n FROM blood_requests GROUP BY status"),
        'donorsByGroup': q("SELECT blood_group, COUNT(*) AS n FROM donors GROUP BY blood_group ORDER BY blood_group"),
        'stock': q("SELECT blood_group, total_units FROM v_stock_summary ORDER BY blood_group"),
        'monthly': q("SELECT TO_CHAR(donation_date,'YYYY-MM') AS month, COUNT(*) AS n FROM donations GROUP BY TO_CHAR(donation_date,'YYYY-MM') ORDER BY 1")}


@api('admin/logs', auth='ADMIN')
def admin_logs():
    return q("SELECT * FROM (SELECT l.log_id, TO_CHAR(l.log_time,'YYYY-MM-DD HH24:MI') AS log_time, l.action, l.details, u.full_name "
             "FROM system_logs l LEFT JOIN users u ON u.user_id=l.user_id ORDER BY l.log_id DESC) WHERE ROWNUM<=200")


if __name__ == '__main__':
    try:
        db().close()
        print('Oracle connection OK')
    except Exception as e:
        print('Oracle connection FAILED:', e)
    print('BDEN running at http://localhost:8080')
    app.run(host='127.0.0.1', port=8080, debug=False)
