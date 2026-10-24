"""Prueba de punta a punta de identidad, roles y segundo factor contra un backend en marcha.

Uso (el backend debe arrancar con base limpia y un administrador inicial):
  APP_BOOTSTRAP_ADMIN_EMAIL=admin@uam.test APP_BOOTSTRAP_ADMIN_PASSWORD='<12+ caracteres>' mvn spring-boot:run
  E2E_BASE=http://localhost:8081 E2E_ADMIN_EMAIL=admin@uam.test E2E_ADMIN_PASSWORD='<la misma>' \
  E2E_STAFF_PASSWORD='<12+ caracteres>' python3 tools/e2e_identity.py

Genera los codigos TOTP igual que una aplicacion autenticadora. Solo para entornos de desarrollo.
"""
import base64, hashlib, hmac, http.cookiejar, json, os, struct, sys, time, urllib.error, urllib.request

BASE = os.environ.get('E2E_BASE', 'http://localhost:8081')
try:
    ADMIN_EMAIL, ADMIN_PASS, STAFF_PASS = (os.environ[k] for k in ('E2E_ADMIN_EMAIL', 'E2E_ADMIN_PASSWORD', 'E2E_STAFF_PASSWORD'))
except KeyError as missing:
    sys.exit(f'Falta la variable de entorno {missing}. Mira el encabezado del archivo.')
STAFF_EMAIL = f'staff{int(time.time())}@uam.test'
results = []


def totp(secret, shift=0):
    key = base64.b32decode(secret + '=' * (-len(secret) % 8))
    counter = int(time.time()) // 30 + shift
    digest = hmac.new(key, struct.pack('>Q', counter), hashlib.sha1).digest()
    offset = digest[-1] & 15
    return f'{(struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7fffffff) % 1000000:06d}'


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(BASE + path, data=data, method=method, headers={'Content-Type': 'application/json'})
        try:
            with self.opener.open(req) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, json.loads(raw) if raw else None
            except ValueError:
                return e.code, None

    def cookie(self):
        return {c.name: c.value for c in self.jar}.get('sap_uam_session')


def check(name, ok, detail=''):
    results.append(ok)
    print(('PASS ' if ok else 'FAIL ') + name + (f'  [{detail}]' if detail and not ok else ''))


def login_with_mfa(client, email, password):
    status, body = client.call('POST', '/api/auth/login', {'email': email, 'password': password})
    if status != 202:
        return status, body, None
    secret = body.get('enrollmentSecret')
    if not secret:  # ya tiene segundo factor: se usa el secreto guardado
        secret = SECRETS[email]
    SECRETS[email] = secret
    for shift in (0, 1, -1):
        s, b = client.call('POST', '/api/auth/mfa/verify', {'challengeToken': body['challengeToken'], 'code': totp(secret, shift)})
        if s == 200:
            return s, b, body
    return s, b, body


SECRETS = {}

# ---- 1. administrador inicial
admin = Client()
s, b = admin.call('POST', '/api/auth/login', {'email': ADMIN_EMAIL, 'password': 'incorrecta-xxxx'})
check('contrasena incorrecta se rechaza', s in (401, 429), s)
s, b, first = login_with_mfa(admin, ADMIN_EMAIL, ADMIN_PASS)
check('el administrador pasa por segundo factor y entra', s == 200 and first and first.get('mfaEnrollmentRequired') is True, (s, first and first.get('mfaRequired')))
s, me = admin.call('GET', '/api/auth/me')
check('/me devuelve rol ADMIN', s == 200 and me.get('role') == 'ADMIN', (s, me))

# ---- 2. administracion de personal
s, users = admin.call('GET', '/api/admin/users')
check('ADMIN lista usuarios', s == 200 and any(u['email'] == ADMIN_EMAIL for u in users), s)
s, created = admin.call('POST', '/api/admin/users', {'fullName': 'Sofia Evaluadora', 'email': STAFF_EMAIL, 'temporaryPassword': STAFF_PASS, 'role': 'STAFF'})
check('ADMIN crea una cuenta STAFF', s in (200, 201) and created.get('role') == 'STAFF', (s, created))
staff_id = created.get('id') if created else None
s, dup = admin.call('POST', '/api/admin/users', {'fullName': 'Sofia Evaluadora', 'email': STAFF_EMAIL, 'temporaryPassword': STAFF_PASS, 'role': 'STAFF'})
check('un correo repetido se rechaza', s in (400, 409), s)
s, short = admin.call('POST', '/api/admin/users', {'fullName': 'Persona Corta', 'email': f'x{int(time.time())}@uam.test', 'temporaryPassword': 'corta', 'role': 'STAFF'})
check('una contrasena temporal corta se rechaza', s == 400, s)
s, audit = admin.call('GET', '/api/admin/audit')
check('la auditoria registra el alta', s == 200 and len(audit) > 0, (s, audit if s != 200 else len(audit)))

