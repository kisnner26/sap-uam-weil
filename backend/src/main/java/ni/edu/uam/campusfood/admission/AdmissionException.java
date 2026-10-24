package ni.edu.uam.campusfood.admission;

/** Una regla del flujo de admision impide la operacion. El codigo permite a la interfaz reaccionar. */
public class AdmissionException extends RuntimeException {
    private final String code;

    public AdmissionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
