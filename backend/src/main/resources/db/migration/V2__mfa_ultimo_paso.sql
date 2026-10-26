-- Evita reutilizar un codigo TOTP ya aceptado.
ALTER TABLE users ADD COLUMN mfa_last_step BIGINT;
