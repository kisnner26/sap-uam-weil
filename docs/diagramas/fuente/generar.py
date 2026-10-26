"""Genera los diagramas UML (estilo Enterprise Architect) del test de Weil.

Uso: python3 docs/diagramas/fuente/generar.py
Escribe un .svg y un .png por diagrama en docs/diagramas/. El png requiere rsvg-convert.
"""
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
from uml import Diagram, FILL, tw  # noqa: E402

OUT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DIAGRAMS = {}


def diagram(name):
    def wrap(fn):
        DIAGRAMS[name] = fn
        return fn
    return wrap


# ---------------------------------------------------------------- 01 componentes
@diagram("01-componentes")
def componentes():
    d = Diagram("cmp", "Componentes de la plataforma de evaluaciones", 1110, 570)
    # columna 1: frontend
    d.component(40, 60, 190, 84, "Interfaz web", ["shell de la plataforma", "acceso, inicio, entrega"])
    d.component(40, 170, 190, 84, "Visor de láminas Weil", ["tests/weil-form-a/", "viewer.js"])
    d.line([(135, 144), (135, 170)], head="open", dashed=True)
    d.text(143, 162, "«usa»", size=10, italic=True)
    # columna 2: borde del backend
    d.component(300, 60, 210, 84, "API REST", ["controladores de aspirante,", "personal y administración"])
    d.component(300, 170, 210, 84, "Seguridad", ["JWT en cookie httpOnly,", "MFA y roles"])
    d.component(300, 290, 210, 84, "Identidad", ["cuentas externas y", "acceso con Moodle"])
    d.box(300, 420, 210, 70, "Moodle UAM", "external system", ["uamvirtual.uam.edu.ni"], dashed=True)
    d.line([(230, 102), (300, 102)], head="filled")
    d.label(265, 96, "JSON")
    d.line([(405, 144), (405, 170)], head="open", dashed=True)
    d.text(413, 162, "«usa»", size=10, italic=True)
    d.line([(300, 134), (278, 134), (278, 332), (300, 332)], head="open", dashed=True)
    d.line([(405, 374), (405, 420)], head="filled")
    d.label(445, 402, "HTTPS", anchor="middle")
    # columna 3: servicios de aplicación (paquete)
    d.add('<rect x="560" y="46" width="230" height="238" fill="#FFFFFF" stroke="#000" stroke-width="1.2"/>')
    d.add('<path d="M560,46 H650 V60 H560 Z" fill="#FFFFFF" stroke="#000" stroke-width="1.2"/>')
    d.text(566, 57, "Servicios", size=10, bold=True)
    d.component(575, 70, 200, 84, "Servicio de evaluaciones", ["intentos, respuestas,", "entrega y cierre"])
    d.component(575, 170, 200, 84, "Servicio de admisión", ["convocatorias, códigos,", "invalidar y reaplicar"])
    d.line([(510, 102), (560, 102)], head="filled")
    d.component(560, 320, 230, 84, "Persistencia", ["repositorios JPA", "y migraciones Flyway"])
    d.line([(675, 284), (675, 320)], head="filled")
    d.box(560, 450, 230, 76, "PostgreSQL 16", "database", ["V1 a V4"], rx=14)
    d.line([(675, 404), (675, 450)], head="filled")
    d.label(710, 432, "JDBC")
    # columna 4: tests
    d.component(850, 60, 220, 84, "Catálogo de tests", ["TestCatalog y TestDefinition", "(definiciones publicadas)"])
    d.component(850, 170, 220, 84, "Registro de calificación", ["ScoringRegistry", "(estrategia por testId)"])
    d.component(850, 310, 220, 84, "Calificación de Weil", ["WeilScoringStrategy", "clave y baremos"])
    d.component(850, 440, 220, 84, "Servicios de apoyo", ["auditoría, privacidad,", "notificaciones"])
    d.line([(790, 102), (850, 102)], head="open", dashed=True)
    d.text(820, 96, "«usa»", size=10, italic=True, anchor="middle")
    d.line([(790, 212), (850, 212)], head="open", dashed=True)
    d.text(820, 206, "«usa»", size=10, italic=True, anchor="middle")
    # interfaz ScoringStrategy: proporcionada por Weil, requerida por el registro
    d.line([(960, 254), (960, 273)], head="open", dashed=True)
    d.add('<circle cx="960" cy="283" r="8" fill="#fff" stroke="#000" stroke-width="1.2"/>')
    d.line([(960, 291), (960, 310)])
    d.text(974, 288, "ScoringStrategy", size=11, italic=True)
    d.line([(790, 262), (815, 262), (815, 482), (850, 482)], head="open", dashed=True)
    d.text(822, 330, "«usa»", size=10, italic=True)
    return d


