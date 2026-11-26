-- ============================================================
-- Blood Donation & Emergency Network (BDEN)
-- Oracle 19c schema: tables, views, triggers, procedures, seed data
-- Run as user BDEN connected to orclpdb
-- ============================================================
SET DEFINE OFF
SET SQLBLANKLINES ON
SET SERVEROUTPUT ON

-- ---------- TABLES ----------
CREATE TABLE users (
  user_id       NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  full_name     VARCHAR2(100) NOT NULL,
  email         VARCHAR2(120) NOT NULL UNIQUE,
  password_hash VARCHAR2(100) NOT NULL,
  phone         VARCHAR2(20),
  city          VARCHAR2(60),
  role          VARCHAR2(10) DEFAULT 'DONOR' NOT NULL
                CHECK (role IN ('DONOR','PATIENT','ADMIN')),
  status        VARCHAR2(10) DEFAULT 'ACTIVE' NOT NULL
                CHECK (status IN ('ACTIVE','BLOCKED')),
  created_at    DATE DEFAULT SYSDATE NOT NULL
);

CREATE TABLE donors (
  donor_id           NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id            NUMBER NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
  blood_group        VARCHAR2(3) NOT NULL
                     CHECK (blood_group IN ('A+','A-','B+','B-','AB+','AB-','O+','O-')),
  gender             VARCHAR2(10) CHECK (gender IN ('MALE','FEMALE','OTHER')),
  date_of_birth      DATE,
  weight_kg          NUMBER(5,1) CHECK (weight_kg >= 50),
  last_donation_date DATE,
  is_available       NUMBER(1) DEFAULT 1 NOT NULL CHECK (is_available IN (0,1)),
  verified           NUMBER(1) DEFAULT 0 NOT NULL CHECK (verified IN (0,1))
);

