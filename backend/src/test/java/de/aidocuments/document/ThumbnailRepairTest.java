package de.aidocuments.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class ThumbnailRepairTest {

    private static BufferedImage white(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    @Test
    void eineReinWeisseSeiteGiltAlsLeer() {
        assertThat(ThumbnailRepair.isBlank(white(560, 792))).isTrue();
    }

    @Test
    void schonEineZeileTextGiltNichtAlsLeer() {
        BufferedImage image = white(560, 792);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.drawString("Rechnung 11736", 60, 80);
        graphics.dispose();
        assertThat(ThumbnailRepair.isBlank(image)).isFalse();
    }

    @Test
    void einNichtLesbaresBildWirdNichtAngefasst() {
        assertThat(ThumbnailRepair.isBlank(null)).isFalse();
    }
}