# ---------------------------------------------------------------- 02 despliegue
@diagram("02-despliegue")
def despliegue():
    d = Diagram("deployment", "Despliegue de referencia (docker compose)", 1180, 510)
    d.node3d(50, 100, 230, 110, "Equipo del aspirante", "device", ["portátil, tablet o móvil", "«artifact» navegador web"])
    d.node3d(50, 300, 230, 110, "Equipo del personal", "device", ["STAFF o ADMIN", "«artifact» navegador web"])
    d.node3d(390, 170, 220, 120, "Servidor web", "execution environment", ["nginx", "«artifact» frontend/ (estáticos)"])
    d.node3d(700, 170, 230, 120, "Servidor de aplicación", "execution environment", ["Spring Boot, Java 17", "«artifact» backend (jar)"])
    d.node3d(700, 370, 230, 110, "Servidor de base de datos", "execution environment", ["PostgreSQL 16", "«artifact» esquema Flyway V1-V4"])
    d.node3d(1000, 80, 150, 80, "Moodle UAM", "external system", ["uamvirtual.uam.edu.ni"])
    d.node3d(1000, 260, 150, 80, "Servidor de correo", "external system", ["SMTP (sin probar)"])
    d.line([(290, 165), (350, 165), (350, 215), (390, 215)], head="open")
    d.line([(290, 355), (350, 355), (350, 250), (390, 250)], head="open")
    d.label(340, 150, "HTTP :80", anchor="middle")
    d.line([(610, 230), (700, 230)], head="open")
    d.label(655, 224, "HTTP :8080")
    d.text(655, 246, "/api/", size=11, anchor="middle")
    d.line([(815, 290), (815, 370)], head="open")
    d.label(870, 335, "JDBC :5432")
    d.line([(930, 200), (965, 200), (965, 125), (1000, 125)], head="open")
    d.label(965, 190, "HTTPS", anchor="middle")
    d.line([(930, 250), (965, 250), (965, 300), (1000, 300)], head="open")
    d.label(965, 245, "SMTP", anchor="middle")
    d.text(60, 480, "TLS no está en el repositorio: lo pone el balanceador de la institución o nginx.", size=11, italic=True)
    return d


