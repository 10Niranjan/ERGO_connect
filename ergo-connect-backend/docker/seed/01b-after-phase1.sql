-- Run AFTER phase 1 (`node docker/seed-demo-data.mjs --only=1`) and BEFORE phases 2-3.
-- Phase 1 creates users 21+ through the admin API: they get a temporary password and no seed session.
-- This gives them the shared demo password (same hash as admin@ergo.dev) and a session with session_id = user_id.
UPDATE users SET password_hash = (SELECT password_hash FROM users WHERE user_id = 1), must_change_password = false WHERE user_id > 20;
INSERT INTO user_sessions (session_id, user_id, token_hash, active_role_code, expires_at) OVERRIDING SYSTEM VALUE
SELECT u.user_id, u.user_id, encode(sha256(('seed-session-' || u.user_id)::bytea), 'hex'),
       (SELECT min(m.role_code) FROM user_role_mappings m WHERE m.user_id = u.user_id), now() + interval '30 days'
FROM users u WHERE u.user_id > 20 ON CONFLICT DO NOTHING;
SELECT setval(pg_get_serial_sequence('user_sessions', 'session_id'), (SELECT max(session_id) FROM user_sessions));
