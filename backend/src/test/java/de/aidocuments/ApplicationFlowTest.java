package de.aidocuments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.aidocuments.document.Document;
import de.aidocuments.document.DocumentRepository;
import de.aidocuments.document.DocumentSource;
import de.aidocuments.document.DocumentStatus;
import de.aidocuments.importer.FolderImportService;
import de.aidocuments.mail.MailAccountRepository;
import de.aidocuments.mail.MailSyncService;
import jakarta.activation.DataHandler;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Durchläuft die Anwendung von der Ersteinrichtung bis zur Suche gegen eine echte MongoDB (Testcontainers)
 * und einen echten IMAP-Server (GreenMail). Ohne KI-Schlüssel – geprüft wird alles außer dem Aufruf der KI.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApplicationFlowTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @RegisterExtension
    static final GreenMailExtension greenMail =
            new GreenMailExtension(ServerSetupTest.IMAP).withPerMethodLifecycle(false);

    static final Path dataDir = createTempDir("aidocs-data");
    static final Path inbox = createTempDir("aidocs-inbox");
    static final MockHttpSession adminSession = new MockHttpSession();
    static final MockHttpSession userSession = new MockHttpSession();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", dataDir::toString);
        registry.add("app.ai.api-key", () -> "");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    DocumentRepository documents;
    @Autowired
    MailAccountRepository mailAccounts;
    @Autowired
    MailSyncService mailSync;
    @Autowired
    FolderImportService folderImport;

    @Test
    @Order(1)
    void firstStartRequiresSetupAndProtectsTheApi() throws Exception {
        mvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupRequired").value(true))
                .andExpect(jsonPath("$.user").doesNotExist());
        mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/setup").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Chef\",\"password\":\"kurz\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/setup").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Chef\",\"password\":\"test-Passwort-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("chef"))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        // die Ersteinrichtung ist einmalig und meldet direkt an
        mvc.perform(post("/api/auth/setup").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zweiter\",\"password\":\"test-Passwort-2\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/auth/status").session(adminSession))
                .andExpect(jsonPath("$.setupRequired").value(false))
                .andExpect(jsonPath("$.user.username").value("chef"));
    }

    @Test
    @Order(2)
    void rolesSeparateAdministrationFromNormalUse() throws Exception {
        mvc.perform(post("/api/users").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"erika\",\"password\":\"test-Passwort-3\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/auth/login").with(csrf()).session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"erika\",\"password\":\"falsch\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Erika\",\"password\":\"test-Passwort-3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));

        mvc.perform(get("/api/documents").session(userSession)).andExpect(status().isOk());
        mvc.perform(get("/api/users").session(userSession)).andExpect(status().isForbidden());
        mvc.perform(get("/api/mail-accounts").session(userSession)).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/ai").session(userSession)).andExpect(status().isForbidden());

        // ohne CSRF-Token wird nichts verändert
        mvc.perform(post("/api/users").session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x-user\",\"password\":\"test-Passwort-4\",\"role\":\"USER\"}"))
                .andExpect(status().isForbidden());

        // der letzte Administrator kann sich nicht selbst herabstufen
        String adminId = jsonValue(mvc.perform(get("/api/auth/status").session(adminSession)).andReturn(),
                "$.user.id");
        mvc.perform(put("/api/users/" + adminId).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\",\"enabled\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(3)
    void uploadedPdfsBecomeSearchableByWordsStemsAndPartialText() throws Exception {
        MockMultipartFile invoice = new MockMultipartFile("files", "Stromrechnung_2025.pdf", "application/pdf",
                pdf("Stadtwerke Musterstadt", "Jahresabrechnung Strom 2025",
                        "Ihre Haftpflichtversicherung ist davon nicht betroffen.",
                        "Rechnungsbetrag 482,17 EUR, zahlbar bis 14.03.2025"));
        MockMultipartFile contract = new MockMultipartFile("files", "Mietvertrag.pdf", "application/pdf",
                pdf("Mietvertrag ueber die Wohnung Gartenweg 7", "Kaltmiete 950 EUR, Kaution drei Monatsmieten"));
        MockMultipartFile noPdf = new MockMultipartFile("files", "notiz.pdf", "application/pdf",
                "nur Text, kein PDF".getBytes());

        mvc.perform(multipart("/api/documents").file(invoice).file(contract).file(noPdf)
                        .with(csrf()).session(userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$[1].outcome").value("CREATED"))
                .andExpect(jsonPath("$[2].outcome").value("REJECTED"));
        // dieselbe Datei ein zweites Mal: wird als Duplikat erkannt
        mvc.perform(multipart("/api/documents").file(contract).with(csrf()).session(userSession))
                .andExpect(jsonPath("$[0].outcome").value("DUPLICATE"));

        awaitProcessed(2);
        assertThat(documents.findAll()).allSatisfy(doc -> {
            assertThat(doc.getStatus()).isEqualTo(DocumentStatus.TEXT_ONLY);
            assertThat(doc.getPageCount()).isEqualTo(1);
            assertThat(doc.isHasThumbnail()).isTrue();
        });

        // ganzes Wort – mit Trefferausschnitt
        mvc.perform(get("/api/documents").param("q", "Jahresabrechnung").session(userSession))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].originalFilename").value("Stromrechnung_2025.pdf"))
                .andExpect(jsonPath("$.items[0].snippet", containsString("Jahresabrechnung")));
        // Stammform: "Monatsmiete" findet "Monatsmieten"
        mvc.perform(get("/api/documents").param("q", "Monatsmiete").session(userSession))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].originalFilename").value("Mietvertrag.pdf"));
        // Teiltext mitten im Wort: "versicherung" findet "Haftpflichtversicherung"
        mvc.perform(get("/api/documents").param("q", "versicherung").session(userSession))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].originalFilename").value("Stromrechnung_2025.pdf"));
        // Wortanfang beim Tippen
        mvc.perform(get("/api/documents").param("q", "Gartenw").session(userSession))
                .andExpect(jsonPath("$.total").value(1));
        // mehrere Wörter müssen alle vorkommen
        mvc.perform(get("/api/documents").param("q", "Strom Kaution").session(userSession))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/documents").session(userSession))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @Order(4)
    void tagsCanBeEditedAndUsedAsFilterAndSearchTerm() throws Exception {
        Document invoice = documents.findAll().stream()
                .filter(doc -> doc.getOriginalFilename().startsWith("Stromrechnung")).findFirst().orElseThrow();

        mvc.perform(put("/api/documents/" + invoice.getId()).with(csrf()).session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Stromrechnung 2025","category":"Rechnung",
                                 "correspondent":"Stadtwerke Musterstadt","documentDate":"2025-02-28",
                                 "tags":["Energie","nebenkosten"," Energie "]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags", hasSize(2)))
                .andExpect(jsonPath("$.tags[0]").value("energie"));

        mvc.perform(get("/api/documents").param("tag", "energie").session(userSession))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/documents").param("tag", "energie").param("tag", "nebenkosten").session(userSession))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/documents").param("tag", "energie").param("tag", "gibtsnicht").session(userSession))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/documents").param("q", "nebenkosten").session(userSession))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/documents").param("category", "Rechnung")
                        .param("from", "2025-01-01").param("to", "2025-12-31").session(userSession))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/documents/facets").session(userSession))
                .andExpect(jsonPath("$.tags", hasSize(2)))
                .andExpect(jsonPath("$.categories[0].name").value("Rechnung"))
                .andExpect(jsonPath("$.correspondents[0].count").value(1));

        mvc.perform(get("/api/documents/" + invoice.getId() + "/file").session(userSession))
                .andExpect(status().isOk());
        mvc.perform(get("/api/documents/" + invoice.getId() + "/thumbnail").session(userSession))
                .andExpect(status().isOk());
        mvc.perform(get("/api/documents/" + invoice.getId() + "/text").session(userSession))
                .andExpect(jsonPath("$.content", containsString("Rechnungsbetrag 482,17 EUR")));
    }

    @Test
    @Order(5)
    void pdfAttachmentsAreFetchedFromImapOnceAndTheMailboxStaysUntouched() throws Exception {
        GreenMailUser mailbox = greenMail.setUser("scans@example.test", "scans@example.test", "imap-test-secret");
        mailbox.deliver(mailWithPdf("Scan vom Kopierer", "Scan_0001.pdf",
                pdf("Zahnarztpraxis Dr. Beispiel", "Heil- und Kostenplan fuer die Behandlung")));
        mailbox.deliver(mailWithoutAttachment("Nur eine Nachricht"));

        String body = """
                {"name":"Scanner","host":"127.0.0.1","port":%d,"security":"NONE",
                 "username":"scans@example.test","password":"imap-test-secret","folder":"INBOX","enabled":false}"""
                .formatted(greenMail.getImap().getPort());

        mvc.perform(post("/api/mail-accounts/test").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"account\":" + body + "}"))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message", containsString("2 E-Mails")));
        mvc.perform(post("/api/mail-accounts/test").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":" + body.replace("imap-test-secret", "falsches-passwort") + "}"))
                .andExpect(jsonPath("$.ok").value(false));

        MvcResult created = mvc.perform(post("/api/mail-accounts").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();
        String accountId = jsonValue(created, "$.id");
        // das Passwort liegt nur verschlüsselt in der Datenbank
        assertThat(mailAccounts.findById(accountId).orElseThrow().getPasswordEncrypted())
                .doesNotContain("imap-test-secret");

        mailSync.sync(accountId);
        awaitProcessed(3);
        Document scan = documents.findAll().stream()
                .filter(doc -> doc.getSource() == DocumentSource.MAIL).findFirst().orElseThrow();
        assertThat(scan.getOriginalFilename()).isEqualTo("Scan_0001.pdf");
        assertThat(scan.getMailSubject()).isEqualTo("Scan vom Kopierer");
        assertThat(scan.getMailFrom()).contains("kopierer@example.test");
        mvc.perform(get("/api/mail-accounts").session(adminSession))
                .andExpect(jsonPath("$[0].lastSyncStatus").value("OK"))
                .andExpect(jsonPath("$[0].lastSyncMessage", containsString("1 PDF übernommen")))
                .andExpect(jsonPath("$[0].documentCount").value(1));
        mvc.perform(get("/api/documents").param("q", "Kostenplan").session(userSession))
                .andExpect(jsonPath("$.total").value(1));

        // zweiter Abruf: nichts Neues; danach kommt eine weitere E-Mail
        mailSync.sync(accountId);
        assertThat(documents.count()).isEqualTo(3);
        mailbox.deliver(mailWithPdf("Noch ein Scan", "Scan_0002.pdf", pdf("Kfz-Steuerbescheid 2025")));
        mailSync.sync(accountId);
        awaitProcessed(4);

        // nur gelesen: keine E-Mail wurde als gelesen markiert oder entfernt
        Message[] remaining = greenMail.getReceivedMessages();
        assertThat(remaining).hasSize(3);
        for (Message message : remaining) {
            assertThat(message.isSet(Flags.Flag.SEEN)).isFalse();
        }
    }

    @Test
    @Order(6)
    void newFilesInTheWatchedFolderAreImported() throws Exception {
        Path file = inbox.resolve("Eingang").resolve("Lohnabrechnung Januar.pdf");
        Files.createDirectories(file.getParent());
        Files.write(file, pdf("Entgeltabrechnung Januar 2025", "Bruttolohn 4.200,00 EUR"));
        Files.write(inbox.resolve("kein-pdf.txt"), "ignorieren".getBytes());
        // Dateien, die eben erst geschrieben wurden, lässt der Import einen Durchlauf lang liegen
        FileTime settled = FileTime.from(Instant.now().minusSeconds(60));
        Files.setLastModifiedTime(file, settled);

        mvc.perform(put("/api/settings/import").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervalMinutes\":0,\"folderEnabled\":false,\"folderPath\":\"\",\"folderMode\":\"KEEP\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/import").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervalMinutes\":15,\"folderEnabled\":true,\"folderPath\":\"/gibt/es/nicht\",\"folderMode\":\"KEEP\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/import").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervalMinutes\":15,\"folderEnabled\":true,\"folderPath\":\"%s\",\"folderMode\":\"KEEP\"}"
                                .formatted(inbox)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalMinutes").value(15));

        folderImport.scan();
        awaitProcessed(5);
        assertThat(folderImport.status().message()).contains("1 neues PDF übernommen");
        assertThat(file).exists();
        mvc.perform(get("/api/documents").param("q", "Bruttolohn").session(userSession))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].source").value("FOLDER"));

        // unveränderte Dateien werden kein zweites Mal angefasst
        folderImport.scan();
        assertThat(folderImport.status().message()).isEqualTo("Keine neuen Dateien.");

        // Modus "verschieben": die übernommene Datei wandert nach importiert/
        Path second = inbox.resolve("Bescheid.pdf");
        Files.write(second, pdf("Grundsteuerbescheid der Gemeinde Beispielhausen"));
        Files.setLastModifiedTime(second, settled);
        mvc.perform(put("/api/settings/import").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervalMinutes\":15,\"folderEnabled\":true,\"folderPath\":\"%s\",\"folderMode\":\"MOVE\"}"
                                .formatted(inbox)))
                .andExpect(status().isOk());
        folderImport.scan();
        awaitProcessed(6);
        assertThat(second).doesNotExist();
        assertThat(inbox.resolve("importiert").resolve("Bescheid.pdf")).exists();
    }

    @Test
    @Order(7)
    void onlyAdminsDeleteAndDeletedDocumentsLeaveTheSearch() throws Exception {
        Document contract = documents.findAll().stream()
                .filter(doc -> doc.getOriginalFilename().equals("Mietvertrag.pdf")).findFirst().orElseThrow();
        mvc.perform(delete("/api/documents/" + contract.getId()).with(csrf()).session(userSession))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/documents/" + contract.getId()).with(csrf()).session(adminSession))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/documents").param("q", "Kaution").session(userSession))
                .andExpect(jsonPath("$.total").value(0));
        assertThat(dataDir.resolve(contract.getStoragePath())).doesNotExist();

        // ein deaktivierter Benutzer verliert seine laufende Sitzung sofort
        String userId = jsonValue(mvc.perform(get("/api/auth/status").session(userSession)).andReturn(),
                "$.user.id");
        mvc.perform(put("/api/users/" + userId).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\",\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/documents").session(userSession)).andExpect(status().isUnauthorized());
    }

    // --- Hilfen ---

    private void awaitProcessed(int expectedCount) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            List<Document> all = documents.findAll();
            boolean done = all.size() == expectedCount && all.stream().noneMatch(doc ->
                    doc.getStatus() == DocumentStatus.PENDING || doc.getStatus() == DocumentStatus.PROCESSING);
            if (done) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Erwartet: " + expectedCount + " fertig verarbeitete Dokumente, vorhanden: "
                + documents.findAll().stream().map(doc -> doc.getOriginalFilename() + "=" + doc.getStatus()).toList());
    }

    private static String jsonValue(MvcResult result, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private static byte[] pdf(String... lines) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(60, 720);
                for (String line : lines) {
                    content.showText(line);
                    content.newLineAtOffset(0, -18);
                }
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static MimeMessage mailWithPdf(String subject, String filename, byte[] pdf) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("kopierer@example.test", "Kopierer 2. OG"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("scans@example.test"));
        message.setSubject(subject);
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Im Anhang finden Sie Ihren Scan.");
        MimeBodyPart attachment = new MimeBodyPart();
        // Scanner schicken PDFs gern als application/octet-stream – der Dateiname muss dann genügen
        attachment.setDataHandler(new DataHandler(new ByteArrayDataSource(pdf, "application/octet-stream")));
        attachment.setFileName(filename);
        message.setContent(new MimeMultipart(text, attachment));
        message.saveChanges();
        return message;
    }

    private static MimeMessage mailWithoutAttachment(String subject) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("jemand@example.test"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("scans@example.test"));
        message.setSubject(subject);
        message.setText("Ohne Anhang.");
        message.saveChanges();
        return message;
    }

    private static Path createTempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
