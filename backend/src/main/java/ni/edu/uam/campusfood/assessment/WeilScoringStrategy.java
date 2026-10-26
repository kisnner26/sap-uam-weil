package ni.edu.uam.campusfood.assessment;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class WeilScoringStrategy implements ScoringStrategy {
    public static final String TEST_ID = "weil-form-a";

    private static final int[] ANSWER_PATTERN = {2, 8, 7, 4, 5, 6, 8, 4, 4, 7, 3, 2};
    private static final List<Map<String, Integer>> NORMS = List.of(
            norm(90, 35, 41, 42, 46, 46, 46, 47, 47, 48, 50, 30, 47, 57),
            norm(80, 31, 36, 39, 42, 43, 43, 43, 43, 43, 46, 23, 43, 56),
            norm(75, 30, 34, 36, 40, 42, 42, 42, 42, 42, 44, 22, 42, 55),
            norm(70, 29, 33, 37, 39, 41, 41, 41, 41, 41, 42, 21, 40, 54),
            norm(60, 27, 31, 35, 37, 38, 38, 38, 39, 39, 41, 19, 37, 53),
            norm(50, 25, 28, 33, 33, 33, 34, 34, 35, 36, 36, 17, 35, 51),
            norm(40, 23, 26, 31, 32, 32, 33, 33, 34, 35, 35, 16, 33, 49),
            norm(30, 21, 23, 30, 31, 31, 32, 32, 33, 34, 34, 13, 30, 46),
            norm(25, 29, 22, 28, 29, 30, 31, 31, 32, 32, 32, 12, 28, 44),
            norm(20, 17, 20, 26, 28, 29, 30, 30, 31, 31, 31, 8, 26, 42),
            norm(10, 11, 15, 21, 25, 25, 27, 26, 27, 26, 25, 7, 21, 40),
            norm(5, 8, 12, 16, 19, 21, 22, 22, 22, 23, 23, 5, 16, 35)
    );
    private static final List<Map<String, Integer>> CI = List.of(
            Map.of("p", 99, "ci", 135), Map.of("p", 97, "ci", 129),
            Map.of("p", 95, "ci", 125), Map.of("p", 90, "ci", 119),
            Map.of("p", 80, "ci", 113), Map.of("p", 75, "ci", 110),
            Map.of("p", 70, "ci", 108), Map.of("p", 60, "ci", 104),
            Map.of("p", 50, "ci", 100), Map.of("p", 40, "ci", 96),
            Map.of("p", 30, "ci", 92), Map.of("p", 25, "ci", 90),
            Map.of("p", 20, "ci", 87), Map.of("p", 10, "ci", 81),
            Map.of("p", 5, "ci", 75), Map.of("p", 3, "ci", 72), Map.of("p", 1, "ci", 65)
    );

    @Override
    public String testId() {
        return TEST_ID;
    }

    @Override
    public String scoringVersion() {
        return "weil-v1";
    }

    @Override
    public List<Scale> scales(Score score) {
        List<Scale> scales = new java.util.ArrayList<>();
        scales.add(new Scale("total", "Puntaje directo", score.directScore()));
        for (int page = 0; page < score.pageScores().size(); page++) {
            scales.add(new Scale("lamina-" + (page + 1), "Lámina " + (page + 1), score.pageScores().get(page)));
        }
        return scales;
    }

    @Override
    public Score score(List<Integer> answers) {
        List<Integer> pages = java.util.stream.IntStream.range(0, 5)
                .map(page -> java.util.stream.IntStream.range(0, ANSWER_PATTERN.length)
                        .map(local -> answers.get(page * ANSWER_PATTERN.length + local) == ANSWER_PATTERN[local] ? 1 : 0)
                        .sum())
                .boxed().toList();
        return new Score(pages.stream().mapToInt(Integer::intValue).sum(), pages);
    }

    @Override
    public Interpretation interpret(int score, Integer age, String group) {
        String column = resolveColumn(age, group);
        int percentile = NORMS.stream().filter(row -> score >= row.get(column))
                .map(row -> row.get("p")).findFirst().orElse(1);
        int ci = CI.stream().filter(row -> percentile >= row.get("p"))
                .map(row -> row.get("ci")).findFirst().orElse(65);
        return new Interpretation(column, percentile, ci, classify(ci));
    }

    @Override
    public List<Map<String, Integer>> norms() {
        return NORMS;
    }

    private static String resolveColumn(Integer age, String group) {
        if ("7-16".equals(group) && age != null && age >= 7 && age <= 16) return String.valueOf(age);
        if ("AN".equals(group) || "AD".equals(group)) return group;
        return "UN";
    }

    private static String classify(int ci) {
        if (ci >= 140) return "Inteligencia muy superior";
        if (ci >= 124) return "Inteligencia superior";
        if (ci >= 116) return "Buena inteligencia";
        if (ci >= 85) return "Inteligencia media";
        if (ci >= 70) return "Rango bajo";
        return "Rango muy bajo";
    }

    private static Map<String, Integer> norm(int p, int a7, int a8, int a9, int a10, int a11,
                                              int a12, int a13, int a14, int a15, int a16,
                                              int an, int ad, int un) {
        return Map.ofEntries(Map.entry("p", p), Map.entry("7", a7), Map.entry("8", a8),
                Map.entry("9", a9), Map.entry("10", a10), Map.entry("11", a11),
                Map.entry("12", a12), Map.entry("13", a13), Map.entry("14", a14),
                Map.entry("15", a15), Map.entry("16", a16), Map.entry("AN", an),
                Map.entry("AD", ad), Map.entry("UN", un));
    }
}