# ---------------------------------------------------------------- 03 secuencia
@diagram("03-secuencia-intento")
def secuencia():
    d = Diagram("sd", "Un intento de principio a fin", 1180, 960)
    xs = {"A": 100, "UI": 280, "API": 470, "SVC": 670, "CAL": 870, "DB": 1060}
    names = {"A": "Aspirante", "UI": "Interfaz web", "API": "API REST", "SVC": "Servicios",
             "CAL": "Calificación", "DB": "PostgreSQL"}
    top, end = 50, 930
    for k, x in xs.items():
        d.lifeline(x, top, end, names[k])
    y = 120
    n = 0

    def msg(a, b, text, ret=False, gap=34):
        nonlocal y, n
        n += 1
        d.message(xs[a], xs[b], y, f"{n}: {text}", ret=ret)
        y += gap

    msg("A", "UI", "ingresa su código de acceso")
    msg("UI", "API", "POST /api/applicant/claim")
    msg("API", "SVC", "claim(código)")
    msg("SVC", "DB", "busca la huella y lo asocia al usuario")
    msg("API", "UI", "disponibilidad: OPEN", ret=True)
    msg("A", "UI", "acepta el aviso y pulsa comenzar")
    msg("UI", "API", "POST /api/attempts")
    msg("API", "SVC", "abrir(testId, versión)")
    msg("SVC", "DB", "bloquea al usuario, crea intento y lease")
    msg("API", "UI", "intento (plazo, lease, versión)", ret=True)
    # fragmento loop
    ly = y - 6
    d.add(f'<rect x="60" y="{ly}" width="1060" height="196" fill="none" stroke="#000" stroke-width="1.1"/>')
    d.add(f'<path d="M60,{ly} H122 V{ly + 12} L114,{ly + 22} H60 Z" fill="#fff" stroke="#000" stroke-width="1.1"/>')
    d.text(68, ly + 15, "loop", size=11, bold=True)
    d.text(130, ly + 15, "[por cada ítem]", size=11)
    y += 38
    msg("A", "UI", "arrastra o toca la pieza N")
    msg("UI", "API", "PUT /api/attempts/{id}/responses/{ítem}")
    msg("API", "SVC", "responder(ítem, opción, versión)")
    msg("SVC", "DB", "guarda la respuesta (idempotente)")
    msg("API", "UI", "intento actualizado", ret=True)
    y += 40
    msg("A", "UI", "finaliza y entrega")
    msg("UI", "API", "POST /api/attempts/{id}/submit")
    msg("API", "SVC", "entregar(respuestas)")
    msg("SVC", "CAL", "score(respuestas)")
    msg("CAL", "SVC", "puntaje directo y escalas", ret=True)
    msg("SVC", "DB", "estado SUBMITTED, puntaje y resultado")
    msg("API", "UI", "constancia (sin puntajes)", ret=True)
    msg("UI", "A", "muestra la constancia", ret=True)
    d.h = int(y + 20)
    d.parts = [p.replace('y2="930"', f'y2="{d.h - 30}"') for p in d.parts]
    return d


# ---------------------------------------------------------------- 04 clases
@diagram("04-clases-contrato")
def clases():
    d = Diagram("class", "Contrato para agregar un test", 1240, 560)

    def cls(x, y, w, name, attrs=(), ops=(), stereo=None):
        h = 30 + (16 if stereo else 0) + 14 * len(attrs) + 8 + 14 * len(ops) + 8
        d.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{FILL}" stroke="#000" stroke-width="1.2"/>')
        cy = y + 18
        if stereo:
            d.text(x + w / 2, cy, f"«{stereo}»", anchor="middle", italic=True)
            cy += 15
        d.text(x + w / 2, cy, name, anchor="middle", bold=True, italic=stereo == "interface")
        cy += 8
        d.add(f'<line x1="{x}" y1="{cy}" x2="{x + w}" y2="{cy}" stroke="#000"/>')
        cy += 14
        for a in attrs:
            d.text(x + 8, cy, a, size=11)
            cy += 14
        cy -= 4
        d.add(f'<line x1="{x}" y1="{cy}" x2="{x + w}" y2="{cy}" stroke="#000"/>')
        cy += 14
        for o in ops:
            d.text(x + 8, cy, o, size=11)
            cy += 14
        return h

    cls(40, 60, 250, "TestDefinition", ["+ id: String", "+ version: int", "+ itemCount: int", "+ optionCount: int"],
        ["+ accepts(item, option): boolean", "+ acceptsItem(item): boolean"])
    cls(40, 225, 250, "TestCatalog", [], ["+ get(testId): TestDefinition", "+ defaultTest(): TestDefinition"])
    cls(40, 380, 250, "PublishedTests", [], ["+ weilFormA(): TestDefinition"], stereo="configuration")
    cls(400, 60, 330, "ScoringStrategy", [], [
        "+ testId(): String", "+ scoringVersion(): String", "+ score(answers): Score",
        "+ scales(score): List<Scale>", "+ interpret(score, age, group): Interpretation", "+ norms(): List<Map>"], stereo="interface")
    cls(400, 300, 330, "WeilScoringStrategy", ["+ TEST_ID = \"weil-form-a\""],
        ["+ scoringVersion(): \"weil-v1\"", "+ scales(score): total + 5 láminas"])
    cls(860, 60, 280, "ScoringRegistry", [], ["+ forTest(testId): ScoringStrategy"])
    cls(860, 170, 280, "AssessmentService", [], ["+ answer(...)", "+ submit(...)", "+ closeExpired()"])
    cls(860, 300, 280, "AdmissionService", [], ["+ claim(...)", "+ reserveFor(...)", "+ invalidate(...)"])
    cls(860, 440, 280, "AssessmentResult", ["+ scoringVersion: String", "+ scales: List<Scale>"], [])
    # TestCatalog agrega TestDefinition
    d.line([(165, 225), (165, 192)], head="open")
    d.text(173, 214, "1..*", size=10)
    # PublishedTests registra definiciones (canal izquierdo)
    d.line([(40, 450), (24, 450), (24, 95), (40, 95)], head="open", dashed=True)
    d.text(30, 335, "«registra»", size=10, italic=True)
    # Weil realiza ScoringStrategy
    d.line([(565, 300), (565, 207)], head="tri", dashed=True)
    # el registro conoce las estrategias
    d.line([(860, 95), (730, 95)], head="open")
    d.label(795, 89, "0..*")
    # servicios -> registro y catalogo
    d.line([(1000, 170), (1000, 120)], head="open", dashed=True)
    d.text(1008, 150, "«usa»", size=10, italic=True)
    d.line([(860, 250), (290, 250)], head="open", dashed=True)
    d.label(600, 244, "«usa»")
    d.line([(860, 345), (830, 345), (830, 250)], dashed=True)
    # resultado
    d.line([(1140, 215), (1166, 215), (1166, 475), (1140, 475)], head="open", dashed=True)
    d.text(1174, 340, "«crea»", size=10, italic=True)
    return d


