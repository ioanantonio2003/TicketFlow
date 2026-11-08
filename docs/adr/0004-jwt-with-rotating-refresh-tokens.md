# ADR-0004: JWT access tokens with rotating refresh tokens

- Status: accepted
- Date: 2026-11-08

## Context

The API is used by a browser app and by scripts, has no server-side session, and has three roles. A stolen token
should be useful for as short a time as possible, and users should not have to log in every 15 minutes.

## Options considered

1. **Server-side sessions** (cookie plus session store). Simple to revoke, but needs shared session storage as soon as
   there is more than one instance and brings CSRF protection with it.
2. **One long-lived JWT.** Stateless and simple, but a leaked token works for days and cannot be revoked.
3. **Short-lived JWT access token plus a long-lived opaque refresh token** that is stored (hashed) in the database.

## Decision

Option 3.

- **Access token**: JWT signed with HS256, valid 15 minutes, claims `sub` (user id), `email` and `roles`
  (`ROLE_CUSTOMER`, ...). The API validates it without touching the database (Spring Security resource server).
  HS256 is enough because the same application issues and checks the tokens; the secret comes from an environment
  variable and must be at least 32 characters.
- **Refresh token**: 256 random bits, opaque, valid 7 days. Only its SHA-256 hash is stored, so a leaked database
  does not leak usable tokens. SHA-256 (not BCrypt) is right here because the token has full entropy and is checked
  on every refresh.
- **Rotation**: every refresh returns a new refresh token and revokes the old one.
- **Reuse detection**: presenting an already-revoked refresh token means a copy was stolen, so all refresh tokens of
  that user are revoked. The revocation is committed even though the request fails (`noRollbackFor`).
- Passwords are stored with BCrypt. Login and refresh failures give one generic answer that does not say what was
  wrong. The API is stateless, so CSRF protection is switched off; the token is sent in the `Authorization` header,
  which a foreign site cannot make a browser add.

## Consequences

- A stolen access token works for at most 15 minutes and cannot be revoked earlier; role changes also take effect
  only when the token is renewed.
- Logging out or detecting theft needs the refresh token store, so there is one small stateful table behind an
  otherwise stateless API.
- Expired refresh tokens are deleted by a scheduled job.
- Tokens are not stored in cookies, so a browser client has to keep them itself (memory or storage) and protect them
  against XSS.
