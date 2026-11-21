package de.aidocuments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;
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
import de.aidocuments.i18n.Msg;
import de.aidocuments.i18n.MsgCodec;
import de.aidocuments.importer.FolderImportService;
import de.aidocuments.mail.MailAccount;
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
import java.util.Locale;
import java.util.Properties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
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

    /**
     * Der Server selbst „spricht“ in diesem Test Deutsch. Trotzdem muss jede Antwort ohne passenden
     * Accept-Language-Header englisch sein: Die Sprache des Servers darf nie durchschlagen.
     */
    static final Locale systemLocale = Locale.getDefault();
    /**
     * Eine Sprache, für die es keine messages_xx.properties gibt. Bewusst keine europäische: Deren Dateien
     * kommen nach und nach hinzu, und dieser Test soll davon unberührt bleiben. (tlh = Klingonisch)
     */
    static final String UNTRANSLATED = "tlh";

    static {
        Locale.setDefault(Locale.GERMANY);
    }

    @AfterAll
    static void restoreSystemLocale() {
        Locale.setDefault(systemLocale);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", dataDir::toString);
        // unabhängig von einer lokalen config/debug.yml: Auslieferungszustand ohne KI-Zugang
        registry.add("app.ai.provider", () -> "anthropic");
        // Ergebnisse sollen nicht davon abhängen, ob auf dem Rechner zufällig Tesseract installiert ist
        registry.add("app.ocr.enabled", () -> "false");
        registry.add("app.ai.base-url", () -> "");
        registry.add("app.ai.api-key", () -> "");
        registry.add("app.ai.model", () -> "claude-opus-5-5");
        registry.add("app.ai.reveal-preset-keys", () -> "false");
        // Vorbelegung, wie sie eine Entwicklungsumgebung für ihren lokalen Server mitbringt
        registry.add("app.ai.presets.lm-studio.base-url", () -> "http://127.0.0.1:9");
        registry.add("app.ai.presets.lm-studio.api-key", () -> "vorbelegter-testschluessel");
        registry.add("app.ai.presets.lm-studio.model", () -> "vorbelegtes-modell");
        registry.add("app.ai.presets.openai-compatible.base-url", () -> "");
        registry.add("app.ai.presets.openai-compatible.api-key", () -> "");
        registry.add("app.ai.presets.openai-compatible.model", () -> "");
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
    @Autowired
    MongoTemplate mongoTemplate;

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
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "de")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"account\":" + body + "}"))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message").value("Verbindung erfolgreich – E-Mails im Ordner „INBOX“: 2."));
        mvc.perform(post("/api/mail-accounts/test").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"account\":" + body + "}"))
                .andExpect(jsonPath("$.message").value("Connection successful – e-mails in folder “INBOX”: 2."));
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
        // Das Ergebnis entstand im Hintergrund, ohne Leser – es liegt sprachneutral in der Datenbank und wird
        // jedem in seiner Sprache ausgeliefert.
        assertThat(mailAccounts.findById(accountId).orElseThrow().getLastSyncMessage()).startsWith("i18n:");
        mvc.perform(get("/api/mail-accounts").session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$[0].lastSyncStatus").value("OK"))
                .andExpect(jsonPath("$[0].lastSyncMessage").value("Neue E-Mails geprüft: 2. PDFs übernommen: 1."))
                .andExpect(jsonPath("$[0].documentCount").value(1));
        mvc.perform(get("/api/mail-accounts").session(adminSession))
                .andExpect(jsonPath("$[0].lastSyncMessage").value("New e-mails checked: 2. PDFs imported: 1."));
        mvc.perform(get("/api/documents").param("q", "Kostenplan").session(userSession))
                .andExpect(jsonPath("$.total").value(1));

        // zweiter Abruf: nichts Neues; danach kommt eine weitere E-Mail
        mailSync.sync(accountId);
        assertThat(documents.count()).isEqualTo(3);
        mvc.perform(get("/api/mail-accounts").session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$[0].lastSyncMessage").value("Keine neuen E-Mails."));
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
        assertThat(folderImport.status().message()).startsWith("i18n:");
        mvc.perform(get("/api/settings/import").session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$.folderStatus.ok").value(true))
                .andExpect(jsonPath("$.folderStatus.message").value("Neue PDFs übernommen: 1."));
        mvc.perform(get("/api/settings/import").session(adminSession))
                .andExpect(jsonPath("$.folderStatus.message").value("New PDFs imported: 1."));
        assertThat(file).exists();
        mvc.perform(get("/api/documents").param("q", "Bruttolohn").session(userSession))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].source").value("FOLDER"));

        // unveränderte Dateien werden kein zweites Mal angefasst
        folderImport.scan();
        mvc.perform(get("/api/settings/import").session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$.folderStatus.message").value("Keine neuen Dateien."));

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
    @Order(4)
    void aChangedPdfIsSavedAndTheOriginalIsAlwaysKept() throws Exception {
        Document invoice = documents.findAll().stream()
                .filter(doc -> doc.getOriginalFilename().startsWith("Stromrechnung")).findFirst().orElseThrow();
        String id = invoice.getId();
        byte[] original = mvc.perform(get("/api/documents/" + id + "/file").session(userSession))
                .andReturn().getResponse().getContentAsByteArray();
        byte[] changed = pdf("Rechnungsbetrag 482,17 EUR", "Geprüft und bezahlt");

        // kein PDF → abgelehnt, nichts verändert
        mvc.perform(put("/api/documents/" + id + "/file").with(csrf()).session(userSession)
                        .contentType(MediaType.APPLICATION_PDF).content("kein pdf".getBytes()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents/" + id + "/original").session(userSession))
                .andExpect(status().isNotFound());

        // die erste Änderung legt das Original beiseite
        mvc.perform(put("/api/documents/" + id + "/file").with(csrf()).session(userSession)
                        .contentType(MediaType.APPLICATION_PDF).content(changed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasOriginal").value(true))
                .andExpect(jsonPath("$.modifiedAt").isNotEmpty())
                .andExpect(jsonPath("$.id").value(id));
        // eine zweite überschreibt nur die Arbeitsfassung, nie das Original
        byte[] changedAgain = pdf("Rechnungsbetrag 482,17 EUR", "Geprüft, bezahlt und abgelegt");
        mvc.perform(put("/api/documents/" + id + "/file").with(csrf()).session(userSession)
                        .contentType(MediaType.APPLICATION_PDF).content(changedAgain))
                .andExpect(status().isOk());

        assertThat(mvc.perform(get("/api/documents/" + id + "/file").session(userSession))
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(changedAgain);
        assertThat(mvc.perform(get("/api/documents/" + id + "/original").session(userSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(original);
        // Text und Suche folgen der geänderten Fassung
        mvc.perform(get("/api/documents").param("q", "abgelegt").session(userSession))
                .andExpect(jsonPath("$.total").value(1));

        // wiederherstellen: wieder genau das Original, die Kopie ist überflüssig
        mvc.perform(post("/api/documents/" + id + "/restore-original").with(csrf()).session(userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasOriginal").value(false))
                .andExpect(jsonPath("$.modifiedAt").isEmpty());
        assertThat(mvc.perform(get("/api/documents/" + id + "/file").session(userSession))
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(original);
        mvc.perform(get("/api/documents").param("q", "abgelegt").session(userSession))
                .andExpect(jsonPath("$.total").value(0));
        assertThat(documents.findById(id).orElseThrow().getOriginalStoragePath()).isNull();
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

    @Test
    @Order(8)
    void llmProviderIsSelectableAndKeysNeverMoveToAnotherProvider() throws Exception {
        mvc.perform(get("/api/settings/ai").session(adminSession))
                .andExpect(jsonPath("$.provider").value("ANTHROPIC"))
                .andExpect(jsonPath("$.baseUrl").value("https://api.anthropic.com"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.providers", hasSize(5)))
                .andExpect(jsonPath("$.providers[?(@.id == 'OPENAI')].cloud").value(true))
                .andExpect(jsonPath("$.providers[?(@.id == 'LM_STUDIO')].cloud").value(false))
                .andExpect(jsonPath("$.providers[?(@.id == 'LM_STUDIO')].defaultBaseUrl").value("http://localhost:1234/v1"));

        String rest = "\"effort\":\"medium\",\"outputLanguage\":\"Deutsch\",\"ocr\":true}";
        mvc.perform(put("/api/settings/ai").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"OPENAI\",\"baseUrl\":\"\",\"apiKey\":\"sk-test-nur-fuer-den-test\","
                                + "\"model\":\"modell-x\"," + rest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("OPENAI"))
                .andExpect(jsonPath("$.baseUrl").value("https://api.openai.com/v1"))
                .andExpect(jsonPath("$.apiKeySet").value(true))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        // Anbieterwechsel ohne neuen Schlüssel: der OpenAI-Schlüssel darf nicht zu Moonshot wandern
        mvc.perform(put("/api/settings/ai").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"MOONSHOT\",\"baseUrl\":\"\",\"model\":\"modell-y\"," + rest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("https://api.moonshot.ai/v1"))
                .andExpect(jsonPath("$.apiKeySet").value(false))
                .andExpect(jsonPath("$.enabled").value(false));

        // lokale Anbieter brauchen keinen Schlüssel; eine Adresse ohne Pfad bekommt /v1
        mvc.perform(put("/api/settings/ai").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"OPENAI_COMPATIBLE\",\"baseUrl\":\"\",\"model\":\"m\"," + rest))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/ai").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"OPENAI_COMPATIBLE\",\"baseUrl\":\"http://127.0.0.1:9/\",\"model\":\"m\","
                                + rest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("http://127.0.0.1:9/v1"))
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(post("/api/settings/ai/test").with(csrf()).session(adminSession)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message", containsString("nicht erreichbar")));
        mvc.perform(post("/api/settings/ai/test").with(csrf()).session(adminSession))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message", containsString("cannot be reached")));
        mvc.perform(get("/api/settings/ai/models").session(adminSession))
                .andExpect(jsonPath("$", hasSize(0)));

        // Vorbelegung aus der Konfiguration: Adresse und Modell stehen für die Oberfläche bereit,
        // der Schlüssel wird nur als "vorhanden" gemeldet und nie ausgeliefert.
        MvcResult settings = mvc.perform(get("/api/settings/ai").session(adminSession))
                .andExpect(jsonPath("$.providers[?(@.id == 'LM_STUDIO')].presetBaseUrl").value("http://127.0.0.1:9/v1"))
                .andExpect(jsonPath("$.providers[?(@.id == 'LM_STUDIO')].presetModel").value("vorbelegtes-modell"))
                .andExpect(jsonPath("$.providers[?(@.id == 'LM_STUDIO')].presetApiKeySet").value(true))
                .andExpect(jsonPath("$.providers[?(@.id == 'OPENAI_COMPATIBLE')].presetBaseUrl").value(""))
                .andExpect(jsonPath("$.providers[?(@.id == 'OPENAI')].presetApiKeySet").value(false))
                .andReturn();
        assertThat(settings.getResponse().getContentAsString()).doesNotContain("vorbelegter-testschluessel");

        // So speichert die Oberfläche nach dem Auswählen: vorbelegte Werte, kein Schlüssel im Formular
        mvc.perform(put("/api/settings/ai").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"LM_STUDIO\",\"baseUrl\":\"http://127.0.0.1:9/v1\","
                                + "\"model\":\"vorbelegtes-modell\"," + rest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeySet").value(true))
                .andExpect(jsonPath("$.apiKeySource").value("ENVIRONMENT"))
                .andExpect(jsonPath("$.enabled").value(true));
        // Wer danach bewusst die Standardadresse speichert, bekommt nicht wieder die Vorbelegung untergeschoben
        mvc.perform(put("/api/settings/ai").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"LM_STUDIO\",\"baseUrl\":\"http://localhost:1234/v1\","
                                + "\"model\":\"anderes-modell\"," + rest))
                .andExpect(jsonPath("$.baseUrl").value("http://localhost:1234/v1"))
                .andExpect(jsonPath("$.model").value("anderes-modell"));
    }

    @Test
    @Order(9)
    void errorsAndValidationAnswerInTheLanguageOfTheRequest() throws Exception {
        String missing = "/api/documents/gibt-es-nicht";
        // ohne Angabe: Englisch – obwohl der Server selbst auf Deutsch eingestellt ist
        mvc.perform(get(missing).session(adminSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("The document was not found."));
        mvc.perform(get(missing).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Das Dokument wurde nicht gefunden."));
        mvc.perform(get(missing).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de-AT,de;q=0.9,en;q=0.8"))
                .andExpect(jsonPath("$.detail").value("Das Dokument wurde nicht gefunden."));
        // eine Sprache ohne Übersetzungsdatei: Englisch
        mvc.perform(get(missing).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, UNTRANSLATED))
                .andExpect(jsonPath("$.detail").value("The document was not found."));
        mvc.perform(get(missing).session(adminSession)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, UNTRANSLATED + "-AQ," + UNTRANSLATED + ";q=0.9"))
                .andExpect(jsonPath("$.detail").value("The document was not found."));
        // … es sei denn, der Leser nennt dahinter eine, die es gibt
        mvc.perform(get(missing).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, UNTRANSLATED + ",de;q=0.5"))
                .andExpect(jsonPath("$.detail").value("Das Dokument wurde nicht gefunden."));
        // Unverständliches: Englisch
        mvc.perform(get(missing).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "*"))
                .andExpect(jsonPath("$.detail").value("The document was not found."));
        mvc.perform(get(missing).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "%%%"))
                .andExpect(jsonPath("$.detail").value("The document was not found."));

        // Meldungen mit Argument
        String duplicate = "{\"username\":\"chef\",\"password\":\"test-Passwort-5\",\"role\":\"USER\"}";
        mvc.perform(post("/api/users").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The username “chef” is already taken."));
        mvc.perform(post("/api/users").with(csrf()).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de")
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Der Benutzername „chef“ ist bereits vergeben."));

        // Eingabeprüfung: eigene Meldung, Standardprüfung und Prüfung mit Grenzwert
        String invalid = "{\"username\":\"neu\",\"password\":\"kurz\"}";
        mvc.perform(post("/api/users").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("password: at least 8 characters")))
                .andExpect(jsonPath("$.detail", containsString("role: must not be null")));
        mvc.perform(post("/api/users").with(csrf()).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de")
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("password: mindestens 8 Zeichen")))
                .andExpect(jsonPath("$.detail", containsString("role: darf nicht null sein")));
        mvc.perform(post("/api/users").with(csrf()).session(adminSession)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, UNTRANSLATED)
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(jsonPath("$.detail", containsString("role: must not be null")));
        String tooLong = "{\"title\":\"" + "x".repeat(501) + "\"}";
        String anyId = documents.findAll().getFirst().getId();
        mvc.perform(put("/api/documents/" + anyId).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("title: size must be between 0 and 500"));
        mvc.perform(put("/api/documents/" + anyId).with(csrf()).session(adminSession)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "de")
                        .contentType(MediaType.APPLICATION_JSON).content(tooLong))
                .andExpect(jsonPath("$.detail").value("title: Größe muss zwischen 0 und 500 sein"));

        // Ergebnis eines Uploads
        MockMultipartFile noPdf = new MockMultipartFile("files", "brief.pdf", "application/pdf", "kein PDF".getBytes());
        mvc.perform(multipart("/api/documents").file(noPdf).with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].message").value("The file is not a PDF."));
        mvc.perform(multipart("/api/documents").file(noPdf).with(csrf()).session(adminSession)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$[0].message").value("Die Datei ist kein PDF."));

        // Übersetzbare Bezeichnung in den Einstellungen
        mvc.perform(get("/api/settings/ai").session(adminSession))
                .andExpect(jsonPath("$.providers[?(@.id == 'OPENAI_COMPATIBLE')].label").value("OpenAI-compatible"));
        mvc.perform(get("/api/settings/ai").session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$.providers[?(@.id == 'OPENAI_COMPATIBLE')].label").value("OpenAI Kompatibel"));
    }

    @Test
    @Order(10)
    void messagesStoredInTheBackgroundAreDeliveredInTheLanguageOfTheReader() throws Exception {
        // Die Pipeline scheitert im Hintergrund an einem kaputten PDF – wer das später liest, steht da noch nicht fest.
        long before = documents.count();
        MockMultipartFile broken = new MockMultipartFile("files", "kaputt.pdf", "application/pdf",
                "%PDF-1.7\nhier endet die Datei".getBytes());
        MvcResult uploaded = mvc.perform(multipart("/api/documents").file(broken).with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("CREATED"))
                .andReturn();
        String brokenId = jsonValue(uploaded, "$[0].documentId");
        awaitProcessed((int) before + 1);

        Document failed = documents.findById(brokenId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.getErrorMessage()).startsWith("i18n:").contains("document.error.corrupt");
        mvc.perform(get("/api/documents/" + brokenId).session(adminSession))
                .andExpect(jsonPath("$.errorMessage", startsWith("The PDF is damaged or not a valid PDF: ")));
        mvc.perform(get("/api/documents/" + brokenId).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$.errorMessage", startsWith("Das PDF ist beschädigt oder kein gültiges PDF: ")));
        mvc.perform(get("/api/documents/" + brokenId).session(adminSession)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, UNTRANSLATED))
                .andExpect(jsonPath("$.errorMessage", startsWith("The PDF is damaged or not a valid PDF: ")));
        // auch in der Liste
        mvc.perform(get("/api/documents").param("status", "FAILED").session(adminSession)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$.items[0].errorMessage", startsWith("Das PDF ist beschädigt")));

        // Eine gescheiterte KI-Analyse: der Fehler der KI steckt als eigene Meldung in der des Dokuments
        failed.setErrorMessage(MsgCodec.encode(Msg.of("document.error.aiFailed", Msg.of("ai.error.unauthorized"))));
        documents.save(failed);
        mvc.perform(get("/api/documents/" + brokenId).session(adminSession))
                .andExpect(jsonPath("$.errorMessage")
                        .value("AI analysis failed: The API key was rejected by the AI server."));
        mvc.perform(get("/api/documents/" + brokenId).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$.errorMessage")
                        .value("KI-Analyse fehlgeschlagen: Der API-Schlüssel wurde vom KI-Server abgelehnt."));

        // Bestand aus der Zeit vor der Mehrsprachigkeit: deutscher Klartext bleibt, wie er ist – für jeden Leser
        String legacy = "KI-Analyse fehlgeschlagen: Der KI-Server meldet einen Fehler (HTTP 503).";
        failed.setErrorMessage(legacy);
        documents.save(failed);
        mvc.perform(get("/api/documents/" + brokenId).session(adminSession))
                .andExpect(jsonPath("$.errorMessage").value(legacy));
        mvc.perform(get("/api/documents/" + brokenId).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$.errorMessage").value(legacy));

        String accountId = mailAccounts.findAll().getFirst().getId();
        mongoTemplate.updateFirst(query(where("_id").is(accountId)),
                new Update().set("lastSyncMessage", "3 neue E-Mails geprüft, 1 PDF übernommen."), MailAccount.class);
        mvc.perform(get("/api/mail-accounts").session(adminSession))
                .andExpect(jsonPath("$[0].lastSyncMessage").value("3 neue E-Mails geprüft, 1 PDF übernommen."));

        // Ein Postfach-Abruf, der scheitert: bekannte Ursachen übersetzt, …
        mongoTemplate.updateFirst(query(where("_id").is(accountId)),
                new Update().set("folder", "Gibt es nicht"), MailAccount.class);
        mailSync.sync(accountId);
        mvc.perform(get("/api/mail-accounts").session(adminSession))
                .andExpect(jsonPath("$[0].lastSyncStatus").value("ERROR"))
                .andExpect(jsonPath("$[0].lastSyncMessage").value("The folder “Gibt es nicht” does not exist."));
        mvc.perform(get("/api/mail-accounts").session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$[0].lastSyncMessage").value("Der Ordner „Gibt es nicht“ existiert nicht."));
    }

    @Test
    @Order(11)
    void theSameLetterScannedTwiceIsMarkedAsPossibleDuplicate() throws Exception {
        String[] letter = {
                "Hausverwaltung Muster, Am Markt 3, 12345 Musterstadt",
                "Nebenkostenabrechnung für das Jahr 2025, Wohnung im zweiten Obergeschoss links",
                "Abrechnungsdatum: 15.04.2026",
                "Für den Abrechnungszeitraum ergeben sich die folgenden Kosten für Heizung,",
                "Wasser, Müllabfuhr, Hausmeister und Gartenpflege. Die Vorauszahlungen wurden",
                "berücksichtigt. Der Nachzahlungsbetrag ist innerhalb von vier Wochen zu überweisen.",
                "Nachzahlung: 312,40 EUR"};
        String[] rescan = letter.clone();
        rescan[3] = rescan[3].replace("Heizung", "Heizunq"); // ein Lesefehler beim zweiten Scan
        long before = documents.count();
        for (byte[] pdf : List.of(pdf(letter), pdf(rescan))) {
            mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files", "nebenkosten.pdf",
                            "application/pdf", pdf)).with(csrf()).session(adminSession))
                    .andExpect(jsonPath("$[0].outcome").value("CREATED"));
        }
        awaitProcessed((int) before + 2);

        List<Document> pair = documents.findAll().stream()
                .filter(doc -> doc.getOriginalFilename().equals("nebenkosten.pdf"))
                .sorted(java.util.Comparator.comparing(Document::getCreatedAt)).toList();
        assertThat(pair).hasSize(2);
        assertThat(pair.get(0).getDuplicateOf()).isNull();
        assertThat(pair.get(1).getDuplicateOf()).isEqualTo(pair.get(0).getId());
        mvc.perform(get("/api/documents").param("duplicates", "true").session(adminSession))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].duplicateOf").value(pair.get(0).getId()));
        mvc.perform(get("/api/system").session(adminSession)).andExpect(jsonPath("$.duplicates").value(1));

        // „kein Duplikat“ – und der Vermerk kommt auch bei erneuter Prüfung nicht wieder
        mvc.perform(post("/api/documents/" + pair.get(1).getId() + "/not-duplicate").with(csrf()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateOf").isEmpty());
        mvc.perform(post("/api/documents/" + pair.get(1).getId() + "/reprocess").with(csrf()).session(adminSession))
                .andExpect(status().isOk());
        awaitProcessed((int) before + 2);
        assertThat(documents.findById(pair.get(1).getId()).orElseThrow().getDuplicateOf()).isNull();
        mvc.perform(get("/api/documents").param("duplicates", "true").session(adminSession))
                .andExpect(jsonPath("$.total").value(0));

        for (Document doc : pair) {
            mvc.perform(delete("/api/documents/" + doc.getId()).with(csrf()).session(adminSession))
                    .andExpect(status().isNoContent());
        }
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
