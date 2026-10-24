package ni.edu.uam.campusfood.assessment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultsCsvTest {
    @Test
    void lasCeldasQueEmpiezanComoFormulaSeNeutralizan() {
        assertThat(ResultsCsv.cell("=HYPERLINK(\"http://malo\")")).startsWith("\"'=HYPERLINK");
        assertThat(ResultsCsv.cell("+cmd|' /C calc'!A0")).startsWith("\"'+cmd");
        assertThat(ResultsCsv.cell("-2+3")).startsWith("\"'-2");
        assertThat(ResultsCsv.cell("@SUMA(1)")).startsWith("\"'@SUMA");
        assertThat(ResultsCsv.cell("\t=1")).startsWith("\"'\t");
        assertThat(ResultsCsv.cell("Ana López")).isEqualTo("\"Ana López\"");
    }

    @Test
    void lasComillasYLosSaltosDeLineaNoRompenElArchivo() {
        assertThat(ResultsCsv.cell("dijo \"hola\"\nadios")).isEqualTo("\"dijo \"\"hola\"\" adios\"");
        assertThat(ResultsCsv.cell(null)).isEmpty();
    }

    @Test
    void elArchivoEmpiezaConBomYTrae18Columnas() {
        String csv = ResultsCsv.render(java.util.List.of());

        assertThat(csv).startsWith("﻿\"Id\"");
        assertThat(csv.split("\r\n")[0].split("\",\"")).hasSize(18);
    }
}
