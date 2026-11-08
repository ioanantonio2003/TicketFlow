package com.ticketflow.ticket;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Draws a QR code as a PNG with ZXing. */
@Component
class QrCodeGenerator {

    private static final int SIZE = 300;

    byte[] png(String content) {
        try {
            // a quiet zone of 2 modules is enough for phone cameras and keeps the picture compact
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, SIZE, SIZE,
                    Map.of(EncodeHintType.MARGIN, 2));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("Could not draw the QR code", e);
        }
    }
}
