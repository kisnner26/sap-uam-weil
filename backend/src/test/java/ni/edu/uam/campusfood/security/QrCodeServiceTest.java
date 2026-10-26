package ni.edu.uam.campusfood.security;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class QrCodeServiceTest {
    private static final int SCALE = 8;

    @Test
    void elSvgSePuedeLeerYTraeElMismoEnlaceDelAutenticador() throws Exception {
        String uri = "otpauth://totp/SAP-UAM:personal%40uam.edu.ni?secret=BHC6XDHC4GRR4NIQ7PCYRS3OFHAEJPUO&issuer=SAP-UAM";
        String svg = new QrCodeService().svg(uri);

        assertThat(svg).startsWith("<svg").doesNotContain("<script").doesNotContain("http://localhost");
        Matcher box = Pattern.compile("viewBox=\"0 0 (\\d+) (\\d+)\"").matcher(svg);
        assertThat(box.find()).isTrue();
        int size = Integer.parseInt(box.group(1));
        int px = size * SCALE;
        int[] pixels = new int[px * px];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        Matcher cell = Pattern.compile("M(\\d+) (\\d+)h1v1h-1z").matcher(svg);
        while (cell.find()) {
            int cx = Integer.parseInt(cell.group(1));
            int cy = Integer.parseInt(cell.group(2));
            for (int dy = 0; dy < SCALE; dy++) for (int dx = 0; dx < SCALE; dx++) pixels[(cy * SCALE + dy) * px + cx * SCALE + dx] = 0xFF000000;
        }
        var decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(px, px, pixels))));
        assertThat(decoded.getText()).isEqualTo(uri);
    }
}
