package ni.edu.uam.campusfood.moodle;

/**
 * Perfil minimo del estudiante extraido de core_webservice_get_site_info.
 * Solo se usan los campos de identidad; nada academico.
 */
public record MoodleProfile(
        int userId,
        String fullName,
        String email,
        String pictureUrl,
        String career
) {
}
