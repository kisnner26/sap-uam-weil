package ni.edu.uam.campusfood.user;

/**
 * Como se autentica el usuario en la app.
 * MANUAL  -> registro propio con correo (gmail) + contrasena.
 * MOODLE  -> valida con la API de Moodle UAM (CIF + contrasena institucional).
 */
public enum AuthProvider {
    MANUAL,
    MOODLE;

    public String value() {
        return name().toLowerCase();
    }
}
