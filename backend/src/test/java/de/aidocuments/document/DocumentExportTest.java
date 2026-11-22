package de.aidocuments.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentExportTest {

    @Test
    void cellsCannotRunAsSpreadsheetFormulas() {
        assertThat(DocumentExport.csvCell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(DocumentExport.csvCell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(DocumentExport.csvCell("a;b")).isEqualTo("\"a;b\"");
        assertThat(DocumentExport.csvCell("Zeile 1\nZeile 2")).isEqualTo("\"Zeile 1\nZeile 2\"");
        assertThat(DocumentExport.csvCell("Stromrechnung")).isEqualTo("Stromrechnung");
    }

    @Test
    void photosKeepTheirNameAsPdf() {
        assertThat(DocumentService.asPdfName("Quittung.HEIC")).isEqualTo("Quittung.pdf");
        assertThat(DocumentService.asPdfName("IMG_0001.jpeg")).isEqualTo("IMG_0001.pdf");
        assertThat(DocumentService.asPdfName(".png")).isEqualTo("Foto.pdf");
    }
}
