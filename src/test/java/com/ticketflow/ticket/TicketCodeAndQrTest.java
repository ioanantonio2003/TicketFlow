package com.ticketflow.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class TicketCodeAndQrTest {

    private final TicketCodeGenerator codes = new TicketCodeGenerator();
    private final QrCodeGenerator qr = new QrCodeGenerator();

    @Test
    void aCodeIs128BitsAsUrlSafeBase64WithoutPadding() {
        String code = codes.next();

        assertThat(code).hasSize(22).matches("[A-Za-z0-9_-]{22}");   // 16 bytes -> 22 characters
        assertThat(java.util.Base64.getUrlDecoder().decode(code)).hasSize(16);
    }

    @Test
    void codesDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            seen.add(codes.next());
        }

        assertThat(seen).hasSize(20_000);
    }

    @Test
    void theQrCodeIsAPngThatDecodesBackToTheCode() throws Exception {
        String code = codes.next();

        byte[] png = qr.png(code);

        assertThat(png).startsWith(0x89, 'P', 'N', 'G');
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(300);
        String decoded = new QRCodeReader()
                .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
        assertThat(decoded).isEqualTo(code);
    }
}