# ---------------------------------------------------------------- 05 / 06 estados
def estado(d, x, y, w, name, do=None):
    h = 46 if not do else 64
    d.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="{FILL}" stroke="#000" stroke-width="1.2"/>')
    d.text(x + w / 2, y + 21, name, anchor="middle", bold=True)
    if do:
        d.text(x + w / 2, y + 40, do, anchor="middle", size=10)
    return h


@diagram("05-estados-convocatoria")
def estados_convocatoria():
    d = Diagram("stm", "Estados de una convocatoria (se calculan, no se guardan)", 1000, 380)
    d.initial(60, 150)
    estado(d, 140, 126, 150, "UPCOMING")
    estado(d, 400, 126, 150, "OPEN")
    estado(d, 660, 126, 150, "CLOSED")
    d.final(900, 150)
    estado(d, 400, 270, 150, "CANCELLED")
    d.final(900, 293)
    d.line([(68, 150), (140, 150)], head="filled")
    d.line([(290, 150), (400, 150)], head="filled")
    d.label(345, 142, "llega opens_at")
    d.line([(550, 150), (660, 150)], head="filled")
    d.label(605, 142, "pasa closes_at")
    d.line([(810, 150), (890, 150)], head="filled")
    d.line([(215, 172), (215, 293), (400, 293)], head="filled")
    d.label(300, 285, "cancelar [personal]")
    d.line([(475, 172), (475, 270)], head="filled")
    d.label(520, 225, "cancelar", anchor="middle")
    d.line([(550, 293), (890, 293)], head="filled")
    d.text(60, 350, "El estado se deduce de la hora actual y de cancelled_at; una habilitación pasa de ENABLED a REVOKED por separado.", size=11, italic=True)
    return d


