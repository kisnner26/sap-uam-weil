package ni.edu.uam.campusfood.security;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;

/** Implementacion RFC 6238: SHA-1, seis digitos y periodos de 30 segundos. */
@Service
public class TotpService {
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String newSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    public boolean verify(String secret, String code, Instant now) {
        return matchingStep(secret, code, now).isPresent();
    }

    /** Paso TOTP al que corresponde el código (ventana de ±1 paso), o vacío si no coincide. */
    public java.util.OptionalLong matchingStep(String secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{6}")) return java.util.OptionalLong.empty();
        long step = now.getEpochSecond() / 30;
        byte[] given = code.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.OptionalLong found = java.util.OptionalLong.empty();
        // Se recorren siempre los tres pasos y se compara en tiempo constante.
        for (long offset = -1; offset <= 1; offset++) {
            byte[] expected = generate(secret, step + offset).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (java.security.MessageDigest.isEqual(expected, given)) found = java.util.OptionalLong.of(step + offset);
        }
        return found;
    }

    String generate(String secret, long step) {
        try {
            byte[] key = decodeBase32(secret);
            byte[] counter = ByteBuffer.allocate(8).putLong(step).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] digest = mac.doFinal(counter);
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo calcular el codigo TOTP", e);
        }
    }

    private String encodeBase32(byte[] input) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32[(buffer >> (bitsLeft - 5)) & 31]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) out.append(BASE32[(buffer << (5 - bitsLeft)) & 31]);
        return out.toString();
    }

    private byte[] decodeBase32(String input) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char raw : input.toUpperCase().toCharArray()) {
            int value = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(raw);
            if (value < 0) continue;
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