# ---- 3. personal con segundo factor
staff = Client()
s, b, first = login_with_mfa(staff, STAFF_EMAIL, STAFF_PASS)
check('STAFF entra con segundo factor', s == 200, (s, b))
s, rows = staff.call('GET', '/api/staff/attempts')
check('STAFF ve los resultados', s == 200, s)
s, _ = staff.call('GET', '/api/admin/users')
check('STAFF NO accede a administracion (403)', s == 403, s)
s, _ = staff.call('GET', '/api/admin/audit')
check('STAFF NO accede a la auditoria (403)', s == 403, s)
staff_cookie = staff.cookie()

# ---- 4. revocacion inmediata
s, _ = admin.call('PATCH', f'/api/admin/users/{staff_id}', {'role': 'STAFF', 'active': False})
check('ADMIN desactiva la cuenta', s == 200, s)
s, _ = staff.call('GET', '/api/staff/attempts')
check('la sesion abierta del personal deja de servir al instante (401)', s == 401, s)
s, b = Client().call('POST', '/api/auth/login', {'email': STAFF_EMAIL, 'password': STAFF_PASS})
check('una cuenta desactivada no puede iniciar sesion', s in (401, 403), s)

# ---- 5. cambio de rol invalida sesiones previas
s, _ = admin.call('PATCH', f'/api/admin/users/{staff_id}', {'role': 'STAFF', 'active': True})
staff2 = Client()
s, b, _ = login_with_mfa(staff2, STAFF_EMAIL, STAFF_PASS)
check('al reactivarla entra otra vez (segundo factor ya enrolado)', s == 200, (s, b))
s, rows = staff2.call('GET', '/api/staff/attempts')
check('...y ve resultados', s == 200, s)
s, _ = admin.call('PATCH', f'/api/admin/users/{staff_id}', {'role': 'ADMIN', 'active': True})
check('ADMIN cambia el rol de una cuenta', s == 200, s)
s, _ = staff2.call('GET', '/api/staff/attempts')
check('al cambiarle el rol, su sesion anterior queda invalida (401)', s == 401, s)
s, _ = admin.call('PATCH', f'/api/admin/users/{staff_id}', {'role': 'CLIENTE', 'active': True})
check('no se puede degradar una cuenta de personal a aspirante (400)', s == 400, s)
s, me = admin.call('GET', '/api/auth/me')
s, _ = admin.call('PATCH', f"/api/admin/users/{me['id']}", {'role': 'STAFF', 'active': True})
check('un administrador no puede quitarse su propio acceso (400)', s == 400, s)
s, _ = admin.call('PATCH', f"/api/admin/users/{me['id']}", {'role': 'ADMIN', 'active': False})
check('ni desactivarse a si mismo (400)', s == 400, s)

# ---- 6. reinicio del segundo factor
admin.call('PATCH', f'/api/admin/users/{staff_id}', {'role': 'STAFF', 'active': True})
s, _ = admin.call('POST', f'/api/admin/users/{staff_id}/mfa-reset')
check('ADMIN reinicia el segundo factor', s in (200, 204), s)
staff3 = Client()
s, body = staff3.call('POST', '/api/auth/login', {'email': STAFF_EMAIL, 'password': STAFF_PASS})
check('tras el reinicio debe enrolarse de nuevo', s == 202 and body.get('mfaEnrollmentRequired') is True, (s, body and body.get('mfaEnrollmentRequired')))

# ---- 7. un aspirante no accede a nada del personal
asp = Client()
s, _ = asp.call('POST', '/api/auth/register', {'fullName': 'Aspirante De Prueba', 'email': f'asp{int(time.time())}@example.com', 'password': 'clave-prueba-123', 'cedula': '001'})
check('un aspirante se registra sin segundo factor', s == 201, s)
s, _ = asp.call('GET', '/api/staff/attempts')
check('el aspirante NO ve resultados (403)', s == 403, s)
s, _ = asp.call('GET', '/api/admin/users')
check('el aspirante NO accede a administracion (403)', s == 403, s)

# ---- 8. cierre de sesion revoca en el servidor
copied = admin.cookie()
admin.call('POST', '/api/auth/logout')
other = Client()
other.jar.set_cookie(http.cookiejar.Cookie(0, 'sap_uam_session', copied, None, False, 'localhost', True, False, '/', True, False, None, False, None, None, {}))
s, _ = other.call('GET', '/api/auth/me')
check('tras cerrar sesion, una copia de la cookie ya no sirve (401)', s == 401, s)

print(f'\n{sum(results)}/{len(results)} comprobaciones correctas')
sys.exit(0 if all(results) else 1)