@diagram("06-estados-intento")
def estados_intento():
    d = Diagram("stm", "Estados de un intento", 1100, 420)
    d.initial(50, 160)
    estado(d, 180, 130, 190, "IN_PROGRESS", "responder, renovar lease")
    estado(d, 580, 130, 170, "SUBMITTED")
    estado(d, 580, 290, 170, "INVALIDADO", "invalidated_at no nulo")
    d.final(990, 313)
    d.line([(58, 160), (180, 160)], head="filled")
    d.label(119, 152, "POST /attempts")
    d.line([(370, 145), (580, 145)], head="filled")
    d.label(475, 137, "entregar [60 respuestas]")
    d.line([(370, 168), (580, 168)], head="filled", dashed=True)
    d.label(480, 190, "vence el plazo [cierre automático]")
    d.line([(750, 143), (785, 143), (785, 163), (750, 163)], head="filled")
    d.text(795, 158, "marcar revisada", size=11)
    d.line([(665, 176), (665, 290)], head="filled")
    d.label(740, 238, "invalidar [motivo]")
    d.line([(750, 313), (980, 313)], head="filled")
    d.label(865, 305, "reaplicar: nueva habilitación")
    d.text(50, 390, "INVALIDADO no es un valor de status: es un intento SUBMITTED con invalidated_at (restricción ck_attempt_invalid).", size=11, italic=True)
    return d


# ---------------------------------------------------------------- 07 modelo de datos
@diagram("07-modelo-de-datos")
def modelo():
    d = Diagram("class", "Modelo de datos (PostgreSQL)", 1220, 790)
    d.table(40, 60, 250, "exam_sessions", [
        ("PK", "id", "bigint"), ("", "name", "varchar"), ("", "test_id", "varchar"), ("", "opens_at", "timestamptz"),
        ("", "closes_at", "timestamptz"), ("", "time_limit_minutes", "int"), ("", "cancelled_at", "timestamptz")])
    d.table(350, 60, 280, "exam_enrollments", [
        ("PK", "id", "bigint"), ("FK", "session_id", "bigint"), ("FK", "user_id", "bigint"), ("", "email", "varchar"),
        ("", "extra_minutes", "int"), ("", "norm_group", "varchar"), ("", "participant_age", "int"),
        ("UK", "access_code_hash", "varchar"), ("", "status", "varchar")])
    d.table(700, 60, 250, "users", [
        ("PK", "id", "bigint"), ("", "full_name", "varchar"), ("", "email", "varchar"), ("", "role", "varchar"),
        ("", "auth_provider", "varchar"), ("", "mfa_enabled", "boolean"), ("", "anonymized_at", "timestamptz")])
    d.table(1020, 60, 170, "consent_records", [
        ("FK", "user_id", "bigint"), ("", "notice_version", "int"), ("", "accepted_at", "timestamptz")])
    d.table(350, 340, 280, "assessment_attempts", [
        ("PK", "id", "bigint"), ("FK", "user_id", "bigint"), ("FK", "enrollment_id", "bigint"), ("", "test_id", "varchar"),
        ("", "test_version", "int"), ("", "status", "varchar"), ("", "deadline_at", "timestamptz"),
        ("", "direct_score", "int"), ("", "invalidated_at", "timestamptz"), ("", "version", "bigint")])
    d.table(40, 380, 250, "assessment_results", [
        ("PK", "id", "bigint"), ("FK", "attempt_id", "bigint"), ("", "scoring_version", "varchar"), ("", "scales", "text")])
    d.table(350, 640, 250, "assessment_responses", [
        ("PK", "id", "bigint"), ("FK", "attempt_id", "bigint"), ("", "item_index", "int"), ("", "selected_option", "int")])
    d.table(700, 640, 250, "assessment_events", [
        ("PK", "id", "bigint"), ("FK", "attempt_id", "bigint"), ("", "type", "varchar"), ("", "occurred_at", "timestamptz")])

    def t(x, y, s, anchor="start"):
        d.text(x, y, s, size=10, anchor=anchor)

    # exam_sessions 1 — 0..* exam_enrollments
    d.line([(290, 130), (350, 130)])
    t(294, 124, "1"); t(346, 124, "0..*", "end"); d.label(320, 150, "habilita")
    # users 1 — 0..* exam_enrollments
    d.line([(700, 130), (630, 130)])
    t(696, 124, "1", "end"); t(634, 124, "0..*"); d.label(665, 150, "canjea")
    # users 1 — 0..* consent_records
    d.line([(950, 130), (1020, 130)])
    t(954, 124, "1"); t(1016, 124, "0..*", "end")
    # exam_enrollments 1 — 0..1 assessment_attempts
    d.line([(490, 252), (490, 340)])
    t(496, 268, "1"); t(496, 334, "0..1"); d.label(555, 302, "un intento")
    # assessment_attempts 0..* — 1 users
    d.line([(630, 380), (825, 380), (825, 220)])
    t(634, 374, "0..*"); t(831, 238, "1"); d.label(730, 398, "rinde")
    # assessment_results 0..1 — 1 assessment_attempts
    d.line([(290, 420), (350, 420)])
    t(294, 414, "0..1"); t(346, 414, "1", "end"); d.label(320, 440, "escalas")
    # assessment_attempts 1 — 60 assessment_responses
    d.line([(450, 548), (450, 640)])
    t(456, 564, "1"); t(456, 634, "60"); d.label(525, 598, "respuestas")
    # assessment_attempts 1 — 0..* assessment_events
    d.line([(560, 548), (560, 596), (800, 596), (800, 640)])
    t(566, 564, "1"); t(806, 634, "0..*"); d.label(680, 590, "incidencias")
    return d


