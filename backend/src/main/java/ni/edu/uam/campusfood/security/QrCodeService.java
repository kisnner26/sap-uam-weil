package ni.edu.uam.campusfood.security;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Dibuja el código QR del enrolamiento TOTP como SVG, sin servicios externos: el secreto nunca sale del servidor. */
@Service
public class QrCodeService {
    private static final int QUIET_ZONE = 4;

    public String svg(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 0,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
            int size = matrix.getWidth() + QUIET_ZONE * 2;
            StringBuilder path = new StringBuilder();
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    if (matrix.get(x, y)) path.append('M').append(x + QUIET_ZONE).append(' ').append(y + QUIET_ZONE).append("h1v1h-1z");
                }
            }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size
                    + "\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"Código QR para el autenticador\">"
                    + "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\"" + path + "\" fill=\"#000\"/></svg>";
        } catch (WriterException e) {
            throw new IllegalStateException("No se pudo generar el código QR", e);
        }
    }
}
