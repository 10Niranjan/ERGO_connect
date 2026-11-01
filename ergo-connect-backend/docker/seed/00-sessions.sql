-- The seeder forges access tokens (lib.mjs) and every request is checked against a live user_sessions row
-- (SessionStillValidValidator), so give each of the 20 DevDataSeeder personas one: session_id = user_id.
-- Run ONCE on a fresh database, before any login and before seed-demo-data.mjs (session ids come from an empty identity).
INSERT INTO user_sessions (user_id, token_hash, active_role_code, expires_at)
SELECT u.user_id,
       encode(sha256(('seed-session-' || u.user_id)::bytea), 'hex'),
       (SELECT min(m.role_code) FROM user_role_mappings m WHERE m.user_id = u.user_id),
       now() + interval '30 days'
FROM users u
WHERE u.user_id <= 20
ORDER BY u.user_id
ON CONFLICT DO NOTHING;