# ---------------------------------------------------------------- 08 casos de uso
@diagram("08-casos-de-uso")
def casos_de_uso():
    d = Diagram("uc", "Casos de uso del test de Weil", 1140, 760)
    d.add('<rect x="230" y="50" width="680" height="680" fill="none" stroke="#000" stroke-width="1.2"/>')
    d.text(570, 72, "SAP-UAM · Test de Inteligencia No Verbal de Weil", anchor="middle", bold=True)
    d.actor(110, 250, "Aspirante")
    d.text(110, 348, "(CLIENTE)", size=11, anchor="middle")
    d.actor(1030, 170, "Personal")
    d.text(1030, 268, "(STAFF)", size=11, anchor="middle")
    d.actor(1030, 520, "Administrador")
    d.text(1030, 618, "(ADMIN)", size=11, anchor="middle")
    left = [("Canjear código de acceso", ""), ("Aceptar aviso de privacidad", ""), ("Rendir la prueba", ""), ("Entregar y recibir constancia", "")]
    ys = [130, 220, 310, 400]
    for (t, _), y in zip(left, ys):
        d.ellipse(380, y, 125, 30, [t])
    right = ["Gestionar convocatorias", "Habilitar aspirantes", "Revisar resultados", "Exportar resultados (CSV)", "Invalidar y reaplicar"]
    ry = [120, 200, 280, 360, 440]
    for t, y in zip(right, ry):
        d.ellipse(740, y, 130, 30, [t])
    adm = ["Gestionar personal", "Consultar auditoría", "Atender solicitudes de titulares"]
    ay = [530, 600, 670]
    for t, y in zip(adm, ay):
        d.ellipse(740, y, 140, 30, [t])
    for y in ys:
        d.line([(126, 295), (255, y)])
    for y in ry:
        d.line([(1030, 215), (870, y)])
    for y in ay:
        d.line([(1030, 565), (880, y)])
    d.line([(380, 280), (380, 250)], head="open", dashed=True)
    d.text(392, 268, "«include»", size=10, italic=True)
    d.line([(1030, 514), (1030, 292)], head="tri")
    d.text(1038, 400, "hereda los", size=10)
    d.text(1038, 413, "casos de STAFF", size=10)
    return d


def main():
    for name, fn in DIAGRAMS.items():
        d = fn()
        svg_path = os.path.join(OUT, name + ".svg")
        with open(svg_path, "w", encoding="utf-8") as fh:
            fh.write(d.svg())
        if shutil.which("rsvg-convert"):
            subprocess.run(["rsvg-convert", "-z", "2", "-o", os.path.join(OUT, name + ".png"), svg_path], check=True)
        print("ok", name)


if __name__ == "__main__":
    main()
