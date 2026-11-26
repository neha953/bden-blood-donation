import com.sun.net.httpserver.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class Main {
  static final String DB_URL = "jdbc:oracle:thin:@//localhost:1521/orclpdb", DB_USER = "bden", DB_PASS = "bden123";
  static final Set<String> GROUPS = Set.of("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-");
  static final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();
  static final Map<String, Route> routes = new HashMap<>();

  interface Handler { Object run(Ctx c) throws Exception; }
  record Route(String auth, Handler h) {}
  static class ApiError extends RuntimeException {
    final int code;
    ApiError(int code, String m) { super(m); this.code = code; }
  }

  static class Ctx {
    final HttpExchange ex; final Map<String, String> p = new HashMap<>();
    Map<String, Object> user; String sid, cookie;
    Ctx(HttpExchange ex) throws IOException {
      this.ex = ex;
      parse(ex.getRequestURI().getRawQuery());
      if (!ex.getRequestMethod().equals("GET")) parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String ck = ex.getRequestHeaders().getFirst("Cookie");
      if (ck != null) for (String part : ck.split(";")) {
        String[] kv = part.trim().split("=", 2);
        if (kv.length == 2 && kv[0].equals("sid") && sessions.containsKey(kv[1])) { sid = kv[1]; user = sessions.get(sid); }
      }
    }
    void parse(String s) {
      if (s == null || s.isEmpty()) return;
      for (String pair : s.split("&")) { String[] kv = pair.split("=", 2); p.put(dec(kv[0]), kv.length > 1 ? dec(kv[1]) : ""); }
    }
    static String dec(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
    String s(String k) { String v = p.get(k); return v == null ? "" : v.trim(); }
    String bg() { return s("blood_group").replace(' ', '+').toUpperCase(); }
    long n(String k) { try { return Long.parseLong(s(k)); } catch (Exception e) { throw new ApiError(400, "Missing or invalid " + k); } }
    long uid() { return ((Number) user.get("user_id")).longValue(); }
    String role() { return (String) user.get("role"); }
  }

  // ---------- database helpers ----------
  static Connection db() throws SQLException { return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS); }
  static List<Map<String, Object>> q(String sql, Object... a) throws SQLException {
    try (Connection c = db(); PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < a.length; i++) ps.setObject(i + 1, a[i]);
      try (ResultSet rs = ps.executeQuery()) { return rows(rs); }
    }
  }
  static int u(String sql, Object... a) throws SQLException {
    try (Connection c = db(); PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < a.length; i++) ps.setObject(i + 1, a[i]);
      return ps.executeUpdate();
    }
  }
  static List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
    ResultSetMetaData m = rs.getMetaData(); List<Map<String, Object>> out = new ArrayList<>();
    while (rs.next()) {
      Map<String, Object> r = new LinkedHashMap<>();
      for (int i = 1; i <= m.getColumnCount(); i++) {
        Object v = rs.getObject(i);
        if (v instanceof Timestamp t) v = t.toString().substring(0, 16);
        else if (v instanceof java.sql.Date d) v = d.toString();
        else if (v instanceof BigDecimal b) v = b.scale() <= 0 ? (Object) b.longValue() : (Object) b.doubleValue();
        r.put(m.getColumnLabel(i).toLowerCase(), v);
      }
      out.add(r);
    }
    return out;
  }
  static void log(long uid, String action, String d) throws SQLException {
    u("INSERT INTO system_logs(user_id,action,details) VALUES(?,?,?)", uid, action, d);
  }
  static String nz(String s) { return s == null || s.isEmpty() ? null : s; }
  static Map<String, Object> m(Object... kv) {
    Map<String, Object> r = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
    return r;
  }
  static String hash(String s) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
    return sb.toString();
  }
  static String sqlMsg(SQLException e) {
    String m = String.valueOf(e.getMessage());
    int i = m.indexOf("ORA-200");
    if (i >= 0) { String t = m.substring(i + 10).trim(); int nl = t.indexOf('\n'); return nl > 0 ? t.substring(0, nl) : t; }
    if (m.contains("ORA-00001")) return "That value already exists (duplicate), for example the email is already registered.";
    if (m.contains("ORA-02290")) return "A value is not allowed by the database rules (check blood group, units or weight).";
    if (m.contains("ORA-02291")) return "A referenced record does not exist.";
    if (m.contains("ORA-02292")) return "This record is in use and cannot be deleted.";
    int nl = m.indexOf('\n');
    return "Database error: " + (nl > 0 ? m.substring(0, nl) : m);
  }

  // ---------- JSON out ----------
  static String js(Object o) {
    if (o == null) return "null";
    if (o instanceof Number || o instanceof Boolean) return o.toString();
    if (o instanceof Map<?, ?> mp) {
      StringBuilder sb = new StringBuilder("{"); boolean f = true;
      for (var e : mp.entrySet()) { if (!f) sb.append(','); f = false; sb.append(js(String.valueOf(e.getKey()))).append(':').append(js(e.getValue())); }
      return sb.append('}').toString();
    }
    if (o instanceof Collection<?> col) {
      StringBuilder sb = new StringBuilder("["); boolean f = true;
      for (Object x : col) { if (!f) sb.append(','); f = false; sb.append(js(x)); }
      return sb.append(']').toString();
    }
    String s = o.toString(); StringBuilder sb = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      switch (ch) {
        case '"' -> sb.append("\\\""); case '\\' -> sb.append("\\\\"); case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r"); case '\t' -> sb.append("\\t");
        default -> { if (ch < 32) sb.append(String.format("\\u%04x", (int) ch)); else sb.append(ch); }
      }
    }
    return sb.append('"').toString();
  }

  // ---------- server ----------
  static void send(HttpExchange ex, int code, String type, byte[] b, String cookie) throws IOException {
    ex.getResponseHeaders().set("Content-Type", type);
    if (cookie != null) ex.getResponseHeaders().add("Set-Cookie", cookie);
    ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
    try (OutputStream o = ex.getResponseBody()) { o.write(b); }
  }
  static void err(HttpExchange ex, int code, String msg) throws IOException {
    send(ex, code, "application/json; charset=utf-8", js(m("error", msg)).getBytes(StandardCharsets.UTF_8), null);
  }
  static String mime(String f) {
    if (f.endsWith(".html")) return "text/html; charset=utf-8"; if (f.endsWith(".css")) return "text/css; charset=utf-8";
    if (f.endsWith(".js")) return "application/javascript; charset=utf-8"; if (f.endsWith(".svg")) return "image/svg+xml";
    if (f.endsWith(".png")) return "image/png"; if (f.endsWith(".jpg")) return "image/jpeg"; if (f.endsWith(".ico")) return "image/x-icon";
    return "application/octet-stream";
  }
  static void handle(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    if (path.startsWith("/api/")) { api(ex, path); return; }
    Path root = Paths.get("web").toAbsolutePath().normalize();
    Path f = root.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
    if (!f.startsWith(root) || !Files.isRegularFile(f)) { send(ex, 404, "text/plain", "Not found".getBytes(), null); return; }
    send(ex, 200, mime(f.toString()), Files.readAllBytes(f), null);
  }
  static void api(HttpExchange ex, String path) throws IOException {
    Ctx c = new Ctx(ex);
    try {
      Route r = routes.get(ex.getRequestMethod() + " " + path);
      if (r == null) throw new ApiError(404, "Not found");
      if (r.auth() != null && c.user == null) throw new ApiError(401, "Please log in first");
      if ("ADMIN".equals(r.auth()) && !"ADMIN".equals(c.role())) throw new ApiError(403, "Admins only");
      Object out = r.h().run(c);
      send(ex, 200, "application/json; charset=utf-8", js(out).getBytes(StandardCharsets.UTF_8), c.cookie);
    } catch (ApiError e) { err(ex, e.code, e.getMessage()); }
    catch (SQLException e) { err(ex, 400, sqlMsg(e)); }
    catch (Exception e) { e.printStackTrace(); err(ex, 500, "Server error"); }
  }
  static void r(String method, String path, String auth, Handler h) { routes.put(method + " " + path, new Route(auth, h)); }

  public static void main(String[] args) throws Exception {
    routes();
    HttpServer s = HttpServer.create(new java.net.InetSocketAddress(8080), 0);
    s.createContext("/", Main::handle);
    s.setExecutor(Executors.newFixedThreadPool(8));
    s.start();
    System.out.println("BDEN running at http://localhost:8080");
    try (Connection c = db()) { System.out.println("Oracle connection OK"); }
    catch (Exception e) { System.out.println("Oracle connection FAILED: " + e.getMessage()); }
  }

  // ---------- API routes ----------
  static void routes() {
    // --- public / auth ---
    r("POST", "/api/register", null, c -> {
      String role = c.s("role").equals("PATIENT") ? "PATIENT" : "DONOR";
      if (c.s("full_name").isEmpty() || !c.s("email").contains("@") || c.s("password").length() < 6)
        throw new ApiError(400, "Name, a valid email and a password of at least 6 characters are required");
      Double w = null; java.sql.Date dob = null; String g = null;
      if (role.equals("DONOR")) {
        if (!GROUPS.contains(c.bg())) throw new ApiError(400, "Choose a valid blood group");
        try { w = Double.parseDouble(c.s("weight_kg")); } catch (Exception e) { throw new ApiError(400, "Enter your weight in kg"); }
        if (w < 50) throw new ApiError(400, "Donors must weigh at least 50 kg");
        if (!c.s("date_of_birth").isEmpty()) try { dob = java.sql.Date.valueOf(c.s("date_of_birth")); } catch (Exception e) { throw new ApiError(400, "Invalid date of birth"); }
        if (List.of("MALE", "FEMALE", "OTHER").contains(c.s("gender").toUpperCase())) g = c.s("gender").toUpperCase();
      }
      u("INSERT INTO users(full_name,email,password_hash,phone,city,role) VALUES(?,?,?,?,?,?)",
        c.s("full_name"), c.s("email"), hash(c.s("password")), c.s("phone"), c.s("city"), role);
      if (role.equals("DONOR"))
        u("INSERT INTO donors(user_id,blood_group,gender,date_of_birth,weight_kg) VALUES((SELECT user_id FROM users WHERE LOWER(email)=LOWER(?)),?,?,?,?)",
          c.s("email"), c.bg(), g, dob, w);
      return m("ok", true, "message", role.equals("DONOR") ? "Registered. An admin will verify your donor profile." : "Registered. You can log in now.");
    });
    r("POST", "/api/login", null, c -> {
      var rows = q("SELECT user_id,full_name,role,status,password_hash FROM users WHERE LOWER(email)=LOWER(?)", c.s("email"));
      if (rows.isEmpty() || !hash(c.s("password")).equals(rows.get(0).get("password_hash"))) throw new ApiError(401, "Invalid email or password");
      var row = rows.get(0);
      if ("BLOCKED".equals(row.get("status"))) throw new ApiError(403, "Your account is blocked. Contact the admin.");
      Map<String, Object> user = m("user_id", row.get("user_id"), "full_name", row.get("full_name"), "role", row.get("role"));
      String token = UUID.randomUUID().toString(); sessions.put(token, user);
      c.cookie = "sid=" + token + "; Path=/; HttpOnly; SameSite=Lax";
      log(((Number) user.get("user_id")).longValue(), "LOGIN", "User logged in");
      return m("user", user);
    });
    r("POST", "/api/logout", null, c -> { if (c.sid != null) sessions.remove(c.sid); c.cookie = "sid=; Path=/; Max-Age=0"; return m("ok", true); });
    r("GET", "/api/me", null, c -> m("user", c.user));
    r("GET", "/api/hospitals", "USER", c -> q("SELECT hospital_id,name,city,address,phone FROM hospitals WHERE status='ACTIVE' ORDER BY name"));

    // --- user modules ---
    r("GET", "/api/dashboard", "USER", c -> {
      var donor = q("SELECT blood_group,last_donation_date,fn_next_eligible_date(donor_id) AS next_eligible,verified,is_available,"
        + "(SELECT COUNT(*) FROM donations x WHERE x.donor_id=d.donor_id) AS total_donations FROM donors d WHERE user_id=?", c.uid());
      return m("name", c.user.get("full_name"), "role", c.role(),
        "donor", donor.isEmpty() ? null : donor.get(0),
        "requestsByStatus", q("SELECT status, COUNT(*) AS n FROM blood_requests WHERE requester_id=? GROUP BY status", c.uid()),
        "openEmergencies", q("SELECT COUNT(*) AS n FROM blood_requests WHERE status IN ('PENDING','APPROVED') AND urgency='CRITICAL'").get(0).get("n"));
    });
    r("GET", "/api/profile", "USER", c -> {
      var u = q("SELECT user_id,full_name,email,phone,city,role FROM users WHERE user_id=?", c.uid()).get(0);
      var d = q("SELECT blood_group,gender,weight_kg,is_available,verified FROM donors WHERE user_id=?", c.uid());
      return m("user", u, "donor", d.isEmpty() ? null : d.get(0));
    });
    r("POST", "/api/profile", "USER", c -> {
      u("UPDATE users SET full_name=?, phone=?, city=? WHERE user_id=?", c.s("full_name"), c.s("phone"), c.s("city"), c.uid());
      c.user.put("full_name", c.s("full_name"));
      if (!c.s("weight_kg").isEmpty())
        u("UPDATE donors SET weight_kg=?, is_available=? WHERE user_id=?", Double.parseDouble(c.s("weight_kg")), c.s("is_available").equals("0") ? 0 : 1, c.uid());
      log(c.uid(), "PROFILE_UPDATE", "Profile updated");
      return m("ok", true);
    });
    r("GET", "/api/donors/search", "USER", c ->
      q("SELECT donor_id,full_name,phone,city,blood_group,next_eligible_date FROM v_available_donors "
        + "WHERE (? IS NULL OR blood_group=?) AND (? IS NULL OR UPPER(city) LIKE '%'||UPPER(?)||'%') ORDER BY full_name",
        nz(c.bg()), nz(c.bg()), nz(c.s("city")), nz(c.s("city"))));
    r("POST", "/api/requests", "USER", c -> {
      if (!GROUPS.contains(c.bg())) throw new ApiError(400, "Choose a valid blood group");
      if (c.s("patient_name").isEmpty() || c.s("city").isEmpty()) throw new ApiError(400, "Patient name and city are required");
      u("INSERT INTO blood_requests(requester_id,hospital_id,patient_name,blood_group,units_needed,urgency,city) VALUES(?,?,?,?,?,?,?)",
        c.uid(), c.n("hospital_id"), c.s("patient_name"), c.bg(), c.n("units_needed"), c.s("urgency").isEmpty() ? "NORMAL" : c.s("urgency"), c.s("city"));
      return m("ok", true, "message", "Request submitted. An admin will review it shortly.");
    });
    r("GET", "/api/requests/mine", "USER", c ->
      q("SELECT r.request_id,r.patient_name,r.blood_group,r.units_needed,r.urgency,r.status,r.created_at,h.name AS hospital "
        + "FROM blood_requests r JOIN hospitals h ON h.hospital_id=r.hospital_id WHERE r.requester_id=? ORDER BY r.request_id DESC", c.uid()));
    r("GET", "/api/donations/mine", "USER", c ->
      q("SELECT n.donation_id,n.donation_date,n.units,h.name AS hospital FROM donations n JOIN donors d ON d.donor_id=n.donor_id "
        + "JOIN hospitals h ON h.hospital_id=n.hospital_id WHERE d.user_id=? ORDER BY n.donation_date DESC", c.uid()));

    // --- admin modules ---
    r("GET", "/api/admin/donors", "ADMIN", c ->
      q("SELECT d.donor_id,u.user_id,u.full_name,u.email,u.phone,u.city,d.blood_group,d.verified,d.is_available,d.last_donation_date,u.status "
        + "FROM donors d JOIN users u ON u.user_id=d.user_id ORDER BY d.donor_id DESC"));
    r("POST", "/api/admin/donors/verify", "ADMIN", c -> {
      u("UPDATE donors SET verified=? WHERE donor_id=?", c.s("verified").equals("1") ? 1 : 0, c.n("donor_id"));
      log(c.uid(), "ADMIN_ACTION", "Donor #" + c.n("donor_id") + " verified=" + c.s("verified")); return m("ok", true);
    });
    r("POST", "/api/admin/users/status", "ADMIN", c -> {
      u("UPDATE users SET status=? WHERE user_id=? AND role<>'ADMIN'", c.s("status"), c.n("user_id"));
      log(c.uid(), "ADMIN_ACTION", "User #" + c.n("user_id") + " set to " + c.s("status")); return m("ok", true);
    });
    r("GET", "/api/admin/hospitals", "ADMIN", c -> q("SELECT hospital_id,name,city,address,phone,status FROM hospitals ORDER BY hospital_id"));
    r("POST", "/api/admin/hospitals/add", "ADMIN", c -> {
      if (c.s("name").isEmpty() || c.s("city").isEmpty()) throw new ApiError(400, "Hospital name and city are required");
      u("INSERT INTO hospitals(name,city,address,phone) VALUES(?,?,?,?)", c.s("name"), c.s("city"), c.s("address"), c.s("phone"));
      log(c.uid(), "ADMIN_ACTION", "Hospital added: " + c.s("name")); return m("ok", true);
    });
    r("POST", "/api/admin/hospitals/status", "ADMIN", c -> {
      u("UPDATE hospitals SET status=? WHERE hospital_id=?", c.s("status"), c.n("hospital_id")); return m("ok", true);
    });
    r("GET", "/api/admin/requests", "ADMIN", c ->
      q("SELECT r.request_id,r.patient_name,r.blood_group,r.units_needed,r.urgency,r.city,r.status,r.created_at,h.name AS hospital,u.full_name AS requested_by "
        + "FROM blood_requests r JOIN hospitals h ON h.hospital_id=r.hospital_id JOIN users u ON u.user_id=r.requester_id "
        + "WHERE (? IS NULL OR r.status=?) ORDER BY r.request_id DESC", nz(c.s("status")), nz(c.s("status"))));
    r("POST", "/api/admin/requests/status", "ADMIN", c -> {
      if (!List.of("PENDING", "APPROVED", "REJECTED", "FULFILLED").contains(c.s("status"))) throw new ApiError(400, "Invalid status");
      u("BEGIN sp_update_request_status(?,?,?); END;", c.n("request_id"), c.s("status"), c.uid()); return m("ok", true);
    });
    r("GET", "/api/admin/requests/matches", "ADMIN", c -> {
      try (Connection cn = db(); CallableStatement cs = cn.prepareCall("{call sp_match_donors(?,?)}")) {
        cs.setLong(1, c.n("request_id")); cs.registerOutParameter(2, -10); cs.execute();
        try (ResultSet rs = (ResultSet) cs.getObject(2)) { return rows(rs); }
      }
    });
    r("POST", "/api/admin/donations/add", "ADMIN", c -> {
      Long req = c.s("request_id").isEmpty() ? null : c.n("request_id");
      u("INSERT INTO donations(donor_id,hospital_id,request_id,units) VALUES(?,?,?,?)", c.n("donor_id"), c.n("hospital_id"), req, c.s("units").isEmpty() ? 1 : c.n("units"));
      log(c.uid(), "DONATION_RECORDED", "Donor #" + c.n("donor_id") + " donated at hospital #" + c.n("hospital_id"));
      return m("ok", true, "message", "Donation recorded and stock updated.");
    });
    r("GET", "/api/admin/stock", "ADMIN", c ->
      q("SELECT s.hospital_id,h.name AS hospital,s.blood_group,s.units_available FROM blood_stock s JOIN hospitals h ON h.hospital_id=s.hospital_id ORDER BY h.name,s.blood_group"));
    r("POST", "/api/admin/stock/set", "ADMIN", c -> {
      u("MERGE INTO blood_stock s USING (SELECT ? AS hid, ? AS bg FROM dual) x ON (s.hospital_id=x.hid AND s.blood_group=x.bg) "
        + "WHEN MATCHED THEN UPDATE SET s.units_available=? WHEN NOT MATCHED THEN INSERT(hospital_id,blood_group,units_available) VALUES(x.hid,x.bg,?)",
        c.n("hospital_id"), c.bg(), c.n("units"), c.n("units"));
      log(c.uid(), "STOCK_UPDATE", "Stock set: hospital #" + c.n("hospital_id") + " " + c.bg() + " = " + c.n("units")); return m("ok", true);
    });
    r("GET", "/api/admin/reports", "ADMIN", c -> m(
      "totals", q("SELECT (SELECT COUNT(*) FROM users WHERE role<>'ADMIN') AS total_users, (SELECT COUNT(*) FROM donors) AS total_donors, "
        + "(SELECT COUNT(*) FROM hospitals) AS total_hospitals, (SELECT COUNT(*) FROM blood_requests) AS total_requests, "
        + "(SELECT COUNT(*) FROM donations) AS total_donations FROM dual").get(0),
      "requestsByStatus", q("SELECT status, COUNT(*) AS n FROM blood_requests GROUP BY status"),
      "donorsByGroup", q("SELECT blood_group, COUNT(*) AS n FROM donors GROUP BY blood_group ORDER BY blood_group"),
      "stock", q("SELECT blood_group, total_units FROM v_stock_summary ORDER BY blood_group"),
      "monthly", q("SELECT TO_CHAR(donation_date,'YYYY-MM') AS month, COUNT(*) AS n FROM donations GROUP BY TO_CHAR(donation_date,'YYYY-MM') ORDER BY 1")));
    r("GET", "/api/admin/logs", "ADMIN", c ->
      q("SELECT * FROM (SELECT l.log_id, TO_CHAR(l.log_time,'YYYY-MM-DD HH24:MI') AS log_time, l.action, l.details, u.full_name "
        + "FROM system_logs l LEFT JOIN users u ON u.user_id=l.user_id ORDER BY l.log_id DESC) WHERE ROWNUM<=200"));
  }
}
