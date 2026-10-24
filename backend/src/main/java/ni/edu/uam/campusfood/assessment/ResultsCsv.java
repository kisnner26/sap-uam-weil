package ni.edu.uam.campusfood.assessment;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Exportacion de resultados a CSV. Dos cuidados: las celdas que empiezan con = + - @ (o tabulador o
 * retorno) se prefijan con una comilla para que Excel no las ejecute como formula (inyeccion de
 * formulas), y se antepone la marca BOM para que Excel lea bien los acentos.
 */
public final class ResultsCsv {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("America/Managua"));
    private static final String[] HEADER = {"Id", "Aspirante", "Correo", "Carrera", "Convocatoria", "Puntaje directo",
            "Percentil", "CI", "Clasificacion", "Baremo", "Edad", "Entregada (hora de Managua)", "Duracion (segundos)",
            "Incidencias", "Cierre automatico", "Estado", "Motivo de anulacion", "Revisada"};

    private ResultsCsv() {
    }

    public static String render(List<AssessmentService.StaffAttemptView> rows) {
        StringBuilder out = new StringBuilder("﻿");
        appendRow(out, HEADER);
        for (AssessmentService.StaffAttemptView r : rows) {
            appendRow(out, new String[] {
                    String.valueOf(r.id()), r.fullName(), r.email(), r.career(), r.sessionName(),
                    text(r.directScore()), text(r.percentile()), text(r.ci()), r.classification(), r.normColumn(),
                    text(r.age()), r.submittedAt() == null ? "" : STAMP.format(r.submittedAt()),
                    String.valueOf(r.durationSeconds()), String.valueOf(r.securityEvents().size()),
                    r.autoSubmitted() ? "Si" : "No", r.invalidated() ? "Anulada" : "Valida",
                    r.invalidReason(), r.reviewed() ? "Si" : "No"});
        }
        return out.toString();
    }

    static String cell(String value) {
        if (value == null) return "";
        String safe = value.replace("\r", " ").replace("\n", " ");
        if (!safe.isEmpty() && "=+-@\t".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void appendRow(StringBuilder out, String[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(cell(values[i]));
        }
        out.append("\r\n");
    }
}
