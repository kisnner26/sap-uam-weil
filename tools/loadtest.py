#!/usr/bin/env python3
"""Prueba de carga del flujo completo de admision contra un backend en marcha.

Simula N aspirantes (C a la vez): registro, canje del codigo, consentimiento, apertura del intento,
60 respuestas, envio. El personal crea la convocatoria y habilita a todos antes de empezar.
El backend debe correr con correo en modo log (los codigos se leen de /api/admin/notifications) y con
el limite por IP de /api/auth subido, p. ej. --app.security.auth-rate-limit.max-requests=1000000 --app.mail.poll-ms=1000.

Uso: tools/loadtest.py --base http://localhost:8081 --admin-email A --admin-password P [--users 200 --concurrency 50]
"""
import argparse, base64, hashlib, hmac, http.cookiejar, json, random, re, statistics, struct, threading, time
import urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor


def totp(secret, shift=0):
    key = base64.b32decode(secret + '=' * (-len(secret) % 8))
    digest = hmac.new(key, struct.pack('>Q', int(time.time()) // 30 + shift), hashlib.sha1).digest()
    o = digest[-1] & 15
    return f'{(struct.unpack(">I", digest[o:o + 4])[0] & 0x7fffffff) % 1000000:06d}'


class Client:
    def __init__(self, base, stats=None):
        self.base, self.stats = base, stats
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, method, path, body=None, label=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method, headers={'Content-Type': 'application/json'})
        t0 = time.perf_counter()
        try:
            with self.opener.open(req, timeout=60) as r:
                raw, status = r.read(), r.status
        except urllib.error.HTTPError as e:
            raw, status = e.read(), e.code
        except Exception as e:  # tiempo agotado, conexion rechazada
            raw, status = str(e).encode(), 0
        ms = (time.perf_counter() - t0) * 1000
        if self.stats is not None:
            self.stats.add(label or method + ' ' + re.sub(r'/\d+', '/{n}', path), ms, status)
        try:
            return status, json.loads(raw) if raw else None
        except ValueError:
            return status, raw.decode('utf-8', 'replace')


class Stats:
    def __init__(self):
        self.lock, self.data, self.errors = threading.Lock(), {}, {}

    def add(self, label, ms, status):
        with self.lock:
            self.data.setdefault(label, []).append(ms)
            if not 200 <= status < 300:
                self.errors.setdefault(label, {}).setdefault(status, 0)
                self.errors[label][status] += 1

    def report(self, wall):
        def q(v, p): return v[min(len(v) - 1, int(len(v) * p))]
        print(f'\n{"endpoint":44} {"n":>6} {"p50":>7} {"p95":>7} {"p99":>7} {"max":>7}  (ms)')
        total = 0
        for label, v in sorted(self.data.items(), key=lambda kv: -len(kv[1])):
            v = sorted(v); total += len(v)
            print(f'{label:44} {len(v):6} {q(v,.5):7.0f} {q(v,.95):7.0f} {q(v,.99):7.0f} {v[-1]:7.0f}')
        err = sum(sum(e.values()) for e in self.errors.values())
        print(f'\n{total} peticiones en {wall:.1f}s = {total / wall:.0f} req/s; respuestas no 2xx: {err}')
        for label, e in self.errors.items():
            print(f'  {label}: {e}')
        return err


def applicant(args, stats, n, code, run):
    c = Client(args.base, stats)
    email = f'carga{run}-{n}@example.com'
    ok = c.call('POST', '/api/auth/register', {'fullName': f'Aspirante Carga {n}', 'email': email, 'password': f'clave-carga-{run}-{n}', 'cedula': f'{n:06d}'})[0] == 201
    ok = ok and c.call('POST', '/api/applicant/claim', {'code': code})[0] in (200, 204)
    st, av = c.call('GET', '/api/applicant/availability')
    if st != 200:
        return False
    if av.get('needsConsent'):
        ok = ok and c.call('POST', '/api/privacy/consent', {'version': av['consentVersion']})[0] in (200, 204)
    st, att = c.call('POST', '/api/attempts', {'testId': 'weil-form-a', 'testVersion': args.test_version})
    if st != 200:
        return False
    lease, version = att['leaseToken'], att['version']
    answers = [random.randint(1, 8) for _ in range(60)]
    for i, option in enumerate(answers):
        st, att = c.call('PUT', f'/api/attempts/{att["id"]}/responses/{i}', {'option': option, 'leaseToken': lease, 'expectedVersion': version})
        if st != 200:
            return False
        version = att['version']
        time.sleep(random.uniform(0, args.think))
    st, _ = c.call('POST', f'/api/attempts/{att["id"]}/submit', {'leaseToken': lease, 'expectedVersion': version, 'answers': answers})
    return ok and st == 200


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default='http://localhost:8081')
    ap.add_argument('--admin-email', required=True)
    ap.add_argument('--admin-password', required=True)
    ap.add_argument('--admin-new-password',
                    help='contraseña temporal nueva cuando el administrador de arranque exige cambiarla')
    ap.add_argument('--users', type=int, default=100)
    ap.add_argument('--concurrency', type=int, default=25)
    ap.add_argument('--think', type=float, default=0.05, help='pausa maxima entre respuestas (s)')
    ap.add_argument('--test-version', type=int, default=1)
    args = ap.parse_args()
    run = int(time.time())
    stats = Stats()

    admin = Client(args.base, Stats())  # la preparacion no cuenta en las metricas
    st, b = admin.call('POST', '/api/auth/login', {'email': args.admin_email, 'password': args.admin_password})
    assert st == 202, f'login admin: {st} {b}'
    secret = b.get('enrollmentSecret') or open('.loadtest-secret').read().strip()
    open('.loadtest-secret', 'w').write(secret)
    for shift in (0, 1, -1):
        st, verified = admin.call('POST', '/api/auth/mfa/verify',
                                  {'challengeToken': b['challengeToken'], 'code': totp(secret, shift)})
        if st == 200:
            break
    assert st == 200, 'segundo factor'
    if verified.get('user', {}).get('mustChangePassword'):
        new_password = args.admin_new_password or args.admin_password + '-loadtest'
        st, changed = admin.call('POST', '/api/auth/password', {
            'currentPassword': args.admin_password,
            'newPassword': new_password,
        })
        assert st == 200, f'cambio de contraseña temporal: {st} {changed}'
    now = time.time()
    iso = lambda t: time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(t))
    st, sess = admin.call('POST', '/api/staff/sessions', {'name': f'Carga {run}', 'opensAt': iso(now - 60), 'closesAt': iso(now + 7200), 'timeLimitMinutes': 60})
    assert st in (200, 201), f'sesion: {st} {sess}'
    codes = {}
    for lo in range(0, args.users, 100):  # el listado de correos solo devuelve los ultimos 100
        batch = [{'email': f'carga{run}-{n}@example.com', 'fullName': f'Aspirante Carga {n}'} for n in range(lo, min(lo + 100, args.users))]
        st, r = admin.call('POST', f'/api/staff/sessions/{sess["id"]}/enrollments', {'applicants': batch})
        assert st in (200, 201), f'habilitar: {st} {r}'
        for _ in range(120):  # el envio desde el outbox es asincrono
            time.sleep(1)
            st, notes = admin.call('GET', '/api/admin/notifications')
            assert st == 200, f'notificaciones: {st} {notes}'
            for m in notes:
                mm = re.search(r'acceso.*?:\s*([A-Z0-9]{4}(?:-[A-Z0-9]{4})+)', m.get('body') or '')
                if m['type'].upper().startswith('INVIT') and mm and f'carga{run}-' in m['toEmail']:
                    codes[m['toEmail']] = mm.group(1)
            if len(codes) >= lo + len(batch):
                break
    assert len(codes) >= args.users, f'solo {len(codes)} codigos de {args.users} (revisa app.mail.mode=log y el limite de /api/admin/notifications)'
    print(f'preparado: convocatoria {sess["id"]}, {len(codes)} codigos; lanzando {args.users} aspirantes ({args.concurrency} a la vez)')

    t0 = time.perf_counter()
    with ThreadPoolExecutor(args.concurrency) as pool:
        results = list(pool.map(lambda n: applicant(args, stats, n, codes[f'carga{run}-{n}@example.com'], run), range(args.users)))
    wall = time.perf_counter() - t0
    err = stats.report(wall)
    done = sum(results)
    print(f'aspirantes que completaron el flujo: {done}/{args.users}')
    raise SystemExit(0 if done == args.users and err == 0 else 1)


if __name__ == '__main__':
    main()
