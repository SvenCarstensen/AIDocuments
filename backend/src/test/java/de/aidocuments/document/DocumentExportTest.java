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
        assertThat(DocumentService.asPdfName("RE-2026-004711.XML")).isEqualTo("RE-2026-004711.pdf");
        assertThat(DocumentService.xmlName("Rechnung")).isEqualTo("Rechnung.xml");
    }

    @Test
    void eInvoiceDetailsHaveTheirOwnColumns() {
        String csv = DocumentExport.csv(java.util.List.of(java.util.Map.of("title", "Strom November",
                "invoiceNumber", "RE-2026-004711", "invoiceGross", "194.78", "invoiceAmountDue", "144.78",
                "invoiceCurrency", "EUR")));
        String[] lines = csv.split("\r\n");
        java.util.List<String> header = java.util.List.of(lines[0].replace("﻿", "").split(";"));
        java.util.List<String> row = java.util.List.of(lines[1].split(";", -1));
        assertThat(row.get(header.indexOf("invoiceNumber"))).isEqualTo("RE-2026-004711");
        assertThat(row.get(header.indexOf("invoiceGross"))).isEqualTo("194.78");
        assertThat(row.get(header.indexOf("invoiceAmountDue"))).isEqualTo("144.78");
        assertThat(row.get(header.indexOf("invoiceCurrency"))).isEqualTo("EUR");
    }
}
