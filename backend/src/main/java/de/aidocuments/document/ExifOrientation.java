package de.aidocuments.document;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Liest die EXIF-Ausrichtung (Tag 0x0112) eines JPEG-Fotos. Kameras und Smartphones speichern das Bild oft quer und
 * vermerken nur, wie es zu drehen ist. Ohne Bibliothek: nur das APP1-Segment, IFD0, ein einziges Tag.
 */
final class ExifOrientation {

    private static final int MAX_SEGMENTS = 64;

    private ExifOrientation() {
    }

    /** 1 bis 8 nach EXIF; 1 (unverändert), wenn nichts oder Ungültiges vermerkt ist. */
    static int read(Path jpeg) {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(jpeg)))) {
            if (in.readUnsignedShort() != 0xFFD8) {
                return 1;
            }
            for (int i = 0; i < MAX_SEGMENTS; i++) {
                int marker = in.readUnsignedShort();
                if ((marker & 0xFF00) != 0xFF00 || marker == 0xFFDA || marker == 0xFFD9) {
                    return 1; // Bilddaten beginnen – keine EXIF-Daten davor
                }
                int length = in.readUnsignedShort() - 2;
                if (length < 0) {
                    return 1;
                }
                if (marker == 0xFFE1 && length > 14) {
                    byte[] segment = in.readNBytes(length);
                    if (segment.length == length && startsWithExif(segment)) {
                        return fromTiff(ByteBuffer.wrap(segment, 6, segment.length - 6).slice());
                    }
                } else {
                    skip(in, length);
                }
            }
        } catch (IOException | RuntimeException e) {
            // beschädigte Metadaten: das Bild so nehmen, wie es gespeichert ist
        }
        return 1;
    }

    private static boolean startsWithExif(byte[] s) {
        return s[0] == 'E' && s[1] == 'x' && s[2] == 'i' && s[3] == 'f' && s[4] == 0 && s[5] == 0;
    }

    private static int fromTiff(ByteBuffer tiff) {
        short order = tiff.getShort(0);
        if (order == 0x4949) {
            tiff.order(ByteOrder.LITTLE_ENDIAN);
        } else if (order == 0x4D4D) {
            tiff.order(ByteOrder.BIG_ENDIAN);
        } else {
            return 1;
        }
        int ifd = tiff.getInt(4);
        if (ifd < 8 || ifd + 2 > tiff.limit()) {
            return 1;
        }
        int entries = Short.toUnsignedInt(tiff.getShort(ifd));
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > tiff.limit()) {
                return 1;
            }
            if (Short.toUnsignedInt(tiff.getShort(entry)) == 0x0112) {
                int value = Short.toUnsignedInt(tiff.getShort(entry + 8));
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static void skip(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("Ende der Datei");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }
}