CREATE TABLE hospitals (
  hospital_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        VARCHAR2(120) NOT NULL,
  city        VARCHAR2(60) NOT NULL,
  address     VARCHAR2(200),
  phone       VARCHAR2(20),
  status      VARCHAR2(10) DEFAULT 'ACTIVE' NOT NULL
              CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE blood_requests (
  request_id    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  requester_id  NUMBER NOT NULL REFERENCES users(user_id),
  hospital_id   NUMBER NOT NULL REFERENCES hospitals(hospital_id),
  patient_name  VARCHAR2(100) NOT NULL,
  blood_group   VARCHAR2(3) NOT NULL
                CHECK (blood_group IN ('A+','A-','B+','B-','AB+','AB-','O+','O-')),
  units_needed  NUMBER(2) NOT NULL CHECK (units_needed BETWEEN 1 AND 20),
  urgency       VARCHAR2(10) DEFAULT 'NORMAL' NOT NULL
                CHECK (urgency IN ('NORMAL','URGENT','CRITICAL')),
  city          VARCHAR2(60) NOT NULL,
  status        VARCHAR2(10) DEFAULT 'PENDING' NOT NULL
                CHECK (status IN ('PENDING','APPROVED','REJECTED','FULFILLED')),
  created_at    DATE DEFAULT SYSDATE NOT NULL
);

CREATE TABLE donations (
  donation_id   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  donor_id      NUMBER NOT NULL REFERENCES donors(donor_id),
  hospital_id   NUMBER NOT NULL REFERENCES hospitals(hospital_id),
  request_id    NUMBER REFERENCES blood_requests(request_id),
  units         NUMBER(2) DEFAULT 1 NOT NULL CHECK (units BETWEEN 1 AND 2),
  donation_date DATE DEFAULT SYSDATE NOT NULL
);

CREATE TABLE blood_stock (
  hospital_id     NUMBER NOT NULL REFERENCES hospitals(hospital_id) ON DELETE CASCADE,
  blood_group     VARCHAR2(3) NOT NULL
                  CHECK (blood_group IN ('A+','A-','B+','B-','AB+','AB-','O+','O-')),
  units_available NUMBER(5) DEFAULT 0 NOT NULL CHECK (units_available >= 0),
  CONSTRAINT pk_blood_stock PRIMARY KEY (hospital_id, blood_group)
);

CREATE TABLE system_logs (
  log_id   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id  NUMBER,
  action   VARCHAR2(60) NOT NULL,
  details  VARCHAR2(300),
  log_time TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE INDEX idx_donors_bg ON donors(blood_group);
CREATE INDEX idx_users_city ON users(city);
CREATE INDEX idx_requests_status ON blood_requests(status);

-- ---------- FUNCTION ----------
CREATE OR REPLACE FUNCTION fn_next_eligible_date(p_donor_id NUMBER) RETURN DATE IS
  v_last DATE;
BEGIN
  SELECT last_donation_date INTO v_last FROM donors WHERE donor_id = p_donor_id;
  IF v_last IS NULL THEN
    RETURN TRUNC(SYSDATE);
  END IF;
  RETURN v_last + 90;
END;
/

-- ---------- VIEWS ----------
CREATE OR REPLACE VIEW v_available_donors AS
SELECT d.donor_id, u.user_id, u.full_name, u.phone, u.city,
       d.blood_group, d.last_donation_date,
       fn_next_eligible_date(d.donor_id) AS next_eligible_date
FROM donors d
JOIN users u ON u.user_id = d.user_id
WHERE d.is_available = 1
  AND d.verified = 1
  AND u.status = 'ACTIVE'
  AND (d.last_donation_date IS NULL OR SYSDATE - d.last_donation_date >= 90);

CREATE OR REPLACE VIEW v_pending_requests AS
SELECT r.request_id, r.patient_name, r.blood_group, r.units_needed, r.urgency,
       r.city, r.created_at, h.name AS hospital_name, u.full_name AS requested_by
FROM blood_requests r
JOIN hospitals h ON h.hospital_id = r.hospital_id
JOIN users u ON u.user_id = r.requester_id
WHERE r.status = 'PENDING';

CREATE OR REPLACE VIEW v_stock_summary AS
SELECT blood_group, SUM(units_available) AS total_units
FROM blood_stock
GROUP BY blood_group;

-- ---------- TRIGGERS ----------
CREATE OR REPLACE TRIGGER trg_donation_gap
BEFORE INSERT ON donations
FOR EACH ROW
DECLARE
  v_last DATE;
BEGIN
  SELECT last_donation_date INTO v_last FROM donors WHERE donor_id = :NEW.donor_id;
  IF v_last IS NOT NULL AND (:NEW.donation_date - v_last) < 90 THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Donor must wait 90 days between donations. Next eligible date: '
      || TO_CHAR(v_last + 90, 'DD-MON-YYYY'));
  END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_after_donation
AFTER INSERT ON donations
FOR EACH ROW
DECLARE
  v_bg VARCHAR2(3);
BEGIN
  SELECT blood_group INTO v_bg FROM donors WHERE donor_id = :NEW.donor_id;

  UPDATE donors SET last_donation_date = :NEW.donation_date
  WHERE donor_id = :NEW.donor_id;

  MERGE INTO blood_stock s
  USING (SELECT :NEW.hospital_id AS hid, v_bg AS bg FROM dual) x
  ON (s.hospital_id = x.hid AND s.blood_group = x.bg)
  WHEN MATCHED THEN UPDATE SET s.units_available = s.units_available + :NEW.units
  WHEN NOT MATCHED THEN INSERT (hospital_id, blood_group, units_available)
       VALUES (x.hid, x.bg, :NEW.units);
END;
/

CREATE OR REPLACE TRIGGER trg_request_log
AFTER INSERT OR UPDATE OF status ON blood_requests
FOR EACH ROW
BEGIN
  IF INSERTING THEN
    INSERT INTO system_logs(user_id, action, details)
    VALUES (:NEW.requester_id, 'REQUEST_CREATED',
            'Request #' || :NEW.request_id || ' for ' || :NEW.blood_group || ' (' || :NEW.urgency || ')');
  ELSE
    INSERT INTO system_logs(user_id, action, details)
    VALUES (:NEW.requester_id, 'REQUEST_' || :NEW.status,
            'Request #' || :NEW.request_id || ' changed from ' || :OLD.status || ' to ' || :NEW.status);
  END IF;
END;
/

-- ---------- PROCEDURES ----------
CREATE OR REPLACE PROCEDURE sp_match_donors(
  p_request_id NUMBER,
  p_result OUT SYS_REFCURSOR
) IS
  v_bg   VARCHAR2(3);
  v_city VARCHAR2(60);
BEGIN
  SELECT blood_group, city INTO v_bg, v_city
  FROM blood_requests WHERE request_id = p_request_id;

  OPEN p_result FOR
    SELECT donor_id, full_name, phone, city, blood_group
    FROM v_available_donors
    WHERE blood_group = v_bg AND UPPER(city) = UPPER(v_city)
    ORDER BY last_donation_date NULLS FIRST;
END;
/

CREATE OR REPLACE PROCEDURE sp_update_request_status(
  p_request_id NUMBER,
  p_status     VARCHAR2,
  p_admin_id   NUMBER
) IS
BEGIN
  UPDATE blood_requests SET status = p_status WHERE request_id = p_request_id;
  IF SQL%ROWCOUNT = 0 THEN
    RAISE_APPLICATION_ERROR(-20002, 'Request not found');
  END IF;
  INSERT INTO system_logs(user_id, action, details)
  VALUES (p_admin_id, 'ADMIN_ACTION', 'Request #' || p_request_id || ' set to ' || p_status);
END;
/

-- ---------- SEED DATA ----------
-- Passwords are SHA-256 hashes: admin123 and donor123
INSERT INTO users(full_name, email, password_hash, phone, city, role)
VALUES ('System Admin', 'admin@bden.pk', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', '03000000000', 'Karachi', 'ADMIN');

INSERT INTO users(full_name, email, password_hash, phone, city, role)
VALUES ('Ali Raza', 'ali@bden.pk', '0df8b21212b360c2862c2cce12a4f3d883f13acdc4b59f43cf5b2fcfd2c30954', '03001111111', 'Karachi', 'DONOR');

INSERT INTO users(full_name, email, password_hash, phone, city, role)
VALUES ('Sara Khan', 'sara@bden.pk', '0df8b21212b360c2862c2cce12a4f3d883f13acdc4b59f43cf5b2fcfd2c30954', '03002222222', 'Karachi', 'DONOR');

INSERT INTO users(full_name, email, password_hash, phone, city, role)
VALUES ('Ahmed Patient', 'patient@bden.pk', '0df8b21212b360c2862c2cce12a4f3d883f13acdc4b59f43cf5b2fcfd2c30954', '03003333333', 'Karachi', 'PATIENT');

INSERT INTO donors(user_id, blood_group, gender, date_of_birth, weight_kg, verified)
SELECT user_id, 'O+', 'MALE', DATE '1999-05-12', 72, 1 FROM users WHERE email = 'ali@bden.pk';

INSERT INTO donors(user_id, blood_group, gender, date_of_birth, weight_kg, verified)
SELECT user_id, 'B+', 'FEMALE', DATE '2000-09-21', 58, 1 FROM users WHERE email = 'sara@bden.pk';

INSERT INTO hospitals(name, city, address, phone) VALUES ('Aga Khan University Hospital', 'Karachi', 'Stadium Road', '021-111-911-911');
INSERT INTO hospitals(name, city, address, phone) VALUES ('Jinnah Postgraduate Medical Centre', 'Karachi', 'Rafiqui Shaheed Road', '021-99201300');
INSERT INTO hospitals(name, city, address, phone) VALUES ('Civil Hospital Karachi', 'Karachi', 'Baba-e-Urdu Road', '021-99215740');
INSERT INTO hospitals(name, city, address, phone) VALUES ('Indus Hospital', 'Karachi', 'Korangi Crossing', '021-111-111-880');

INSERT INTO blood_stock(hospital_id, blood_group, units_available)
SELECT h.hospital_id, g.bg, TRUNC(DBMS_RANDOM.VALUE(3, 30))
FROM hospitals h
CROSS JOIN (
  SELECT 'A+' bg FROM dual UNION ALL SELECT 'A-' FROM dual UNION ALL
  SELECT 'B+' FROM dual UNION ALL SELECT 'B-' FROM dual UNION ALL
  SELECT 'AB+' FROM dual UNION ALL SELECT 'AB-' FROM dual UNION ALL
  SELECT 'O+' FROM dual UNION ALL SELECT 'O-' FROM dual
) g;

COMMIT;

-- ---------- QUICK CHECK ----------
SELECT table_name FROM user_tables ORDER BY table_name;
SELECT object_name, object_type, status FROM user_objects
WHERE object_type IN ('VIEW','TRIGGER','PROCEDURE','FUNCTION') ORDER BY object_type, object_name;
