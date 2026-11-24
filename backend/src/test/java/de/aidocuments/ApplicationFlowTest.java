package de.aidocuments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.time.LocalDate;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * Durchläuft die Anwendung von der Ersteinrichtung bis zur Suche gegen die eingebaute Datenbank (SQLite im
 * Datenverzeichnis) und einen echten IMAP-Server (GreenMail). Ohne KI-Schlüssel – geprüft wird alles außer dem
 * Aufruf der KI.
 *
 * <p>Mit {@code -Daidocs.test.database=mariadb} läuft derselbe Durchlauf gegen eine echte MariaDB (Testcontainers);
 * die POM tut das in einem eigenen Lauf (siehe Surefire-Ausführung „mariadb“).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApplicationFlowTest {

    static final MariaDBContainer mariaDb = "mariadb".equals(System.getProperty("aidocs.test.database"))
            ? new MariaDBContainer("mariadb:11.8")
            : null;

    @RegisterExtension
    static final GreenMailExtension greenMail =
            new GreenMailExtension(ServerSetupTest.SMTP_IMAP).withPerMethodLifecycle(false);

    static final Path dataDir = createTempDir("aidocs-data");
    static final Path inbox = createTempDir("aidocs-inbox");
    static final MockHttpSession adminSession = new MockHttpSession();
    static final MockHttpSession userSession = new MockHttpSession();

    /**
     * Änderungen an Konten, Postfächern und Einstellungen verlangen einen höchstens zehn Minuten alten Code aus der App
     * – die Bestätigung der Admin-Sitzung über die ganze (lange) Testreihe frisch halten; geprüft wird sie in Order 1.
     */
    @org.junit.jupiter.api.BeforeEach
    void keepAdminConfirmed() {
        if (!adminSession.isInvalid()) {
            adminSession.setAttribute("aidocuments.confirmedAt", java.time.Instant.now());
        }
    }

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

    /** wie AIDOCS_BACKUP_PASSWORD aus der .env: verschlüsselt die automatischen Sicherungen */
    static final String BACKUP_PASSWORD = "Sicherungs-Testpasswort-42";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", dataDir::toString);
        registry.add("app.backup.password", () -> BACKUP_PASSWORD);
        if (mariaDb != null) {
            mariaDb.start();
            registry.add("spring.datasource.url", mariaDb::getJdbcUrl);
            registry.add("spring.datasource.username", mariaDb::getUsername);
            registry.add("spring.datasource.password", mariaDb::getPassword);
        }
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
    de.aidocuments.importer.ImportSettingsService importSettings;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    de.aidocuments.document.DocumentService documentService;
    @Autowired
    de.aidocuments.contract.ContractService contractService;
    @Autowired
    de.aidocuments.user.UserService userService;
    @Autowired
    de.aidocuments.reminder.ReminderNotifier reminderNotifier;
    @Autowired
    de.aidocuments.notify.Notifications notifications;
    @Autowired
    de.aidocuments.backup.AutoBackup autoBackup;

    @Test
    @Order(1)
    void firstStartRequiresSetupAndProtectsTheApi() throws Exception {
        mvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("object-src 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(jsonPath("$.setupRequired").value(true))
                .andExpect(jsonPath("$.user").doesNotExist());
        mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());

        String code = userService.setupCode();
        mvc.perform(post("/api/auth/setup").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Chef\",\"password\":\"kurz\",\"setupCode\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest());
        // ohne den Einrichtungscode aus dem Protokoll kann niemand die frische Instanz übernehmen
        mvc.perform(post("/api/auth/setup").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"angreifer\",\"password\":\"test-Passwort-9\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/setup").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"angreifer\",\"password\":\"test-Passwort-9\",\"setupCode\":\"AAAA-BBBB-CCCC\"}"))
                .andExpect(status().isUnauthorized());
        // ein falscher Freischaltcode richtet nichts halb ein
        mvc.perform(post("/api/auth/setup").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Chef\",\"password\":\"test-Passwort-1\",\"setupCode\":\"" + code
                                + "\",\"usage\":\"BUSINESS\",\"licenseCode\":\"AIDOCS-12345-67890\"}"))
                .andExpect(status().isBadRequest());
        assertThat(userService.setupRequired()).isTrue();
        mvc.perform(post("/api/auth/setup").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Chef\",\"password\":\"test-Passwort-1\",\"setupCode\":\""
                                + code.toLowerCase().replace("-", " ") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("chef"))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        // die Ersteinrichtung ist einmalig und meldet direkt an
        mvc.perform(post("/api/auth/setup").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zweiter\",\"password\":\"test-Passwort-2\",\"setupCode\":\"" + code + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/auth/status").session(adminSession))
                .andExpect(jsonPath("$.setupRequired").value(false))
                .andExpect(jsonPath("$.user.username").value("chef"))
                .andExpect(jsonPath("$.user.twoFactorEnabled").value(false));

        // für Administratoren ist die Zwei-Faktor-Anmeldung Pflicht: vorher geht nichts außer der Einrichtung
        mvc.perform(get("/api/users").session(adminSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("mandatory for administrators")));
        mvc.perform(get("/api/documents").session(adminSession)).andExpect(status().isForbidden());
        // auch nicht über kodierte Zeichen im Pfad („/%61pi“ ist für Spring „/api“)
        mvc.perform(get(java.net.URI.create("/%61pi/documents")).session(adminSession))
                .andExpect(status().isForbidden());
        mvc.perform(get(java.net.URI.create("/api/%64ocuments")).session(adminSession))
                .andExpect(status().isForbidden());
        while (java.time.Instant.now().getEpochSecond() % 30 >= 25) {
            Thread.sleep(500);
        }
        String secret = jsonValue(mvc.perform(post("/api/auth/two-factor/setup").with(csrf()).session(adminSession))
                .andReturn(), "$.secret");
        mvc.perform(post("/api/auth/two-factor/enable").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"test-Passwort-1\",\"code\":\"" + totp(secret, 0) + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users").session(adminSession)).andExpect(status().isOk());
        // und lässt sich für sie nicht abschalten
        mvc.perform(post("/api/auth/two-factor/disable").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"test-Passwort-1\",\"code\":\"" + totp(secret, 1) + "\"}"))
                .andExpect(status().isConflict());

        // Änderungen an Konten, Postfächern und Einstellungen verlangen einen höchstens zehn Minuten alten Code
        adminSession.setAttribute("aidocuments.confirmedAt",
                java.time.Instant.now().minus(java.time.Duration.ofMinutes(11)));
        mvc.perform(post("/api/users").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("confirmationRequired"));
        mvc.perform(put("/api/settings/ai").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(java.net.URI.create("/api/%75sers/x")).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("confirmationRequired"));
        mvc.perform(put(java.net.URI.create("/%61pi/%73ettings/ai")).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("confirmationRequired"));
        mvc.perform(get("/api/users").session(adminSession)).andExpect(status().isOk()); // lesen geht weiter
        mvc.perform(post("/api/auth/confirm").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + totp(secret, 7) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/confirm").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + totp(secret, 1) + "\"}"))
                .andExpect(status().isNoContent());
        // jetzt kommt die Anfrage durch (und scheitert erst an der Eingabeprüfung)
        mvc.perform(post("/api/users").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
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

        // das gespeicherte Passwort geht nur an den Server, für den es gespeichert wurde
        String otherServer = body.replace("\"127.0.0.1\"", "\"mail.example.test\"")
                .replace("\"password\":\"imap-test-secret\",", "");
        mvc.perform(post("/api/mail-accounts/test").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"account\":" + otherServer + "}"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("password again")));
        mvc.perform(put("/api/mail-accounts/" + accountId).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(otherServer))
                .andExpect(status().isBadRequest());
        assertThat(mailAccounts.findById(accountId).orElseThrow().getHost()).isEqualTo("127.0.0.1");

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

        String thumbnailBefore = documents.findById(id).orElseThrow().getThumbnailAt().toString();
        Thread.sleep(20); // Zeitstempel des Dateisystems müssen sich unterscheiden können
        // die erste Änderung legt das Original beiseite
        mvc.perform(put("/api/documents/" + id + "/file").with(csrf()).session(userSession)
                        .contentType(MediaType.APPLICATION_PDF).content(changed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasOriginal").value(true))
                .andExpect(jsonPath("$.modifiedAt").isNotEmpty())
                .andExpect(jsonPath("$.id").value(id));
        // neues Vorschaubild – mit neuem Zeitpunkt, damit die Oberfläche es neu lädt
        assertThat(documents.findById(id).orElseThrow().getThumbnailAt().toString()).isNotEqualTo(thumbnailBefore);
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
        // zunächst im Papierkorb: Datei bleibt, das Dokument ist aus Liste und Suche verschwunden
        assertThat(documents.findById(contract.getId())).isEmpty();
        assertThat(dataDir.resolve(contract.getStoragePath())).exists();

        // über die Historie zurückholen – das dürfen nur Administratoren
        String deletion = historyEntryId(contract.getId(), "DELETED");
        mvc.perform(post("/api/history/" + deletion + "/undo").with(csrf()).session(userSession))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/history/" + deletion + "/undo").with(csrf()).session(adminSession))
                .andExpect(status().isOk());
        mvc.perform(get("/api/documents").param("q", "Kaution").session(userSession))
                .andExpect(jsonPath("$.total").value(1));
        // ein zweites Mal geht nicht
        mvc.perform(post("/api/history/" + deletion + "/undo").with(csrf()).session(adminSession))
                .andExpect(status().isConflict());

        // erneut löschen; nach Ablauf der Frist wird endgültig aufgeräumt
        mvc.perform(delete("/api/documents/" + contract.getId()).with(csrf()).session(adminSession))
                .andExpect(status().isNoContent());
        jdbc.sql("UPDATE deleted_documents SET deleted_at = ? WHERE id = ?")
                .param(Instant.now().minus(Duration.ofDays(31)).toEpochMilli()).param(contract.getId()).update();
        documentService.purgeTrash();
        assertThat(dataDir.resolve(contract.getStoragePath())).doesNotExist();
        assertThat(documents.findInTrash(contract.getId())).isEmpty();

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
                .andExpect(jsonPath("$[0].message").value("The file is neither a PDF nor a photo (JPG, PNG, HEIC) nor an e-invoice (XML)."));
        mvc.perform(multipart("/api/documents").file(noPdf).with(csrf()).session(adminSession)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$[0].message").value("Die Datei ist weder ein PDF noch ein Foto (JPG, PNG, HEIC) noch eine E-Rechnung (XML)."));

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
        jdbc.sql("UPDATE mail_accounts SET last_sync_message = ? WHERE id = ?")
                .param("3 neue E-Mails geprüft, 1 PDF übernommen.").param(accountId).update();
        mvc.perform(get("/api/mail-accounts").session(adminSession))
                .andExpect(jsonPath("$[0].lastSyncMessage").value("3 neue E-Mails geprüft, 1 PDF übernommen."));

        // Ein Postfach-Abruf, der scheitert: bekannte Ursachen übersetzt, …
        jdbc.sql("UPDATE mail_accounts SET folder = ? WHERE id = ?").param("Gibt es nicht").param(accountId).update();
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

        // die Historie hält Import (mit Quelle und Anwender) und Löschung fest – neueste zuerst
        String historyJson = mvc.perform(get("/api/history").param("size", "200").session(adminSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = pair.get(1).getId();
        List<java.util.Map<String, Object>> entries = com.jayway.jsonpath.JsonPath.read(historyJson,
                "$.items[?(@.documentId == '" + id + "')]");
        assertThat(entries).extracting(e -> e.get("action")).containsExactly("DELETED", "IMPORTED");
        assertThat(entries.get(1)).containsEntry("source", "UPLOAD").containsEntry("user", "chef");
        assertThat((String) entries.get(1).get("origin")).contains("nebenkosten.pdf");
        assertThat(entries.get(0)).containsEntry("documentTitle", pair.get(1).getTitle());
        List<String> actions = com.jayway.jsonpath.JsonPath.read(historyJson, "$.items[*].action");
        assertThat(actions).contains("EDITED");
    }

    @Test
    @Order(12)
    void editsCanBeUndoneStepByStepFromTheHistory() throws Exception {
        long before = documents.count();
        byte[] original = pdf("Angebot Dachdecker Meier", "Gesamtbetrag 4.180,00 EUR", "gültig bis 30.06.2026");
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files", "angebot.pdf",
                        "application/pdf", original)).with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("CREATED"));
        awaitProcessed((int) before + 1);
        String id = documents.findAll().stream().filter(d -> d.getOriginalFilename().equals("angebot.pdf"))
                .findFirst().orElseThrow().getId();
        String fileUrl = "/api/documents/" + id + "/file";

        // Angaben ändern, dann zweimal das PDF, dann das Original wiederherstellen
        mvc.perform(put("/api/documents/" + id).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Angebot Dach\",\"category\":\"Angebot\",\"tags\":[\"dach\"]}"))
                .andExpect(status().isOk());
        byte[] first = pdf("Angebot Dachdecker Meier", "Gesamtbetrag 4.180,00 EUR", "angenommen");
        byte[] second = pdf("Angebot Dachdecker Meier", "Gesamtbetrag 4.180,00 EUR", "angenommen und bezahlt");
        for (byte[] version : List.of(first, second)) {
            mvc.perform(put(fileUrl).with(csrf()).session(adminSession)
                    .contentType(MediaType.APPLICATION_PDF).content(version)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/documents/" + id + "/restore-original").with(csrf()).session(adminSession))
                .andExpect(status().isOk());
        assertThat(file(fileUrl)).isEqualTo(original);

        // nur der neueste Schritt lässt sich zurücknehmen
        String details = historyEntryId(id, "EDITED", "DETAILS");
        mvc.perform(post("/api/history/" + details + "/undo").with(csrf()).session(adminSession))
                .andExpect(status().isConflict());

        // Schritt für Schritt zurück: Original wiederhergestellt → zweite Fassung → erste Fassung → Original
        undoLatest(id, "ORIGINAL_RESTORED");
        assertThat(file(fileUrl)).isEqualTo(second);
        undoLatest(id, "FILE");
        assertThat(file(fileUrl)).isEqualTo(first);
        undoLatest(id, "FILE");
        assertThat(file(fileUrl)).isEqualTo(original);
        mvc.perform(get("/api/documents/" + id).session(adminSession))
                .andExpect(jsonPath("$.hasOriginal").value(false));

        // keine Fassung ging dabei verloren: importiert, zweimal bearbeitet, Original, dreimal rückgängig
        String versionsUrl = "/api/documents/" + id + "/versions";
        mvc.perform(get(versionsUrl).session(adminSession))
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[0].number").value(7))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].kind").value("UNDONE"))
                .andExpect(jsonPath("$[3].kind").value("ORIGINAL_RESTORED"))
                .andExpect(jsonPath("$[3].createdBy").value("chef"))
                .andExpect(jsonPath("$[6].kind").value("IMPORTED"));
        assertThat(file(versionsUrl + "/1/file")).isEqualTo(original);
        assertThat(file(versionsUrl + "/2/file")).isEqualTo(first);
        assertThat(file(versionsUrl + "/3/file")).isEqualTo(second);
        assertThat(file(versionsUrl + "/7/file")).isEqualTo(original);
        mvc.perform(get(versionsUrl + "/99/file").session(adminSession)).andExpect(status().isNotFound());

        // eine ältere Fassung wiederherstellen ergibt eine neue Fassung – und lässt sich zurücknehmen
        mvc.perform(post(versionsUrl + "/7/restore").with(csrf()).session(adminSession))
                .andExpect(status().isConflict());
        mvc.perform(post(versionsUrl + "/3/restore").with(csrf()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasOriginal").value(true));
        assertThat(file(fileUrl)).isEqualTo(second);
        mvc.perform(get(versionsUrl).session(adminSession))
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[0].kind").value("VERSION_RESTORED"))
                .andExpect(jsonPath("$[0].restoredFrom").value(3));
        undoLatest(id, "VERSION_RESTORED");
        assertThat(file(fileUrl)).isEqualTo(original);
        assertThat(file(versionsUrl + "/8/file")).isEqualTo(second);

        // zuletzt die Angaben
        undoLatest(id, "DETAILS");
        mvc.perform(get("/api/documents/" + id).session(adminSession))
                .andExpect(jsonPath("$.title").value("angebot"))
                .andExpect(jsonPath("$.category").isEmpty())
                .andExpect(jsonPath("$.tags", hasSize(0)));

        mvc.perform(delete("/api/documents/" + id).with(csrf()).session(adminSession))
                .andExpect(status().isNoContent());
    }

    private byte[] file(String url) throws Exception {
        return mvc.perform(get(url).session(adminSession)).andReturn().getResponse().getContentAsByteArray();
    }

    /** Nimmt den neuesten Schritt des Dokuments zurück und prüft, dass es der erwartete war. */
    private void undoLatest(String documentId, String change) throws Exception {
        String json = mvc.perform(get("/api/history").param("size", "200").session(adminSession))
                .andReturn().getResponse().getContentAsString();
        List<java.util.Map<String, Object>> undoable = com.jayway.jsonpath.JsonPath.read(json,
                "$.items[?(@.documentId == '" + documentId + "' && @.undoable == true)]");
        assertThat(undoable).hasSize(1);
        assertThat(undoable.getFirst()).containsEntry("change", change);
        mvc.perform(post("/api/history/" + undoable.getFirst().get("id") + "/undo").with(csrf()).session(adminSession))
                .andExpect(status().isOk());
    }

    private String historyEntryId(String documentId, String action) throws Exception {
        return historyEntryId(documentId, action, null);
    }

    private String historyEntryId(String documentId, String action, String change) throws Exception {
        String json = mvc.perform(get("/api/history").param("size", "200").session(adminSession))
                .andReturn().getResponse().getContentAsString();
        List<java.util.Map<String, Object>> entries = com.jayway.jsonpath.JsonPath.read(json,
                "$.items[?(@.documentId == '" + documentId + "' && @.action == '" + action + "')]");
        return (String) entries.stream().filter(e -> change == null || change.equals(e.get("change")))
                .findFirst().orElseThrow().get("id");
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

    @Test
    @Order(13)
    void twoFactorLoginNeedsTheCodeFromTheApp() throws Exception {
        // nicht kurz vor einem Intervallwechsel beginnen – sonst verschieben sich die Codes mitten im Test
        while (java.time.Instant.now().getEpochSecond() % 30 >= 25) {
            Thread.sleep(500);
        }
        mvc.perform(post("/api/users").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zweifaktor\",\"password\":\"test-Passwort-7\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated());
        MockHttpSession erika = new MockHttpSession();
        mvc.perform(post("/api/auth/login").with(csrf()).session(erika).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zweifaktor\",\"password\":\"test-Passwort-7\"}"))
                .andExpect(status().isOk());

        // einrichten: Schlüssel holen, mit falschem Passwort bzw. Code abgelehnt, mit beidem richtig eingeschaltet
        MvcResult setup = mvc.perform(post("/api/auth/two-factor/setup").with(csrf()).session(erika))
                .andExpect(jsonPath("$.qrCode").value(startsWith("data:image/svg+xml;base64,")))
                .andReturn();
        String secret = jsonValue(setup, "$.secret");
        mvc.perform(post("/api/auth/two-factor/enable").with(csrf()).session(erika)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"falsch\",\"code\":\"" + totp(secret, 0) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/two-factor/enable").with(csrf()).session(erika)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"test-Passwort-7\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest());
        MvcResult enabled = mvc.perform(post("/api/auth/two-factor/enable").with(csrf()).session(erika)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"test-Passwort-7\",\"code\":\"" + totp(secret, -1) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes", hasSize(10)))
                .andReturn();
        String recoveryCode = jsonValue(enabled, "$.recoveryCodes[0]");
        // die eigene Sitzung bleibt angemeldet; der Schlüssel liegt nur verschlüsselt in der Datenbank
        mvc.perform(get("/api/auth/two-factor").session(erika)).andExpect(jsonPath("$.enabled").value(true));
        assertThat(jdbc.sql("SELECT totp_secret FROM users WHERE username = 'zweifaktor'").query(String.class).single())
                .doesNotContain(secret);

        // anmelden: Passwort allein reicht nicht mehr
        MockHttpSession login = new MockHttpSession();
        mvc.perform(post("/api/auth/login").with(csrf()).session(login).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zweifaktor\",\"password\":\"test-Passwort-7\"}"))
                .andExpect(jsonPath("$.twoFactorRequired").value(true));
        mvc.perform(get("/api/documents").session(login)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login/two-factor").with(csrf()).session(login)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        // der beim Einrichten benutzte Code gilt nicht noch einmal; der aktuelle schon
        mvc.perform(post("/api/auth/login/two-factor").with(csrf()).session(login)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + totp(secret, -1) + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login/two-factor").with(csrf()).session(login)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + totp(secret, 0) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("zweifaktor"))
                .andExpect(jsonPath("$.twoFactorEnabled").value(true));
        mvc.perform(get("/api/documents").session(login)).andExpect(status().isOk());

        // ein Wiederherstellungscode funktioniert genau einmal
        MockHttpSession viaRecovery = null;
        for (int round = 0; round < 2; round++) {
            MockHttpSession again = new MockHttpSession();
            if (round == 0) {
                viaRecovery = again;
            }
            mvc.perform(post("/api/auth/login").with(csrf()).session(again).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"zweifaktor\",\"password\":\"test-Passwort-7\"}"))
                    .andExpect(jsonPath("$.twoFactorRequired").value(true));
            mvc.perform(post("/api/auth/login/two-factor").with(csrf()).session(again)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + recoveryCode.toUpperCase() + "\"}"))
                    .andExpect(round == 0 ? status().isOk() : status().isUnauthorized());
        }

        // an einer fremden, offen gelassenen Sitzung lässt sich der Bestätigungscode nicht durchprobieren:
        // nach fünf falschen Codes ist die Sitzung beendet
        for (int attempt = 1; attempt <= 5; attempt++) {
            mvc.perform(post("/api/auth/confirm").with(csrf()).session(viaRecovery)
                            .with(request -> {
                                request.setRemoteAddr("192.0.2.77");
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + totp(secret, 9 + attempt) + "\"}"))
                    .andExpect(attempt < 5 ? status().isBadRequest() : status().isUnauthorized());
        }
        mvc.perform(get("/api/documents").session(viaRecovery)).andExpect(status().isUnauthorized());

        // ohne vorher geprüftes Passwort gibt es keinen zweiten Schritt
        mvc.perform(post("/api/auth/login/two-factor").with(csrf()).session(new MockHttpSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + totp(secret, 1) + "\"}"))
                .andExpect(status().isUnauthorized());

        // Gerät verloren: ein Administrator setzt zurück, danach genügt wieder das Passwort
        String erikaId = jsonValue(mvc.perform(get("/api/auth/status").session(login)).andReturn(), "$.user.id");
        mvc.perform(delete("/api/users/" + erikaId + "/two-factor").with(csrf()).session(login))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/users/" + erikaId + "/two-factor").with(csrf()).session(adminSession))
                .andExpect(jsonPath("$.twoFactorEnabled").value(false));
        mvc.perform(get("/api/documents").session(login)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).session(new MockHttpSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zweifaktor\",\"password\":\"test-Passwort-7\"}"))
                .andExpect(jsonPath("$.username").value("zweifaktor"));
    }

    @Test
    @Order(14)
    void aDocumentCanBeSharedByLinkWithPeopleWithoutAnAccount() throws Exception {
        Document invoice = documents.findAll().stream()
                .filter(doc -> doc.getOriginalFilename().startsWith("Stromrechnung")).findFirst().orElseThrow();
        String id = invoice.getId();
        String sharesUrl = "/api/documents/" + id + "/shares";
        // ein Benutzer ohne Adminrechte teilt
        mvc.perform(post("/api/users").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"teilerin\",\"password\":\"test-Passwort-8\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated());
        MockHttpSession member = new MockHttpSession();
        mvc.perform(post("/api/auth/login").with(csrf()).session(member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"teilerin\",\"password\":\"test-Passwort-8\"}"))
                .andExpect(status().isOk());

        // nur Angemeldete teilen; Name der Person ist Pflicht
        mvc.perform(get(sharesUrl)).andExpect(status().isUnauthorized());
        mvc.perform(post(sharesUrl).with(csrf()).session(member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"  \",\"canEdit\":false}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/documents/gibt-es-nicht/shares").with(csrf()).session(member)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recipient\":\"Pat\",\"canEdit\":false}"))
                .andExpect(status().isNotFound());

        MvcResult viewOnly = mvc.perform(post(sharesUrl).with(csrf()).session(member)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"Steuerbüro Beispiel\",\"canEdit\":false,\"expiresInDays\":30}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.createdBy").value("teilerin"))
                .andReturn();
        String viewToken = jsonValue(viewOnly, "$.token");
        String viewShareId = jsonValue(viewOnly, "$.id");
        assertThat(viewToken).matches("[A-Za-z0-9_-]{43}");
        // in der Datenbank steht der Link weder im Klartext noch als bloße Kodierung
        for (String column : List.of("token_hash", "token_encrypted")) {
            assertThat(jdbc.sql("SELECT " + column + " FROM document_shares WHERE id = ?")
                    .param(viewShareId).query(String.class).single()).doesNotContain(viewToken);
        }
        String editToken = jsonValue(mvc.perform(post(sharesUrl).with(csrf()).session(member)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"Pat Extern\",\"canEdit\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").isEmpty())
                .andReturn(), "$.token");

        // ohne Sitzung: genau dieses Dokument, ohne die übrigen Angaben
        byte[] before = mvc.perform(get("/api/documents/" + id + "/file").session(member))
                .andReturn().getResponse().getContentAsByteArray();
        mvc.perform(get("/api/share/" + viewToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Robots-Tag", containsString("noindex")))
                .andExpect(jsonPath("$.title").value("Stromrechnung 2025"))
                .andExpect(jsonPath("$.recipient").value("Steuerbüro Beispiel"))
                .andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.tags").doesNotExist())
                .andExpect(jsonPath("$.summary").doesNotExist());
        assertThat(mvc.perform(get("/api/share/" + viewToken + "/file"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(before);
        // der Link öffnet nichts sonst
        mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());

        // nur ansehen: Speichern abgelehnt, Datei unverändert
        byte[] changed = pdf("Rechnungsbetrag 482,17 EUR", "Vom Steuerbüro geprüft");
        mvc.perform(put("/api/share/" + viewToken + "/file").with(csrf())
                        .contentType(MediaType.APPLICATION_PDF).content(changed))
                .andExpect(status().isForbidden());
        // darf bearbeiten: neue Fassung, in Fassungen und Historie mit dem Namen der Person
        mvc.perform(put("/api/share/" + editToken + "/file").with(csrf())
                        .contentType(MediaType.APPLICATION_PDF).content("kein pdf".getBytes()))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/share/" + editToken + "/file").with(csrf())
                        .contentType(MediaType.APPLICATION_PDF).content(changed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modifiedAt").isNotEmpty());
        assertThat(mvc.perform(get("/api/share/" + viewToken + "/file"))
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(changed);
        // mehr als beim Hochladen erlaubt wird auch über einen Link nicht angenommen
        byte[] huge = new byte[50 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-1.7".getBytes(), 0, huge, 0, 8);
        mvc.perform(put("/api/share/" + editToken + "/file").with(csrf())
                        .contentType(MediaType.APPLICATION_PDF).content(huge))
                .andExpect(status().isContentTooLarge());
        mvc.perform(get("/api/documents/" + id + "/versions").session(member))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].createdBy").value("Link: Pat Extern"));
        String historyJson = mvc.perform(get("/api/history").param("size", "200").session(adminSession))
                .andReturn().getResponse().getContentAsString();
        List<String> editors = com.jayway.jsonpath.JsonPath.read(historyJson,
                "$.items[?(@.documentId == '" + id + "' && @.change == 'FILE')].user");
        assertThat(editors).first().isEqualTo("Link: Pat Extern");

        // die Dokumentansicht zeigt, mit wem geteilt ist – und wie oft der Link aufgerufen wurde
        mvc.perform(get(sharesUrl).session(member))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].recipient").value("Pat Extern"))
                .andExpect(jsonPath("$[1].recipient").value("Steuerbüro Beispiel"))
                .andExpect(jsonPath("$[1].accessCount").value(1))
                .andExpect(jsonPath("$[1].lastAccessAt").isNotEmpty())
                .andExpect(jsonPath("$[1].token").value(viewToken));

        // falsches Format, unbekannt, abgelaufen: alles dieselbe Antwort
        for (String wrong : List.of("kurz", viewToken.substring(1) + "!", "A".repeat(43))) {
            mvc.perform(get("/api/share/" + wrong)).andExpect(status().isNotFound());
        }
        jdbc.sql("UPDATE document_shares SET expires_at = ? WHERE id = ?")
                .param(Instant.now().minusSeconds(60).toEpochMilli()).param(viewShareId).update();
        mvc.perform(get("/api/share/" + viewToken)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").isNotEmpty());
        mvc.perform(get("/api/share/" + viewToken + "/file")).andExpect(status().isNotFound());
        mvc.perform(get(sharesUrl).session(member)).andExpect(jsonPath("$[1].expired").value(true));

        // im Papierkorb ist das Dokument auch über den Link weg – und nach dem Zurückholen wieder da
        mvc.perform(delete("/api/documents/" + id).with(csrf()).session(adminSession))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/share/" + editToken)).andExpect(status().isNotFound());
        mvc.perform(put("/api/share/" + editToken + "/file").with(csrf())
                        .contentType(MediaType.APPLICATION_PDF).content(changed))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/history/" + historyEntryId(id, "DELETED") + "/undo").with(csrf()).session(adminSession))
                .andExpect(status().isOk());
        mvc.perform(get("/api/share/" + editToken)).andExpect(status().isOk());

        // entziehen dürfen auch Benutzer ohne Adminrechte; danach ist der Link tot
        String editShareId = jsonValue(mvc.perform(get(sharesUrl).session(member)).andReturn(), "$[0].id");
        mvc.perform(delete(sharesUrl + "/" + editShareId).with(csrf()).session(member))
                .andExpect(status().isNoContent());
        mvc.perform(delete(sharesUrl + "/" + editShareId).with(csrf()).session(member))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/share/" + editToken)).andExpect(status().isNotFound());
        mvc.perform(get(sharesUrl).session(member)).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @Order(15)
    void onlyMailsMatchingTheSubjectAndSenderFilterAreEvaluated() throws Exception {
        GreenMailUser mailbox = greenMail.setUser("gefiltert@example.test", "gefiltert@example.test", "imap-test-secret");
        InternetAddress copier = new InternetAddress("geraet@kopierer.example", "Kopierer Empfang");
        mailbox.deliver(mailWithPdf("Scan 17", "Scan_17.pdf", pdf("Filtertest: Lieferschein 17"), copier));
        mailbox.deliver(mailWithPdf("Rechnung Mai", "Rechnung.pdf", pdf("Filtertest: Rechnung Mai"), copier));
        mailbox.deliver(mailWithPdf("Scan Werbung", "Werbung.pdf", pdf("Filtertest: Werbung"), copier));
        mailbox.deliver(mailWithPdf("Scan 18", "Scan_18.pdf", pdf("Filtertest: fremder Absender"),
                new InternetAddress("jemand@other.example")));
        mailbox.deliver(mailWithPdf("SCAN 19", "Scan_19.pdf", pdf("Filtertest: private Kopie"),
                new InternetAddress("privat@kopierer.example", "Privat")));

        String account = """
                {"name":"Gefiltert","host":"127.0.0.1","port":%d,"security":"NONE",
                 "username":"gefiltert@example.test","password":"imap-test-secret","folder":"INBOX","enabled":false,
                 "filter":{"subjectIncludes":[" scan ","Scan",""],"subjectExcludes":["werbung"],
                           "senderIncludes":["@KOPIERER.example"],"senderExcludes":["privat"]}}"""
                .formatted(greenMail.getImap().getPort());
        // zu viele oder zu lange Begriffe werden abgelehnt
        String tooMany = account.replace("\"werbung\"", String.join(",", java.util.Collections.nCopies(51, "\"x\"")));
        mvc.perform(post("/api/mail-accounts").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(tooMany))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/mail-accounts").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(account.replace("werbung", "w".repeat(201))))
                .andExpect(status().isBadRequest());

        String accountId = jsonValue(mvc.perform(post("/api/mail-accounts").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(account))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filter.subjectIncludes", hasSize(1)))
                .andExpect(jsonPath("$.filter.subjectIncludes[0]").value("scan"))
                .andExpect(jsonPath("$.filter.senderExcludes[0]").value("privat"))
                .andReturn(), "$.id");

        long before = documents.count();
        mailSync.sync(accountId);
        awaitProcessed((int) before + 1);
        assertThat(documents.findAll().stream().filter(doc -> accountId.equals(doc.getMailAccountId()))
                .map(Document::getOriginalFilename)).containsExactly("Scan_17.pdf");
        mvc.perform(get("/api/mail-accounts").session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$[?(@.id == '" + accountId + "')].lastSyncMessage")
                        .value("Neue E-Mails geprüft: 5. Vom Filter übersprungen: 4. PDFs übernommen: 1."));

        // die Anwendung merkt sich den Beginn des letzten erfolgreichen Abrufs
        Instant checkedUntil = mailAccounts.findById(accountId).orElseThrow().getCheckedUntil();
        assertThat(checkedUntil).isNotNull().isBefore(Instant.now());

        // ein anderer Filter gilt nur für E-Mails, die danach eingehen – geprüfte E-Mails bleiben geprüft
        String withoutSubjectFilter = account.replace("\"password\":\"imap-test-secret\",", "")
                .replace("\"subjectIncludes\":[\" scan \",\"Scan\",\"\"]", "\"subjectIncludes\":[]");
        mvc.perform(put("/api/mail-accounts/" + accountId).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(withoutSubjectFilter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter.subjectIncludes", hasSize(0)));
        mailSync.sync(accountId);
        assertThat(mailFiles(accountId)).containsExactly("Scan_17.pdf");
        mvc.perform(get("/api/mail-accounts").session(adminSession))
                .andExpect(jsonPath("$[?(@.id == '" + accountId + "')].lastSyncMessage").value("No new e-mails."));
        mailbox.deliver(mailWithPdf("Rechnung Juni", "Rechnung_Juni.pdf", pdf("Filtertest: Rechnung Juni"), copier));
        mailSync.sync(accountId);
        awaitProcessed((int) before + 2);
        assertThat(mailFiles(accountId)).containsExactlyInAnyOrder("Scan_17.pdf", "Rechnung_Juni.pdf");
        mvc.perform(get("/api/mail-accounts").session(adminSession))
                .andExpect(jsonPath("$[?(@.id == '" + accountId + "')].lastSyncMessage")
                        .value("New e-mails checked: 1. PDFs imported: 1."));

        // ein gescheiterter Abruf rückt die Zeitmarke nicht weiter
        Instant afterSuccess = mailAccounts.findById(accountId).orElseThrow().getCheckedUntil();
        assertThat(afterSuccess).isAfter(checkedUntil);
        jdbc.sql("UPDATE mail_accounts SET folder = ? WHERE id = ?").param("Weg").param(accountId).update();
        mailSync.sync(accountId);
        assertThat(mailAccounts.findById(accountId).orElseThrow().getLastSyncStatus())
                .isEqualTo(de.aidocuments.mail.MailAccount.SyncStatus.ERROR);
        assertThat(mailAccounts.findById(accountId).orElseThrow().getCheckedUntil()).isEqualTo(afterSuccess);
        jdbc.sql("UPDATE mail_accounts SET folder = ? WHERE id = ?").param("INBOX").param(accountId).update();

        // geht der UID-Stand verloren (z. B. neue UIDVALIDITY nach einem Serverumzug), bleiben ältere E-Mails trotzdem
        // geprüft. Die Zeitmarke wird vorgestellt, statt den Spielraum für abweichende Uhren abzuwarten.
        jdbc.sql("UPDATE mail_accounts SET uid_validity = 1, last_uid = 0, checked_until = ? WHERE id = ?")
                .param(Instant.now().plus(de.aidocuments.mail.MailSyncService.CLOCK_TOLERANCE).plusSeconds(60)
                        .toEpochMilli())
                .param(accountId).update();
        mailSync.sync(accountId);
        assertThat(mailFiles(accountId)).hasSize(2);
        mvc.perform(get("/api/mail-accounts").session(adminSession))
                .andExpect(jsonPath("$[?(@.id == '" + accountId + "')].lastSyncMessage").value("No new e-mails."));

        // ein anderer Zeitraum („Nur E-Mails ab“) ist dagegen ein bewusster Neuanfang: alles wird erneut geprüft
        mvc.perform(put("/api/mail-accounts/" + accountId).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withoutSubjectFilter.replace("\"enabled\":false", "\"enabled\":false,\"importSince\":\"2000-01-01\"")))
                .andExpect(status().isOk());
        assertThat(mailAccounts.findById(accountId).orElseThrow().getCheckedUntil()).isNull();
        mailSync.sync(accountId);
        awaitProcessed((int) before + 3);
        assertThat(mailFiles(accountId))
                .containsExactlyInAnyOrder("Scan_17.pdf", "Rechnung_Juni.pdf", "Rechnung.pdf");
        mvc.perform(get("/api/mail-accounts").session(adminSession))
                .andExpect(jsonPath("$[?(@.id == '" + accountId + "')].lastSyncMessage").value(
                        "New e-mails checked: 6. Skipped by filter: 3. PDFs imported: 1. Already present: 2."));
    }

    @Test
    @Order(16)
    void eachMailboxIsCheckedInItsOwnInterval() throws Exception {
        greenMail.setUser("intervall@example.test", "intervall@example.test", "imap-test-secret");
        String account = """
                {"name":"Intervall","host":"127.0.0.1","port":%d,"security":"NONE",
                 "username":"intervall@example.test","password":"imap-test-secret","folder":"INBOX","enabled":false,
                 "intervalMinutes":%s}""";
        int port = greenMail.getImap().getPort();
        for (String invalid : List.of("0", "1441", "-5")) {
            mvc.perform(post("/api/mail-accounts").with(csrf()).session(adminSession)
                            .contentType(MediaType.APPLICATION_JSON).content(account.formatted(port, invalid)))
                    .andExpect(status().isBadRequest());
        }
        String accountId = jsonValue(mvc.perform(post("/api/mail-accounts").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(account.formatted(port, "60")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervalMinutes").value(60))
                .andExpect(jsonPath("$.effectiveIntervalMinutes").value(60))
                // pausiert: kein nächster Abruf
                .andExpect(jsonPath("$.nextSyncAt").doesNotExist())
                .andReturn(), "$.id");

        // aktiviert und noch nie abgerufen: sofort fällig
        String enabled = account.replace("\"enabled\":false", "\"enabled\":true").formatted(port, "60");
        mvc.perform(put("/api/mail-accounts/" + accountId).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(enabled))
                .andExpect(status().isOk());
        mailSync.syncDue();
        Instant firstSync = mailAccounts.findById(accountId).orElseThrow().getLastSyncAt();
        assertThat(firstSync).isNotNull();

        // danach erst wieder nach 60 Minuten – gezählt ab dem Beginn des Abrufs
        List<String> listed = com.jayway.jsonpath.JsonPath.read(mvc.perform(get("/api/mail-accounts").session(adminSession))
                .andReturn().getResponse().getContentAsString(), "$[?(@.id == '" + accountId + "')].nextSyncAt");
        String next = listed.getFirst();
        assertThat(Instant.parse(next)).isBetween(firstSync.plus(Duration.ofMinutes(59)), firstSync.plus(Duration.ofMinutes(60)));
        mailSync.syncDue();
        assertThat(mailAccounts.findById(accountId).orElseThrow().getLastSyncAt()).isEqualTo(firstSync);

        // ohne eigenes Intervall gilt das allgemeine aus „Abruf & Eingangsordner“
        mvc.perform(put("/api/mail-accounts/" + accountId).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(enabled.replace("\"intervalMinutes\":60", "\"intervalMinutes\":null")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalMinutes").doesNotExist())
                .andExpect(jsonPath("$.effectiveIntervalMinutes").value(importSettings.current().intervalMinutes()));
        // ein geändertes Intervall wirkt sofort, ohne dass der Abrufzeitpunkt neu beginnt
        String shorter = jsonValue(mvc.perform(put("/api/mail-accounts/" + accountId).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(enabled.replace("\"intervalMinutes\":60", "\"intervalMinutes\":1")))
                .andExpect(status().isOk()).andReturn(), "$.nextSyncAt");
        assertThat(Duration.between(Instant.parse(next), Instant.parse(shorter))).isEqualTo(Duration.ofMinutes(-59));

        mvc.perform(delete("/api/mail-accounts/" + accountId).with(csrf()).session(adminSession))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(17)
    void documentsOfAnAreaAreVisibleOnlyToItsMembersAndAdmins() throws Exception {
        MockHttpSession anna = userWithSession("anna");
        MockHttpSession bert = userWithSession("bert");
        String annaId = jsonValue(mvc.perform(get("/api/auth/status").session(anna)).andReturn(), "$.user.id");

        // Bereiche verwalten nur Admins, und nur mit frischer Bestätigung
        String request = "{\"name\":\"Buchhaltung\",\"everyone\":false,\"memberIds\":[\"" + annaId + "\"]}";
        mvc.perform(post("/api/areas").with(csrf()).session(anna)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        adminSession.setAttribute("aidocuments.confirmedAt", Instant.now().minus(Duration.ofMinutes(11)));
        mvc.perform(post("/api/areas").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("confirmationRequired"));
        adminSession.setAttribute("aidocuments.confirmedAt", Instant.now());
        String areaId = jsonValue(mvc.perform(post("/api/areas").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberIds", hasSize(1)))
                .andReturn(), "$.id");
        mvc.perform(post("/api/areas").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(request.replace("Buchhaltung", "buchhaltung ")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/areas").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"everyone\":false,\"memberIds\":[\"gibt-es-nicht\"]}"))
                .andExpect(status().isBadRequest());

        // wer kein Mitglied ist, sieht den Bereich nicht und kann nichts hineinlegen
        mvc.perform(get("/api/areas").session(bert))
                .andExpect(jsonPath("$[?(@.id == '" + areaId + "')]", hasSize(0)))
                .andExpect(jsonPath("$[0].memberIds", hasSize(0)));
        MockMultipartFile secret = new MockMultipartFile("files", "Gehaltsliste.pdf", "application/pdf",
                pdf("Gehaltsliste Quartal", "Vertraulich Bereichstest Zebrafink"));
        mvc.perform(multipart("/api/documents").file(secret).param("area", areaId).with(csrf()).session(bert))
                .andExpect(status().isBadRequest());

        long before = documents.findAll().size();
        String docId = jsonValue(mvc.perform(multipart("/api/documents").file(secret).param("area", areaId)
                        .with(csrf()).session(anna))
                .andExpect(jsonPath("$[0].outcome").value("CREATED")).andReturn(), "$[0].documentId");
        awaitProcessed((int) before + 1);
        mvc.perform(put("/api/documents/" + docId).with(csrf()).session(anna)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Gehaltsliste\",\"category\":\"Personalakte\",\"correspondent\":\"Lohnbuero Zebrafink\",\"tags\":[\"gehalt\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaId").value(areaId));

        long bertTotal = jsonNumber(mvc.perform(get("/api/documents").session(bert)).andReturn(), "$.total");
        long adminTotal = jsonNumber(mvc.perform(get("/api/documents").session(adminSession)).andReturn(), "$.total");
        assertThat(adminTotal).isEqualTo(bertTotal + 1);
        mvc.perform(get("/api/documents").param("q", "Zebrafink").session(bert)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/documents").param("area", areaId).session(bert)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/documents/" + docId).session(bert)).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/" + docId + "/file").session(bert)).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/" + docId + "/text").session(bert)).andExpect(status().isNotFound());
        mvc.perform(put("/api/documents/" + docId).with(csrf()).session(bert)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"übernommen\",\"tags\":[]}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/facets").session(bert))
                .andExpect(jsonPath("$.categories[?(@.name == 'Personalakte')]", hasSize(0)))
                .andExpect(jsonPath("$.correspondents[?(@.name == 'Lohnbuero Zebrafink')]", hasSize(0)));
        mvc.perform(get("/api/history").param("size", "100").session(bert))
                .andExpect(jsonPath("$.items[?(@.documentId == '" + docId + "')]", hasSize(0)));
        assertThat(jsonNumber(mvc.perform(get("/api/system").session(bert)).andReturn(), "$.documents"))
                .isEqualTo(bertTotal);
        // dieselbe Datei gibt es nur einmal; wer sie nicht sehen darf, erfährt nicht, wo sie liegt
        mvc.perform(multipart("/api/documents").file(secret).with(csrf()).session(bert))
                .andExpect(jsonPath("$[0].outcome").value("DUPLICATE"))
                .andExpect(jsonPath("$[0].documentId").doesNotExist());

        // Mitglieder und Admins finden es
        mvc.perform(get("/api/documents").param("q", "Zebrafink").session(anna)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/documents").param("q", "Zebrafink").session(adminSession)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/documents").param("q", "Zebrafink").param("area", areaId).session(adminSession))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/areas").session(adminSession))
                .andExpect(jsonPath("$[?(@.id == '" + areaId + "')].documentCount").value(org.hamcrest.Matchers.contains(1)));

        // ein Bereich mit Dokumenten lässt sich nicht löschen, der allgemeine nie
        mvc.perform(delete("/api/areas/" + areaId).with(csrf()).session(adminSession)).andExpect(status().isConflict());
        mvc.perform(delete("/api/areas/general").with(csrf()).session(adminSession)).andExpect(status().isConflict());

        // verschieben: in einen nicht sichtbaren Bereich geht nicht; in den allgemeinen sieht es jeder
        mvc.perform(put("/api/documents/" + docId).with(csrf()).session(anna)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Gehaltsliste\",\"tags\":[],\"areaId\":\"gibt-es-nicht\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/documents/" + docId).with(csrf()).session(anna)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Gehaltsliste\",\"tags\":[],\"areaId\":\"general\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaId").value("general"));
        mvc.perform(get("/api/documents/" + docId).session(bert)).andExpect(status().isOk());
        mvc.perform(get("/api/documents").param("q", "Zebrafink").session(bert)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/history").param("size", "100").session(bert))
                .andExpect(jsonPath("$.items[?(@.documentId == '" + docId + "')]", org.hamcrest.Matchers.not(hasSize(0))));

        // ein Postfach kann nur einen vorhandenen Bereich als Ziel haben
        String mailbox = """
                {"name":"Ziel","host":"127.0.0.1","port":%d,"security":"NONE","username":"ziel@example.test",
                 "password":"imap-test-secret","folder":"INBOX","enabled":false,"areaId":"%s"}""";
        mvc.perform(post("/api/mail-accounts").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content(mailbox.formatted(greenMail.getImap().getPort(), "gibt-es-nicht")))
                .andExpect(status().isBadRequest());
        String mailboxId = jsonValue(mvc.perform(post("/api/mail-accounts").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(mailbox.formatted(greenMail.getImap().getPort(), areaId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.areaId").value(areaId)).andReturn(), "$.id");
        // Bereiche, die noch Ziel eines Postfachs sind, bleiben
        mvc.perform(delete("/api/areas/" + areaId).with(csrf()).session(adminSession)).andExpect(status().isConflict());
        mvc.perform(delete("/api/mail-accounts/" + mailboxId).with(csrf()).session(adminSession))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/areas/" + areaId).with(csrf()).session(adminSession)).andExpect(status().isNoContent());
        mvc.perform(get("/api/areas").session(anna)).andExpect(jsonPath("$[?(@.id == '" + areaId + "')]", hasSize(0)));
    }

    @Test
    @Order(18)
    void documentsUnderARetentionPeriodCannotBeDeletedByAnyone() throws Exception {
        MockHttpSession clara = userWithSession("clara");
        String rule = "{\"category\":\"Steuerbescheid\",\"years\":%d}";
        mvc.perform(get("/api/settings/retention").session(clara)).andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/retention").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(rule.formatted(0)))
                .andExpect(status().isBadRequest());
        String ruleId = jsonValue(mvc.perform(post("/api/settings/retention").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(rule.formatted(10)))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        mvc.perform(post("/api/settings/retention").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(rule.formatted(10).replace("Steuer", "steuer")))
                .andExpect(status().isConflict());

        String taxId = uploadAs(clara, "Bescheid_2024.pdf", "Einkommensteuerbescheid 2024 Fristtest");
        String details = "{\"title\":\"%s\",\"category\":\"%s\",\"documentDate\":\"%s\",\"tags\":[]}";
        // gezählt ab dem Ende des Jahres des Dokumentdatums; die Kategorie unabhängig von Groß-/Kleinschreibung
        mvc.perform(put("/api/documents/" + taxId).with(csrf()).session(clara).contentType(MediaType.APPLICATION_JSON)
                        .content(details.formatted("Steuerbescheid 2024", "steuerbescheid", "2024-05-03")))
                .andExpect(jsonPath("$.retainUntil").value("2034-12-31"))
                .andExpect(jsonPath("$.retentionLocked").value(true));
        mvc.perform(delete("/api/documents/" + taxId).with(csrf()).session(adminSession))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("2034")));
        // die Frist wird nie kürzer: weder über eine andere Kategorie noch über eine geänderte oder gelöschte Regel
        mvc.perform(put("/api/documents/" + taxId).with(csrf()).session(clara).contentType(MediaType.APPLICATION_JSON)
                        .content(details.formatted("Steuerbescheid 2024", "Sonstiges", "2000-01-01")))
                .andExpect(jsonPath("$.retainUntil").value("2034-12-31"));
        mvc.perform(put("/api/settings/retention/" + ruleId).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(rule.formatted(1)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/settings/retention/" + ruleId).with(csrf()).session(adminSession))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/documents/" + taxId).session(clara)).andExpect(jsonPath("$.retentionLocked").value(true));
        mvc.perform(delete("/api/documents/" + taxId).with(csrf()).session(adminSession)).andExpect(status().isConflict());

        // eine neue Regel gilt auch für vorhandene Dokumente – und für die im Papierkorb, die dann dort bleiben
        String binnedId = uploadAs(clara, "Vertrag_alt.pdf", "Altvertrag Papierkorbtest");
        mvc.perform(put("/api/documents/" + binnedId).with(csrf()).session(clara).contentType(MediaType.APPLICATION_JSON)
                .content(details.formatted("Altvertrag", "Vertrag", "2023-02-01"))).andExpect(status().isOk());
        mvc.perform(delete("/api/documents/" + binnedId).with(csrf()).session(adminSession)).andExpect(status().isNoContent());
        jdbc.sql("UPDATE deleted_documents SET deleted_at = ? WHERE id = ?")
                .param(Instant.now().minus(Duration.ofDays(40)).toEpochMilli()).param(binnedId).update();
        String oldId = uploadAs(clara, "Akte_2000.pdf", "Uralte Akte Ablauftest");
        mvc.perform(put("/api/documents/" + oldId).with(csrf()).session(clara).contentType(MediaType.APPLICATION_JSON)
                .content(details.formatted("Akte 2000", "Vertrag", "2000-01-01"))).andExpect(status().isOk());
        mvc.perform(post("/api/settings/retention").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"vertrag\",\"years\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentCount").value(1));
        documentService.purgeTrash();
        assertThat(documents.findInTrash(binnedId)).get()
                .satisfies(doc -> assertThat(doc.getRetainUntil()).isEqualTo(java.time.LocalDate.of(2033, 12, 31)));

        // abgelaufene Fristen lassen sich finden – und diese Dokumente wieder löschen
        mvc.perform(get("/api/documents/" + oldId).session(clara))
                .andExpect(jsonPath("$.retainUntil").value("2010-12-31"))
                .andExpect(jsonPath("$.retentionLocked").value(false));
        mvc.perform(get("/api/documents").param("retention", "expired").session(clara))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(oldId));
        mvc.perform(get("/api/system").session(clara)).andExpect(jsonPath("$.retentionExpired").value(1));
        mvc.perform(delete("/api/documents/" + oldId).with(csrf()).session(adminSession)).andExpect(status().isNoContent());
    }

    @Test
    @Order(19)
    void remindersComeFromSuggestionsOrByHandAndAreSentByMailOnce() throws Exception {
        MockHttpSession dora = userWithSession("dora");
        mvc.perform(put("/api/auth/profile").with(csrf()).session(dora).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"keine-adresse\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/auth/profile").with(csrf()).session(dora).header(HttpHeaders.ACCEPT_LANGUAGE, "de")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"dora@example.test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dora@example.test"));

        // Vorschläge stammen aus den wichtigen Angaben: nur Fristen, nur künftige
        String docId = uploadAs(dora, "Rechnung_Wartung.pdf", "Wartungsrechnung Fristtest");
        java.time.LocalDate payBy = java.time.LocalDate.now().plusDays(20);
        java.time.LocalDate cancelBy = java.time.LocalDate.now().plusDays(60);
        Document doc = documents.findById(docId).orElseThrow();
        doc.replaceKeyFacts(List.of(
                new de.aidocuments.document.KeyFact("Rechnungsdatum", "01.02.2026"),
                new de.aidocuments.document.KeyFact("Fällig am", "03.01.2020"),
                new de.aidocuments.document.KeyFact("Zahlbar bis", payBy.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))),
                new de.aidocuments.document.KeyFact("Kündigungsfrist", "bis zum " + cancelBy)));
        documents.save(doc);
        mvc.perform(get("/api/documents/" + docId + "/reminders").session(dora))
                .andExpect(jsonPath("$.reminders", hasSize(0)))
                .andExpect(jsonPath("$.suggestions", hasSize(2)))
                .andExpect(jsonPath("$.suggestions[0].date").value(payBy.toString()))
                .andExpect(jsonPath("$.suggestions[0].label").value("Zahlbar bis"));
        mvc.perform(get("/api/reminders").session(dora))
                .andExpect(jsonPath("$.suggestions[?(@.documentId == '" + docId + "')]", hasSize(2)));

        // übernehmen und verwerfen: beide Vorschläge verschwinden
        mvc.perform(post("/api/documents/" + docId + "/reminders").with(csrf()).session(dora)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dueDate\":\"" + payBy + "\",\"note\":\"Zahlbar bis\",\"fromSuggestion\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assigneeName").value("dora"))
                .andExpect(jsonPath("$.source").value("SUGGESTION"));
        mvc.perform(post("/api/documents/" + docId + "/reminders/dismiss").with(csrf()).session(dora)
                        .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content("{\"date\":\"" + cancelBy + "\",\"label\":\"Kündigungsfrist\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/documents/" + docId + "/reminders").session(dora))
                .andExpect(jsonPath("$.reminders", hasSize(1)))
                .andExpect(jsonPath("$.suggestions", hasSize(0)));

        // ein heute fälliger Termin von Hand: zählt im Menü und kommt einmal per E-Mail
        mvc.perform(get("/api/reminders/count").session(dora)).andExpect(jsonPath("$.due").value(0));
        String dueId = jsonValue(mvc.perform(post("/api/documents/" + docId + "/reminders").with(csrf()).session(dora)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"dueDate\":\"" + java.time.LocalDate.now() + "\",\"note\":\"Wartungsvertrag prüfen\"}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        mvc.perform(get("/api/reminders/count").session(dora)).andExpect(jsonPath("$.due").value(1));
        mvc.perform(get("/api/reminders").session(dora))
                .andExpect(jsonPath("$.due", hasSize(1)))
                .andExpect(jsonPath("$.due[0].documentTitle").value("Rechnung Wartung"))
                .andExpect(jsonPath("$.upcoming", hasSize(1)));

        // wer das Dokument nicht sieht, kann den Termin weder sehen noch übernehmen
        String privateArea = jsonValue(mvc.perform(post("/api/areas").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Vorstand\",\"everyone\":false,\"memberIds\":[]}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        int before = documents.findAll().size();
        String privateDoc = jsonValue(mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("files", "Protokoll.pdf", "application/pdf", pdf("Vorstandsprotokoll Termintest")))
                        .param("area", privateArea).with(csrf()).session(adminSession)).andReturn(), "$[0].documentId");
        awaitProcessed(before + 1);
        String doraId = jsonValue(mvc.perform(get("/api/auth/status").session(dora)).andReturn(), "$.user.id");
        mvc.perform(post("/api/documents/" + privateDoc + "/reminders").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dueDate\":\"" + java.time.LocalDate.now() + "\",\"assigneeId\":\"" + doraId + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents/" + privateDoc + "/reminders/assignees").session(adminSession))
                .andExpect(jsonPath("$[?(@.username == 'dora')]", hasSize(0)))
                .andExpect(jsonPath("$[?(@.username == 'chef')]", hasSize(1)));
        String adminReminder = jsonValue(mvc.perform(post("/api/documents/" + privateDoc + "/reminders").with(csrf())
                        .session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dueDate\":\"" + java.time.LocalDate.now() + "\"}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        mvc.perform(get("/api/reminders").param("mine", "false").session(dora))
                .andExpect(jsonPath("$.due[?(@.documentId == '" + privateDoc + "')]", hasSize(0)));
        mvc.perform(post("/api/reminders/" + adminReminder + "/done").with(csrf()).session(dora))
                .andExpect(status().isNotFound());

        // E-Mail-Versand einrichten (Admin, mit Bestätigung) und testen
        String smtp = """
                {"enabled":true,"host":"127.0.0.1","port":%d,"security":"NONE","username":"","password":null,
                 "from":"aidocs@example.test","appUrl":"https://docs.example.test/","digestHour":0}"""
                .formatted(greenMail.getSmtp().getPort());
        mvc.perform(put("/api/settings/mail-out").with(csrf()).session(dora)
                .contentType(MediaType.APPLICATION_JSON).content(smtp)).andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/mail-out").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(smtp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appUrl").value("https://docs.example.test"))
                .andExpect(jsonPath("$.passwordSet").value(false));
        mvc.perform(post("/api/settings/mail-out/test").with(csrf()).session(adminSession))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/auth/profile").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"chef@example.test\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/settings/mail-out/test").with(csrf()).session(adminSession))
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(mailsTo("chef@example.test")).hasSize(1);

        int sent = reminderNotifier.sendDue();
        assertThat(sent).isEqualTo(2);
        List<jakarta.mail.internet.MimeMessage> toDora = mailsTo("dora@example.test");
        assertThat(toDora).hasSize(1);
        assertThat(toDora.getFirst().getSubject()).isEqualTo("AIDocuments: Wiedervorlagen fällig (1)");
        String body = (String) toDora.getFirst().getContent();
        assertThat(body).contains("Hallo dora", "Rechnung Wartung", "Wartungsvertrag prüfen",
                "https://docs.example.test/documents/" + docId).doesNotContain("Vorstandsprotokoll");
        // jeder Termin nur einmal
        assertThat(reminderNotifier.sendDue()).isZero();

        // erledigen und wieder öffnen
        mvc.perform(post("/api/reminders/" + dueId + "/done").with(csrf()).session(dora))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.doneBy").value("dora"));
        mvc.perform(get("/api/reminders/count").session(dora)).andExpect(jsonPath("$.due").value(0));
        mvc.perform(post("/api/reminders/" + dueId + "/reopen").with(csrf()).session(dora))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(delete("/api/reminders/" + dueId).with(csrf()).session(dora)).andExpect(status().isNoContent());
        mvc.perform(get("/api/reminders/count").session(dora)).andExpect(jsonPath("$.due").value(0));
    }

    @Test
    @Order(20)
    void photosAndImagesBecomeOnePagePdfs() throws Exception {
        int before = documents.findAll().size();
        byte[] png = image("png", 900, 600);
        String id = jsonValue(mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("files", "Kassenbon.PNG", "image/png", png))
                        .with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("CREATED")).andReturn(), "$[0].documentId");
        awaitProcessed(before + 1);
        assertThat(documents.findById(id).orElseThrow().getOriginalFilename()).isEqualTo("Kassenbon.pdf");
        try (PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(file("/api/documents/" + id + "/file"))) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            org.apache.pdfbox.pdmodel.common.PDRectangle box = pdf.getPage(0).getMediaBox();
            assertThat(box.getWidth()).isGreaterThan(box.getHeight());
        }
        // dasselbe Foto noch einmal: an der Prüfsumme der Bilddatei erkannt
        mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("files", "Kassenbon Kopie.png", "image/png", png))
                        .with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("DUPLICATE"));

        // quer gespeichertes Handyfoto mit EXIF-Drehung: die Seite steht hochkant
        String rotated = jsonValue(mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("files", "IMG_0001.jpg", "image/jpeg",
                                withExifOrientation(image("jpg", 1200, 800), 6)))
                        .with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("CREATED")).andReturn(), "$[0].documentId");
        awaitProcessed(before + 2);
        try (PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(file("/api/documents/" + rotated + "/file"))) {
            org.apache.pdfbox.pdmodel.common.PDRectangle box = pdf.getPage(0).getMediaBox();
            assertThat(box.getHeight()).isGreaterThan(box.getWidth());
        }

        mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("files", "notiz.txt", "text/plain", "nur Text".getBytes()))
                        .with(csrf()).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$[0].outcome").value("REJECTED"))
                .andExpect(jsonPath("$[0].message").value("Die Datei ist weder ein PDF noch ein Foto (JPG, PNG, HEIC) noch eine E-Rechnung (XML)."));

        // E-Mail: das angehängte Foto wird übernommen, das im HTML eingebettete Logo nicht
        GreenMailUser mailbox = greenMail.setUser("fotos@example.test", "fotos@example.test", "imap-test-secret");
        mailbox.deliver(mailWithPhotoAndLogo(image("jpg", 1600, 1200), image("png", 120, 40)));
        String accountId = jsonValue(mvc.perform(post("/api/mail-accounts").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Fotos","host":"127.0.0.1","port":%d,"security":"NONE",
                                 "username":"fotos@example.test","password":"imap-test-secret","folder":"INBOX",
                                 "enabled":false}""".formatted(greenMail.getImap().getPort())))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        mailSync.sync(accountId);
        awaitProcessed(before + 3);
        assertThat(mailFiles(accountId)).containsExactly("Rechnung Handwerker.pdf");
        mvc.perform(get("/api/mail-accounts").session(adminSession))
                .andExpect(jsonPath("$[?(@.id == '" + accountId + "')].lastSyncStatus").value("OK"));
        mvc.perform(delete("/api/mail-accounts/" + accountId).with(csrf()).session(adminSession))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(21)
    void theVisibleDocumentsCanBeExportedAsZipWithTheirMetadata() throws Exception {
        MockHttpSession erna = userWithSession("export-erna");
        uploadAs(erna, "Exportprobe Eins.pdf", "Exportprobe eins: Wasserrechnung");
        uploadAs(erna, "Exportprobe Zwei.pdf", "Exportprobe zwei: Hausratversicherung; Beitrag \"jährlich\"");
        String hidden = jsonValue(mvc.perform(post("/api/areas").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Export geheim\",\"everyone\":false,\"memberIds\":[]}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        int before = documents.findAll().size();
        mvc.perform(multipart("/api/documents").param("area", hidden)
                        .file(new MockMultipartFile("files", "Exportprobe Geheim.pdf", "application/pdf",
                                pdf("Exportprobe geheim: Personalakte")))
                        .with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("CREATED"));
        awaitProcessed(before + 1);

        // Schritt 1: die Auswahl mit denselben Filtern wie die Liste – fremde Bereiche fehlen
        MvcResult ticket = mvc.perform(post("/api/documents/export").param("q", "Exportprobe").param("sort", "newest")
                        .with(csrf()).session(erna))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2)).andReturn();
        String token = jsonValue(ticket, "$.token");
        mvc.perform(post("/api/documents/export").param("q", "Exportprobe").with(csrf()).session(adminSession))
                .andExpect(jsonPath("$.count").value(3));
        mvc.perform(post("/api/documents/export").param("q", "gibtesnirgendwo").with(csrf()).session(erna))
                .andExpect(status().isBadRequest());

        // der Schlüssel gilt nur in der eigenen Sitzung …
        mvc.perform(get("/api/documents/export/" + token).session(userWithSession("export-fritz"))).andExpect(status().isNotFound());
        MvcResult download = mvc.perform(get("/api/documents/export/" + token).session(erna))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("AIDocuments-Export-")))
                .andReturn();
        // … und nur einmal
        mvc.perform(get("/api/documents/export/" + token).session(erna)).andExpect(status().isNotFound());

        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(download.getResponse().getContentAsByteArray()))) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        List<String> pdfs = entries.keySet().stream().filter(n -> n.startsWith("documents/")).toList();
        assertThat(pdfs).hasSize(2).allMatch(n -> n.endsWith(".pdf") && n.contains("Exportprobe"));
        assertThat(new String(entries.get(pdfs.getFirst()), 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                .isEqualTo("%PDF-");
        String csv = new String(entries.get("documents.csv"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFFfile;title;").contains("Exportprobe Eins").doesNotContain("Geheim");
        List<?> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(entries.get("documents.json"), List.class);
        assertThat(json).hasSize(2);

        // wer die Zwei-Faktor-Anmeldung nutzt, bestätigt den Export mit einem frischen Code
        adminSession.setAttribute("aidocuments.confirmedAt", Instant.now().minus(Duration.ofHours(1)));
        mvc.perform(post("/api/documents/export").param("q", "Exportprobe").with(csrf()).session(adminSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("confirmationRequired"));
        adminSession.setAttribute("aidocuments.confirmedAt", Instant.now());
    }

    @Test
    @Order(22)
    void membersOfAnAreaMayReadEditOrAlsoDelete() throws Exception {
        MockHttpSession lea = userWithSession("recht-lea");   // nur lesen
        MockHttpSession max = userWithSession("recht-max");   // bearbeiten
        MockHttpSession ida = userWithSession("recht-ida");   // auch löschen
        MockHttpSession ole = userWithSession("recht-ole");   // kein Mitglied
        String members = "[{\"userId\":\"%s\",\"access\":\"READ\"},{\"userId\":\"%s\",\"access\":\"EDIT\"},"
                + "{\"userId\":\"%s\",\"access\":\"DELETE\"}]";
        members = members.formatted(userId(lea), userId(max), userId(ida));
        adminSession.setAttribute("aidocuments.confirmedAt", Instant.now());
        String areaId = jsonValue(mvc.perform(post("/api/areas").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rechteprobe\",\"everyone\":false,\"members\":" + members + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members[?(@.access == 'READ')]", hasSize(1)))
                .andExpect(jsonPath("$.members[?(@.access == 'DELETE')]", hasSize(1)))
                .andReturn(), "$.id");
        int before = documents.findAll().size();
        String docId = jsonValue(mvc.perform(multipart("/api/documents").param("area", areaId)
                        .file(new MockMultipartFile("files", "Rechteprobe.pdf", "application/pdf",
                                pdf("Rechteprobe: Wartungsvertrag Heizung")))
                        .with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("CREATED")).andReturn(), "$[0].documentId");
        awaitProcessed(before + 1);
        String change = "{\"title\":\"Wartungsvertrag\",\"tags\":[]}";

        // lesen: ansehen ja, ändern, ablegen, teilen und löschen nicht
        mvc.perform(get("/api/areas").session(lea))
                .andExpect(jsonPath("$[?(@.id == '" + areaId + "')].myAccess").value(org.hamcrest.Matchers.contains("READ")));
        mvc.perform(get("/api/documents/" + docId).session(lea))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access").value("READ"));
        mvc.perform(get("/api/documents/" + docId + "/file").session(lea)).andExpect(status().isOk());
        mvc.perform(put("/api/documents/" + docId).with(csrf()).session(lea).header("Accept-Language", "de")
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("In diesem Bereich darfst du Dokumente nur ansehen."));
        mvc.perform(multipart("/api/documents").param("area", areaId)
                        .file(new MockMultipartFile("files", "Leseprobe.pdf", "application/pdf", pdf("Leseprobe Rechte")))
                        .with(csrf()).session(lea))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/documents/" + docId + "/shares").session(lea)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/documents/" + docId).with(csrf()).session(lea)).andExpect(status().isForbidden());

        // bearbeiten: ändern und teilen ja, löschen nicht
        mvc.perform(put("/api/documents/" + docId).with(csrf()).session(max)
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access").value("EDIT"));
        mvc.perform(get("/api/documents/" + docId + "/shares").session(max)).andExpect(status().isOk());
        mvc.perform(delete("/api/documents/" + docId).with(csrf()).session(max)).andExpect(status().isForbidden());

        // löschen – und zurückholen nur, wer löschen darf
        mvc.perform(delete("/api/documents/" + docId).with(csrf()).session(ida)).andExpect(status().isNoContent());
        String deletion = historyEntryId(docId, "DELETED");
        mvc.perform(get("/api/history").param("size", "200").session(max))
                .andExpect(jsonPath("$.items[?(@.id == '" + deletion + "')].undoable")
                        .value(org.hamcrest.Matchers.contains(false)));
        mvc.perform(post("/api/history/" + deletion + "/undo").with(csrf()).session(max))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/history/" + deletion + "/undo").with(csrf()).session(ida)).andExpect(status().isOk());

        // für alle freigegeben, aber nur zum Lesen: Mitglieder behalten ihr höheres Recht
        mvc.perform(put("/api/areas/" + areaId).with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rechteprobe\",\"everyone\":true,\"everyoneAccess\":\"READ\",\"members\":"
                                + members + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.everyoneAccess").value("READ"));
        mvc.perform(get("/api/documents/" + docId).session(ole))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access").value("READ"));
        mvc.perform(put("/api/documents/" + docId).with(csrf()).session(ole)
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/documents/" + docId).with(csrf()).session(max)
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isOk());
        // in einen nur lesbaren Bereich verschiebt niemand
        mvc.perform(put("/api/documents/" + docId).with(csrf()).session(ole)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"tags\":[],\"areaId\":\"general\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/documents").param("q", "Rechteprobe").session(ole))
                .andExpect(jsonPath("$.items[0].access").value("READ"));
    }

    @Test
    @Order(23)
    void pagesCanBeRearrangedSplitAndAppended() throws Exception {
        MockHttpSession sam = userWithSession("seiten-sam"); // im allgemeinen Bereich: bearbeiten, nicht löschen
        String id = uploadPdf(sam, "Seitenprobe.pdf", pages("Seitenprobe Alpha", "Seitenprobe Beta", "Seitenprobe Gamma"));

        mvc.perform(get("/api/documents/" + id + "/pages/2/image").session(sam))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.IMAGE_JPEG));
        mvc.perform(get("/api/documents/" + id + "/pages/9/image").session(sam)).andExpect(status().isNotFound());

        // umsortieren, drehen, entfernen – als neue Fassung
        mvc.perform(post("/api/documents/" + id + "/pages").with(csrf()).session(sam)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pages\":[{\"page\":1,\"rotate\":0},{\"page\":1,\"rotate\":0}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/documents/" + id + "/pages").with(csrf()).session(sam)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pages\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/documents/" + id + "/pages").with(csrf()).session(sam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pages\":[{\"page\":3,\"rotate\":0},{\"page\":1,\"rotate\":90}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pageCount").value(2));
        String text = jsonValue(mvc.perform(get("/api/documents/" + id + "/text").session(sam)).andReturn(), "$.content");
        assertThat(text).doesNotContain("Beta");
        assertThat(text.indexOf("Gamma")).isLessThan(text.indexOf("Alpha"));
        try (PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(mvc.perform(get("/api/documents/" + id + "/file").session(sam))
                .andReturn().getResponse().getContentAsByteArray())) {
            assertThat(pdf.getPage(1).getRotation()).isEqualTo(90);
        }
        mvc.perform(get("/api/documents/" + id + "/versions").session(sam))
                .andExpect(jsonPath("$[?(@.kind == 'PAGES')]", hasSize(1)));

        // trennen: ab Seite 2 ein eigenes Dokument im selben Bereich
        String letters = uploadPdf(sam, "Trennprobe.pdf", pages("Trennprobe Eins", "Trennprobe Zwei", "Trennprobe Drei"));
        mvc.perform(post("/api/documents/" + letters + "/split").with(csrf()).session(sam)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fromPage\":1}"))
                .andExpect(status().isBadRequest());
        int before = documents.findAll().size();
        String created = jsonValue(mvc.perform(post("/api/documents/" + letters + "/split").with(csrf()).session(sam)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fromPage\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.original.pageCount").value(1)).andReturn(), "$.createdId");
        awaitProcessed(before + 1);
        mvc.perform(get("/api/documents/" + created).session(sam))
                .andExpect(jsonPath("$.pageCount").value(2)).andExpect(jsonPath("$.areaId").value("general"));
        assertThat(jsonValue(mvc.perform(get("/api/documents/" + created + "/text").session(sam)).andReturn(), "$.content"))
                .contains("Zwei", "Drei").doesNotContain("Eins");

        // anhängen: der angehängte Teil kommt in den Papierkorb – dafür braucht es das Recht zum Löschen
        String merge = "{\"documentIds\":[\"" + created + "\"]}";
        mvc.perform(post("/api/documents/" + letters + "/merge").with(csrf()).session(sam)
                        .contentType(MediaType.APPLICATION_JSON).content(merge))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/documents/" + letters + "/merge").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"documentIds\":[\"" + letters + "\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/documents/" + letters + "/merge").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(merge))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pageCount").value(3));
        mvc.perform(get("/api/documents/" + created).session(sam)).andExpect(status().isNotFound());
        assertThat(jsonValue(mvc.perform(get("/api/documents/" + letters + "/text").session(sam)).andReturn(), "$.content"))
                .contains("Eins", "Zwei", "Drei");

        // mehrere Fotos als ein Dokument mit je einer Seite
        before = documents.findAll().size();
        MockMultipartFile first = new MockMultipartFile("files", "Brief Seite 1.jpg", "image/jpeg", image("jpg", 1200, 1700));
        MockMultipartFile second = new MockMultipartFile("files", "Brief Seite 2.png", "image/png", image("png", 1200, 1650));
        String photos = jsonValue(mvc.perform(multipart("/api/documents").file(first).file(second).param("combine", "true")
                        .with(csrf()).session(sam))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].outcome").value("CREATED")).andReturn(), "$[0].documentId");
        awaitProcessed(before + 1);
        mvc.perform(get("/api/documents/" + photos).session(sam))
                .andExpect(jsonPath("$.pageCount").value(2))
                .andExpect(jsonPath("$.originalFilename").value("Brief Seite 1.pdf"));
        mvc.perform(multipart("/api/documents").file(first).file(second).param("combine", "true").with(csrf()).session(sam))
                .andExpect(jsonPath("$[0].outcome").value("DUPLICATE"));
        mvc.perform(multipart("/api/documents").file(first)
                        .file(new MockMultipartFile("files", "Anlage.pdf", "application/pdf", pdf("Anlage zum Foto")))
                        .param("combine", "true").with(csrf()).session(sam))
                .andExpect(jsonPath("$[0].outcome").value("REJECTED"));
    }

    @Test
    @Order(24)
    void recurringRemindersAndContractsKeepTheirDeadlines() throws Exception {
        MockHttpSession vera = userWithSession("vertrag-vera");
        String id = uploadPdf(vera, "Stromvertrag.pdf", pdf("Stromliefervertrag Vertragsprobe", "Laufzeit 12 Monate"));
        LocalDate today = LocalDate.now();

        // wiederkehrend: erledigt -> der nächste Termin im selben Rhythmus, nur einmal
        String reminder = jsonValue(mvc.perform(post("/api/documents/" + id + "/reminders").with(csrf()).session(vera)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dueDate\":\"" + today.minusDays(1) + "\",\"note\":\"Zählerstand melden\",\"repeatMonths\":3}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.repeatMonths").value(3)).andReturn(), "$.id");
        mvc.perform(post("/api/reminders/" + reminder + "/done").with(csrf()).session(vera)).andExpect(status().isOk());
        mvc.perform(post("/api/reminders/" + reminder + "/reopen").with(csrf()).session(vera)).andExpect(status().isOk());
        mvc.perform(post("/api/reminders/" + reminder + "/done").with(csrf()).session(vera)).andExpect(status().isOk());
        String next = today.minusDays(1).plusMonths(3).toString();
        mvc.perform(get("/api/documents/" + id + "/reminders").session(vera))
                .andExpect(jsonPath("$.reminders[?(@.status == 'OPEN' && @.dueDate == '" + next + "')]", hasSize(1)))
                .andExpect(jsonPath("$.reminders[?(@.status == 'OPEN')].note").value(org.hamcrest.Matchers.contains("Zählerstand melden")));

        // Vertrag: Erinnerung 14 Tage vor Ende der Kündigungsfrist
        LocalDate end = today.plusMonths(3);
        LocalDate deadline = end.minusMonths(1);
        String contract = "{\"endDate\":\"%s\",\"noticeMonths\":1,\"renewalMonths\":12,\"amount\":\"49 EUR im Monat\",\"cancelled\":%s}";
        mvc.perform(put("/api/documents/" + id + "/contract").with(csrf()).session(vera).header("Accept-Language", "de")
                        .contentType(MediaType.APPLICATION_JSON).content(contract.formatted(end, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noticeDeadline").value(deadline.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reminderDays").value(14));
        mvc.perform(get("/api/documents/" + id + "/reminders").session(vera).header("Accept-Language", "de"))
                .andExpect(jsonPath("$.reminders[?(@.source == 'CONTRACT')].dueDate")
                        .value(org.hamcrest.Matchers.contains(deadline.minusDays(14).toString())))
                .andExpect(jsonPath("$.reminders[?(@.source == 'CONTRACT')].note")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.startsWith("Vertrag kündigen bis spätestens "
                                + deadline.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)
                                        .withLocale(java.util.Locale.GERMAN)) + " "))));
        mvc.perform(get("/api/contracts").session(vera))
                .andExpect(jsonPath("$[?(@.documentId == '" + id + "')].amount").value(org.hamcrest.Matchers.contains("49 EUR im Monat")));
        mvc.perform(put("/api/documents/" + id + "/contract").with(csrf()).session(vera)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"" + end + "\",\"endDate\":\"" + today + "\",\"cancelled\":false}"))
                .andExpect(status().isBadRequest());

        // nicht gekündigt und abgelaufen: verlängert sich um 12 Monate, die Erinnerung zieht mit
        jdbc.sql("UPDATE contracts SET end_date = ? WHERE document_id = ?").param(today.minusDays(3).toString()).param(id).update();
        contractService.renewExpired();
        LocalDate renewed = today.minusDays(3).plusMonths(12);
        mvc.perform(get("/api/documents/" + id + "/contract").session(vera))
                .andExpect(jsonPath("$.endDate").value(renewed.toString()));
        mvc.perform(get("/api/documents/" + id + "/reminders").session(vera))
                .andExpect(jsonPath("$.reminders[?(@.source == 'CONTRACT' && @.status == 'OPEN')].dueDate")
                        .value(org.hamcrest.Matchers.contains(renewed.minusMonths(1).minusDays(14).toString())));

        // gekündigt: keine offene Erinnerung mehr; Angaben entfernen
        mvc.perform(put("/api/documents/" + id + "/contract").with(csrf()).session(vera)
                        .contentType(MediaType.APPLICATION_JSON).content(contract.formatted(renewed, true)))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/documents/" + id + "/reminders").session(vera))
                .andExpect(jsonPath("$.reminders[?(@.source == 'CONTRACT' && @.status == 'OPEN')]", hasSize(0)));
        mvc.perform(delete("/api/documents/" + id + "/contract").with(csrf()).session(vera)).andExpect(status().isNoContent());
        mvc.perform(get("/api/documents/" + id + "/contract").session(vera)).andExpect(status().isNoContent());
    }

    private String uploadPdf(MockHttpSession session, String name, byte[] content) throws Exception {
        int before = documents.findAll().size();
        String id = jsonValue(mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("files", name, "application/pdf", content)).with(csrf()).session(session))
                .andExpect(jsonPath("$[0].outcome").value("CREATED")).andReturn(), "$[0].documentId");
        awaitProcessed(before + 1);
        return id;
    }

    /** Ein PDF mit je einer Zeile Text auf jeder Seite. */
    private static byte[] pages(String... texts) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : texts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(60, 720);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private String userId(MockHttpSession session) throws Exception {
        return jsonValue(mvc.perform(get("/api/auth/status").session(session)).andReturn(), "$.user.id");
    }

    /** Ein Testbild mit etwas Text (für die Texterkennung) im angegebenen Format. */
    private static byte[] image(String format, int width, int height) throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(java.awt.Color.BLACK);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, Math.max(10, height / 12)));
        g.drawString("Fototest " + width + "x" + height, width / 10, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(javax.imageio.ImageIO.write(img, format, out)).isTrue();
        return out.toByteArray();
    }

    /** Fügt hinter dem JPEG-Anfang ein APP1-Segment mit EXIF-Ausrichtung ein (wie es Smartphones schreiben). */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        java.nio.ByteBuffer app1 = java.nio.ByteBuffer.allocate(36);
        app1.putShort((short) 0xFFE1).putShort((short) 34)
                .put(new byte[] {'E', 'x', 'i', 'f', 0, 0})
                .put(new byte[] {'M', 'M'}).putShort((short) 42).putInt(8)
                .putShort((short) 1)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0)
                .putInt(0);
        byte[] result = new byte[jpeg.length + app1.capacity()];
        System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(app1.array(), 0, result, 2, app1.capacity());
        System.arraycopy(jpeg, 2, result, 2 + app1.capacity(), jpeg.length - 2);
        return result;
    }

    private static MimeMessage mailWithPhotoAndLogo(byte[] photo, byte[] logo) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("handwerker@example.test"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("fotos@example.test"));
        message.setSubject("Rechnung");
        MimeBodyPart html = new MimeBodyPart();
        html.setText("<p>Anbei die Rechnung.</p><img src=\"cid:logo\">", "UTF-8", "html");
        MimeBodyPart inlineLogo = new MimeBodyPart();
        inlineLogo.setDataHandler(new DataHandler(new ByteArrayDataSource(logo, "image/png")));
        inlineLogo.setContentID("<logo>");
        inlineLogo.setDisposition(jakarta.mail.Part.INLINE);
        inlineLogo.setFileName("logo.png");
        MimeMultipart related = new MimeMultipart("related", html, inlineLogo);
        MimeBodyPart body = new MimeBodyPart();
        body.setContent(related);
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setDataHandler(new DataHandler(new ByteArrayDataSource(photo, "image/jpeg")));
        attachment.setFileName("Rechnung Handwerker.jpg");
        attachment.setDisposition(jakarta.mail.Part.ATTACHMENT);
        message.setContent(new MimeMultipart(body, attachment));
        message.saveChanges();
        return message;
    }

    @Test
    @Order(25)
    void adminsAndSubscribersAreNotifiedByEmail() throws Exception {
        // der E-Mail-Versand ist seit Order 19 eingerichtet, der Admin hat die Adresse chef@example.test
        jdbc.sql("DELETE FROM retention_notices").update();
        jdbc.sql("UPDATE users SET digest_until = NULL").update();
        mvc.perform(put("/api/auth/profile").with(csrf()).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"chef@example.test\",\"notifyNewDocuments\":true}"))
                .andExpect(jsonPath("$.notifyNewDocuments").value(true))
                .andExpect(jsonPath("$.notifyAdmin").value(true));

        // abgelaufene Aufbewahrungsfrist: einmal melden, nichts löschen; eine geänderte Frist erneut
        MockHttpSession dina = userWithSession("digest-dina");
        String own = uploadPdf(dina, "Dinas Beleg.pdf", pdf("Beleg von Dina", "Betrag 12 Euro"));
        jdbc.sql("UPDATE documents SET retain_until = '2001-12-31' WHERE id = ?").param(own).update();
        int before = mailsTo("chef@example.test").size();
        assertThat(notifications.sendRetentionNotices()).isEqualTo(1);
        List<jakarta.mail.internet.MimeMessage> toChef = mailsTo("chef@example.test");
        assertThat(toChef).hasSize(before + 1);
        assertThat(toChef.getLast().getSubject()).startsWith("AIDocuments: Aufbewahrungsfrist abgelaufen");
        assertThat((String) toChef.getLast().getContent()).contains("31. Dezember 2001",
                "https://docs.example.test/?retention=expired");
        assertThat(notifications.sendRetentionNotices()).isZero();
        jdbc.sql("UPDATE documents SET retain_until = '2002-12-31' WHERE id = ?").param(own).update();
        assertThat(notifications.sendRetentionNotices()).isEqualTo(1);
        assertThat(documents.findById(own)).isPresent();
        jdbc.sql("UPDATE documents SET retain_until = NULL WHERE id = ?").param(own).update();

        // tägliche Übersicht: nur Dokumente aus den eigenen Bereichen, einmal am Tag
        String hidden = documents.findAll().stream().filter(d -> "Protokoll.pdf".equals(d.getOriginalFilename()))
                .findFirst().orElseThrow().getId();
        jdbc.sql("UPDATE documents SET created_at = created_at - 259200000 WHERE id NOT IN (?, ?)")
                .param(own).param(hidden).update();
        try {
            mvc.perform(put("/api/auth/profile").with(csrf()).session(dina).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"dina@example.test\",\"notifyNewDocuments\":true}"))
                    .andExpect(jsonPath("$.notifyNewDocuments").value(true));
            Instant now = Instant.now();
            assertThat(notifications.sendDigests(now)).isEqualTo(2);
            String toDina = (String) mailsTo("dina@example.test").getLast().getContent();
            assertThat(toDina).contains("/documents/" + own).doesNotContain(hidden);
            String digest = (String) mailsTo("chef@example.test").getLast().getContent();
            assertThat(digest).contains("/documents/" + own, "/documents/" + hidden);
            assertThat(notifications.sendDigests(now.plusSeconds(60))).isZero();
        } finally {
            jdbc.sql("UPDATE documents SET created_at = created_at + 259200000 WHERE id NOT IN (?, ?)")
                    .param(own).param(hidden).update();
            jdbc.sql("UPDATE users SET notify_new_documents = ?").param(false).update();
        }

        // Postfach: erst nach mehreren Fehlschlägen in Folge, einmal je Fehlerserie
        String body = """
                {"name":"Kaputt","host":"127.0.0.1","port":1,"security":"NONE",
                 "username":"scans@example.test","password":"imap-test-secret","folder":"INBOX","enabled":false}""";
        String account = jsonValue(mvc.perform(post("/api/mail-accounts").with(csrf()).session(adminSession)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn(), "$.id");
        before = mailsTo("chef@example.test").size();
        for (int i = 0; i < de.aidocuments.mail.MailSyncService.FAILURES_BEFORE_NOTICE + 1; i++) {
            mailSync.sync(account);
        }
        // erst jetzt aktiv – sonst stieße schon das Anlegen einen Abruf an
        jdbc.sql("UPDATE mail_accounts SET enabled = ? WHERE id = ?").param(true).param(account).update();
        toChef = mailsTo("chef@example.test");
        assertThat(toChef).hasSize(before + 1);
        assertThat(toChef.getLast().getSubject()).isEqualTo("AIDocuments: Postfach „Kaputt“ lässt sich nicht abrufen");
        assertThat((String) toChef.getLast().getContent()).contains("https://docs.example.test/settings/mailboxes");
        mvc.perform(get("/api/system").session(adminSession)).andExpect(jsonPath("$.failingMailboxes[0]").value("Kaputt"));
        mvc.perform(get("/api/system").session(dina)).andExpect(jsonPath("$.failingMailboxes", hasSize(0)));
        jdbc.sql("UPDATE mail_accounts SET port = ? WHERE id = ?").param(greenMail.getImap().getPort()).param(account)
                .update();
        mailSync.sync(account);
        mvc.perform(get("/api/system").session(adminSession)).andExpect(jsonPath("$.failingMailboxes", hasSize(0)));
        mvc.perform(delete("/api/mail-accounts/" + account).with(csrf()).session(adminSession))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(26)
    void backupAndImportKeepEverything() throws Exception {
        MockHttpSession gustav = userWithSession("backup-gustav");
        mvc.perform(get("/api/settings/backup").session(gustav)).andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/backup").with(csrf()).session(gustav)).andExpect(status().isForbidden());

        boolean sqlite = !"mariadb".equals(System.getProperty("aidocs.test.database"));
        mvc.perform(get("/api/settings/backup").session(adminSession))
                .andExpect(jsonPath("$.available").value(sqlite));
        if (sqlite) {
            String token = jsonValue(mvc.perform(post("/api/settings/backup").with(csrf()).session(adminSession))
                    .andExpect(status().isOk()).andReturn(), "$.token");
            mvc.perform(get("/api/settings/backup/" + token).session(gustav)).andExpect(status().isForbidden());
            MvcResult download = mvc.perform(get("/api/settings/backup/" + token).session(adminSession))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("AIDocuments-Sicherung-")))
                    .andReturn();
            mvc.perform(get("/api/settings/backup/" + token).session(adminSession)).andExpect(status().isNotFound());
            java.util.Map<String, byte[]> entries = unzip(download.getResponse().getContentAsByteArray());
            assertThat(entries).containsKeys("README.txt", "aidocuments.db", "secret.key");
            assertThat(new String(entries.get("aidocuments.db"), 0, 15, java.nio.charset.StandardCharsets.US_ASCII))
                    .isEqualTo("SQLite format 3");
            assertThat(entries.keySet().stream().filter(n -> n.startsWith("files/")).count())
                    .isGreaterThanOrEqualTo(documents.findAll().size());
            mvc.perform(get("/api/settings/backup").session(adminSession))
                    .andExpect(jsonPath("$.lastBackupAt").isNotEmpty())
                    .andExpect(jsonPath("$.passwordConfigured").value(true))
                    .andExpect(jsonPath("$.password").doesNotExist());

            // verschlüsselt mit eigenem Passwort: zu kurz oder zu lang wird abgelehnt
            for (String bad : List.of("elf Zeichen", "x".repeat(129))) {
                mvc.perform(post("/api/settings/backup").with(csrf()).session(adminSession)
                                .header(HttpHeaders.ACCEPT_LANGUAGE, "de").contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(java.util.Map.of("password", bad))))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.detail").value(containsString("128")));
            }
            // ein leerer Rumpf bleibt unverschlüsselt wie bisher
            String plain = jsonValue(mvc.perform(post("/api/settings/backup").with(csrf()).session(adminSession)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()).andReturn(), "$.token");
            assertThat(unzip(mvc.perform(get("/api/settings/backup/" + plain).session(adminSession))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.not(
                            containsString("verschluesselt")))).andReturn().getResponse().getContentAsByteArray()))
                    .containsKey("aidocuments.db");
            String own = "eigenes Passwört 2026!";
            byte[] encrypted = downloadBackup(JSON.writeValueAsString(java.util.Map.of("password", own)));
            java.util.Map<String, byte[]> decrypted = unzip(encrypted, own);
            assertThat(decrypted.keySet()).isEqualTo(entries.keySet());
            assertThat(decrypted.get("secret.key")).isEqualTo(entries.get("secret.key"));
            // ohne oder mit falschem Passwort ist nichts lesbar – auch die Namen der Einträge allein verraten nichts
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> unzip(encrypted))
                    .isInstanceOf(java.util.zip.ZipException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> unzip(encrypted, "falsches Passwort"))
                    .isInstanceOf(IOException.class);
            assertThat(new String(encrypted, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("SQLite format 3");

            // mit dem Passwort aus AIDOCS_BACKUP_PASSWORD – das selbst nie in einer Antwort steht
            byte[] configured = downloadBackup("{\"useConfigured\":true}");
            assertThat(unzip(configured, BACKUP_PASSWORD)).containsKeys("README.txt", "aidocuments.db", "secret.key");
            assertThat(new String(configured, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(BACKUP_PASSWORD);
        } else {
            mvc.perform(post("/api/settings/backup").with(csrf()).session(adminSession))
                    .andExpect(status().isConflict());
        }

        // Import eines Exports: die Angaben bleiben, die KI liest nicht nochmals; Fehlendes wird gemeldet
        String json = """
                [{"file":"documents/2024-05-01 Mietvertrag.pdf","title":"Importierter Mietvertrag","category":"Vertrag",
                  "correspondent":"Hausverwaltung Importprobe","tags":["Miete","import"],"documentDate":"2024-05-01",
                  "summary":"Mietvertrag aus einer anderen Installation","keyFacts":[{"label":"Miete","value":"800 €"}],
                  "area":"gibt es hier nicht","originalFilename":"Mietvertrag.pdf","source":"MAIL",
                  "mailSubject":"Ihr Vertrag","textFile":"texts/2024-05-01 Mietvertrag.txt","ocr":true},
                 {"file":"documents/fehlt.pdf","title":"Fehlt"}]""";
        byte[] archive = zip(java.util.Map.of(
                "documents.json", json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "documents/2024-05-01 Mietvertrag.pdf", pdf("Seite eins des Mietvertrags"),
                "texts/2024-05-01 Mietvertrag.txt", "Erkannter Volltext mit dem Zauberwort Importfeder"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        mvc.perform(post("/api/settings/backup/import").with(csrf()).session(gustav)
                .contentType("application/zip").content(archive)).andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/backup/import").with(csrf()).session(adminSession)
                        .contentType("application/zip").content("kein zip".getBytes()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/settings/backup/import").with(csrf()).session(adminSession)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "de").contentType("application/zip").content(archive))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.problems[0]").value("documents/fehlt.pdf: Die Datei fehlt im Archiv."));
        Document imported = documents.findAll().stream()
                .filter(d -> "Importierter Mietvertrag".equals(d.getTitle())).findFirst().orElseThrow();
        assertThat(imported.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(imported.getCategory()).isEqualTo("Vertrag");
        assertThat(imported.getTags()).containsExactlyInAnyOrder("miete", "import");
        assertThat(imported.getDocumentDate()).isEqualTo(LocalDate.of(2024, 5, 1));
        assertThat(imported.getSource()).isEqualTo(DocumentSource.MAIL);
        assertThat(imported.getMailSubject()).isEqualTo("Ihr Vertrag");
        assertThat(imported.isOcrUsed()).isTrue();
        assertThat(imported.getPageCount()).isEqualTo(1);
        mvc.perform(get("/api/documents").param("q", "Importfeder").session(adminSession))
                .andExpect(jsonPath("$.items[0].id").value(imported.getId()));
        // ein zweites Mal: nichts doppelt
        mvc.perform(post("/api/settings/backup/import").with(csrf()).session(adminSession)
                        .contentType("application/zip").content(archive))
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.duplicates").value(1));
        // eine hier bearbeitete Fassung (andere Datei, aber dieselbe Prüfsumme des Originals) gilt ebenso als vorhanden
        byte[] edited = zip(java.util.Map.of(
                "documents.json", ("[{\"file\":\"documents/bearbeitet.pdf\",\"title\":\"Bearbeitet\",\"sha256\":\""
                        + imported.getSha256() + "\"}]").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "documents/bearbeitet.pdf", pdf("Seite eins des Mietvertrags, mit Anmerkung")));
        mvc.perform(post("/api/settings/backup/import").with(csrf()).session(adminSession)
                        .contentType("application/zip").content(edited))
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.duplicates").value(1));

        // der Export bringt nun auch den Text mit – ein Kreislauf ohne Verlust
        String exportToken = jsonValue(mvc.perform(post("/api/documents/export").param("q", "Importfeder")
                .with(csrf()).session(adminSession)).andExpect(jsonPath("$.count").value(1)).andReturn(), "$.token");
        java.util.Map<String, byte[]> exported = unzip(mvc.perform(get("/api/documents/export/" + exportToken)
                .session(adminSession)).andReturn().getResponse().getContentAsByteArray());
        String exportedJson = new String(exported.get("documents.json"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(exportedJson).contains("\"textFile\"", "\"ocr\" : true");
        assertThat(exported.keySet()).anyMatch(n -> n.startsWith("texts/") && n.endsWith(".txt"));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    @Order(27)
    void passkeysSignInWithoutPassword() throws Exception {
        String origin = "http://localhost"; // so sieht MockMvc die Adresse
        MockHttpSession paula = userWithSession("passkey-paula");
        SoftPasskey passkey = new SoftPasskey();

        // anlegen: nur mit dem Passwort (ohne Zwei-Faktor-Anmeldung)
        mvc.perform(post("/api/auth/passkeys/options").with(csrf()).session(paula)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"falsch\"}"))
                .andExpect(status().isBadRequest());
        String creation = mvc.perform(post("/api/auth/passkeys/options").with(csrf()).session(paula)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"test-Passwort-7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rp.id").value("localhost"))
                .andExpect(jsonPath("$.authenticatorSelection.userVerification").value("required"))
                .andReturn().getResponse().getContentAsString();
        String register = JSON.writeValueAsString(java.util.Map.of("name", "Laptop",
                "credential", passkey.create(creation, origin)));
        mvc.perform(post("/api/auth/passkeys").with(csrf()).session(paula).contentType(MediaType.APPLICATION_JSON)
                        .content(register))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Laptop"));
        // die Challenge gilt nur einmal
        mvc.perform(post("/api/auth/passkeys").with(csrf()).session(paula).contentType(MediaType.APPLICATION_JSON)
                .content(register)).andExpect(status().isBadRequest());
        String passkeyId = jsonValue(mvc.perform(get("/api/auth/passkeys").session(paula))
                .andExpect(jsonPath("$", hasSize(1))).andReturn(), "$[0].id");

        // anmelden ohne Passwort, in einer neuen Sitzung
        MockHttpSession fresh = new MockHttpSession();
        String request = mvc.perform(post("/api/auth/passkey/options").with(csrf()).session(fresh))
                .andExpect(jsonPath("$.rpId").value("localhost")).andReturn().getResponse().getContentAsString();
        String assertion = passkey.get(request, origin);
        mvc.perform(post("/api/auth/passkey/login").with(csrf()).session(fresh)
                        .contentType(MediaType.APPLICATION_JSON).content(assertion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("passkey-paula"));
        mvc.perform(get("/api/auth/status").session(fresh)).andExpect(jsonPath("$.user.username").value("passkey-paula"));
        mvc.perform(get("/api/auth/passkeys").session(paula)).andExpect(jsonPath("$[0].lastUsedAt").isNotEmpty());

        // dieselbe Antwort ein zweites Mal, eine fremde Adresse, eine fremde Challenge: abgelehnt
        MockHttpSession other = new MockHttpSession();
        mvc.perform(post("/api/auth/passkey/login").with(csrf()).session(other)
                .contentType(MediaType.APPLICATION_JSON).content(assertion)).andExpect(status().isBadRequest());
        String challenge = mvc.perform(post("/api/auth/passkey/options").with(csrf()).session(other))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/auth/passkey/login").with(csrf()).session(other)
                        .contentType(MediaType.APPLICATION_JSON).content(passkey.get(challenge, "https://evil.example")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/passkey/options").with(csrf()).session(other)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/passkey/login").with(csrf()).session(other)
                        .contentType(MediaType.APPLICATION_JSON).content(assertion))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/status").session(other)).andExpect(jsonPath("$.user").doesNotExist());

        // entfernt: damit kommt niemand mehr herein
        mvc.perform(delete("/api/auth/passkeys/" + passkeyId).with(csrf()).session(paula))
                .andExpect(status().isNoContent());
        MockHttpSession late = new MockHttpSession();
        String lateOptions = mvc.perform(post("/api/auth/passkey/options").with(csrf()).session(late))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/auth/passkey/login").with(csrf()).session(late)
                        .contentType(MediaType.APPLICATION_JSON).content(passkey.get(lateOptions, origin)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(28)
    void automaticBackupWritesKeepsTheNewestAndWarns() throws Exception {
        String auto = "/api/settings/backup/auto";
        String settings = """
                {"enabled":%s,"hour":3,"keep":2,"directory":"%s","warnAfterDays":7}""";
        if (mariaDb != null) {
            // mit MariaDB sichert man die Datenbank mit deren Werkzeugen – kein Hinweis, keine Einstellung
            mvc.perform(get(auto).session(adminSession)).andExpect(jsonPath("$.available").value(false));
            mvc.perform(put(auto).with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                    .content(settings.formatted(true, ""))).andExpect(status().isConflict());
            mvc.perform(post(auto + "/run").with(csrf()).session(adminSession)).andExpect(status().isConflict());
            mvc.perform(get("/api/system").session(adminSession)).andExpect(jsonPath("$.backup").doesNotExist());
            return;
        }
        mvc.perform(get(auto).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.nextRunAt").doesNotExist())
                .andExpect(jsonPath("$.defaultDirectory").value(dataDir.resolve("backups").toString()));

        // noch nie gesichert: der Admin sieht den Hinweis, ein Benutzer nicht
        jdbc.sql("DELETE FROM settings WHERE id = 'backup'").update();
        mvc.perform(get("/api/system").session(adminSession))
                .andExpect(jsonPath("$.backup.overdue").value(true))
                .andExpect(jsonPath("$.backup.lastBackupAt").doesNotExist())
                .andExpect(jsonPath("$.backup.failure").doesNotExist());
        MockHttpSession user = userWithSession("sicherung-sina");
        mvc.perform(get("/api/system").session(user)).andExpect(jsonPath("$.backup").doesNotExist());
        mvc.perform(get(auto).session(user)).andExpect(status().isForbidden());

        // ungültige Ordner
        for (String bad : List.of("relativ/ordner", dataDir.toString(), dataDir.resolve("files/x").toString())) {
            mvc.perform(put(auto).with(csrf()).session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "de")
                            .contentType(MediaType.APPLICATION_JSON).content(settings.formatted(true, bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").isNotEmpty());
        }
        mvc.perform(put(auto).with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"hour\":24,\"keep\":0,\"directory\":\"\",\"warnAfterDays\":7}"))
                .andExpect(status().isBadRequest());

        Path target = createTempDir("aidocs-sicherungen").resolve("nachts");
        mvc.perform(put(auto).with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content(settings.formatted(true, target)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.directory").value(target.toString()))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty());
        assertThat(target).isDirectory();

        // drei Läufe, zwei Stände bleiben; fremde Dateien im Ordner rührt die Sicherung nicht an
        Files.writeString(target.resolve("notizen.txt"), "bleibt");
        for (int i = 0; i < 3; i++) {
            autoBackup.run();
            Thread.sleep(1100); // der Name trägt die Sekunde
        }
        List<String> names;
        try (var files = Files.list(target)) {
            names = files.map(f -> f.getFileName().toString()).sorted().toList();
        }
        assertThat(names).hasSize(3).contains("notizen.txt");
        assertThat(names.stream().filter(n -> n.matches("AIDocuments-Sicherung-\\d{4}-\\d{2}-\\d{2}-\\d{6}\\.zip")))
                .hasSize(2);
        byte[] newest = Files.readAllBytes(target.resolve(names.get(1)));
        // mit AIDOCS_BACKUP_PASSWORD verschlüsselt
        assertThat(unzip(newest, BACKUP_PASSWORD)).containsKey("README.txt").containsKey("aidocuments.db");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> unzip(newest))
                .isInstanceOf(java.util.zip.ZipException.class);
        mvc.perform(get(auto).session(adminSession))
                .andExpect(jsonPath("$.encrypted").value(true))
                .andExpect(jsonPath("$.lastRunOk").value(true))
                .andExpect(jsonPath("$.backups", hasSize(2)))
                .andExpect(jsonPath("$.backups[0].name").value(names.get(1)));
        // gesichert: kein Hinweis mehr
        mvc.perform(get("/api/system").session(adminSession)).andExpect(jsonPath("$.backup").doesNotExist());

        // der Ordner verschwindet (etwa: Netzlaufwerk weg) – Hinweis und genau eine E-Mail je Fehlerserie
        int mailsBefore = mailsTo("chef@example.test").size();
        try (var files = Files.list(target)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(target);
        Files.writeString(target, "keine Ordner");
        autoBackup.run();
        autoBackup.run();
        List<jakarta.mail.internet.MimeMessage> toChef = mailsTo("chef@example.test");
        assertThat(toChef).hasSize(mailsBefore + 1);
        assertThat(toChef.getLast().getSubject()).contains("Sicherung");
        assertThat((String) toChef.getLast().getContent()).contains(target.toString()).contains("/settings/backup");
        mvc.perform(get("/api/system").session(adminSession).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.backup.overdue").value(false))
                .andExpect(jsonPath("$.backup.failure").value(org.hamcrest.Matchers.containsString(target.toString())));
        mvc.perform(get(auto).session(adminSession)).andExpect(jsonPath("$.lastRunOk").value(false));

        // wieder ein Ordner: gelingt, die nächste Fehlerserie meldet sich wieder
        Files.delete(target);
        mvc.perform(post(auto + "/run").with(csrf()).session(adminSession)).andExpect(status().isAccepted());
        for (int i = 0; i < 100 && !Boolean.TRUE.equals(lastRunOk()); i++) {
            Thread.sleep(100);
        }
        assertThat(lastRunOk()).isTrue();
        assertThat(target).isDirectory();

        mvc.perform(put(auto).with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content(settings.formatted(false, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.directory").value(dataDir.resolve("backups").toString()));
    }

    /** Jede Seite der Oberfläche lässt sich direkt aufrufen oder neu laden – der Server liefert dafür die App aus. */
    @Test
    @Order(29)
    void everyPageOfTheInterfaceCanBeReloaded() throws Exception {
        for (String page : List.of("/documents", "/documents/abc", "/history", "/reminders", "/contracts",
                "/duplicates/abc", "/settings", "/settings/backup", "/s/token")) {
            mvc.perform(get(page)).andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .forwardedUrl("/index.html"));
        }
    }

    @Test
    @Order(30)
    void eInvoicesAreReadExactlyWithoutAi() throws Exception {
        byte[] cii = einvoice("xrechnung-cii.xml");
        long before = documents.count();
        String id = jsonValue(mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files",
                        "RE-2026-004711.xml", "application/xml", cii)).with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("CREATED")).andReturn(), "$[0].documentId");
        awaitProcessed((int) before + 1);

        // die exakten Angaben stehen am Dokument – Dokumentart, Absender und Datum stammen aus der XML-Datei
        mvc.perform(get("/api/documents/" + id).session(adminSession))
                .andExpect(jsonPath("$.originalFilename").value("RE-2026-004711.pdf"))
                .andExpect(jsonPath("$.category").value("Rechnung"))
                .andExpect(jsonPath("$.correspondent").value("Stadtwerke Musterstadt GmbH"))
                .andExpect(jsonPath("$.documentDate").value("2026-11-15"))
                .andExpect(jsonPath("$.hasThumbnail").value(true))
                .andExpect(jsonPath("$.eInvoice.number").value("RE-2026-004711"))
                .andExpect(jsonPath("$.eInvoice.syntax").value("CII"))
                .andExpect(jsonPath("$.eInvoice.dueDate").value("2026-12-15"))
                .andExpect(jsonPath("$.eInvoice.seller.vatId").value("DE123456789"))
                .andExpect(jsonPath("$.eInvoice.grossAmount").value(194.78))
                .andExpect(jsonPath("$.eInvoice.amountDue").value(144.78))
                .andExpect(jsonPath("$.eInvoice.iban").value("DE02120300000000202051"))
                .andExpect(jsonPath("$.eInvoice.lines.length()").value(3));
        // das PDF ist die lesbare Darstellung; die XML-Datei bekommt man unverändert zurück
        assertThat(new String(file("/api/documents/" + id + "/file"), java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        MvcResult xml = mvc.perform(get("/api/documents/" + id + "/e-invoice").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn();
        assertThat(xml.getResponse().getContentAsByteArray()).isEqualTo(cii);
        assertThat(xml.getResponse().getContentType()).startsWith("application/xml");

        // dieselbe Rechnung noch einmal, anders formatiert (andere Prüfsumme): erkannt an Verkäufer, Nummer, Datum
        byte[] reformatted = new String(cii, java.nio.charset.StandardCharsets.UTF_8).replace("  ", "\t").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files", "kopie.xml", "text/xml",
                        reformatted)).with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("DUPLICATE"))
                .andExpect(jsonPath("$[0].documentId").value(id));

        // ZUGFeRD: ein PDF mit eingebetteter Rechnung; kommt die XML-Datei gleich danach, ist sie ein Duplikat –
        // auch wenn das PDF noch gar nicht verarbeitet ist
        byte[] ubl = einvoice("xrechnung-ubl.xml");
        Path zugferd = Files.createTempFile(dataDir, "zugferd", ".pdf");
        new de.aidocuments.einvoice.EInvoicePdf(de.aidocuments.i18n.TestMessages.create()).render(
                de.aidocuments.einvoice.EInvoiceReader.parse(ubl), ubl, "factur-x.xml", Locale.GERMAN, zugferd);
        String pdfId = jsonValue(mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files",
                        "schmidt.pdf", "application/pdf", Files.readAllBytes(zugferd))).with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("CREATED")).andReturn(), "$[0].documentId");
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files", "2026-0815.xml",
                        "application/xml", ubl)).with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("DUPLICATE"))
                .andExpect(jsonPath("$[0].documentId").value(pdfId));
        awaitProcessed((int) before + 2);
        mvc.perform(get("/api/documents/" + pdfId).session(adminSession))
                .andExpect(jsonPath("$.originalFilename").value("schmidt.pdf"))
                .andExpect(jsonPath("$.correspondent").value("Kontorbedarf Schmidt e.K."))
                .andExpect(jsonPath("$.eInvoice.syntax").value("UBL"))
                .andExpect(jsonPath("$.eInvoice.taxAmount").value(39.50));

        // andere XML-Dateien sind keine Dokumente; ein gewöhnliches PDF hat keine E-Rechnung
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files", "daten.xml", "application/xml",
                        "<daten><wert>1</wert></daten>".getBytes())).with(csrf()).session(adminSession)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$[0].outcome").value("REJECTED"))
                .andExpect(jsonPath("$[0].message")
                        .value("Die XML-Datei ist keine E-Rechnung (XRechnung, ZUGFeRD oder Factur-X)."));
        String plainId = documents.findAll().stream().filter(d -> d.getEInvoice() == null).findFirst()
                .orElseThrow().getId();
        mvc.perform(get("/api/documents/" + plainId + "/e-invoice").session(adminSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("This document does not contain an e-invoice."));

        // ein gewöhnliches PDF und die XRechnung dazu, zusammen hochgeladen: ein Dokument, die XML-Datei im PDF –
        // und das PDF des Absenders bleibt Byte für Byte erhalten
        byte[] plainPdf = pdf("Stadtwerke Musterstadt GmbH", "Rechnung Nr. RE-2026-0999");
        byte[] pairXml = new String(cii, java.nio.charset.StandardCharsets.UTF_8).replace("RE-2026-004711", "RE-2026-0999")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MvcResult pair = mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("files", "rechnung.pdf", "application/pdf", plainPdf))
                        .file(new MockMultipartFile("files", "rechnung.xml", "application/xml", pairXml))
                        .with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$[1].outcome").value("IGNORED")).andReturn();
        String pairId = jsonValue(pair, "$[0].documentId");
        assertThat(jsonValue(pair, "$[1].documentId")).isEqualTo(pairId);
        awaitProcessed((int) documents.count());
        mvc.perform(get("/api/documents/" + pairId).session(adminSession))
                .andExpect(jsonPath("$.originalFilename").value("rechnung.pdf"))
                .andExpect(jsonPath("$.eInvoice.number").value("RE-2026-0999"))
                .andExpect(jsonPath("$.eInvoice.prepaidAmount").value(50.00));
        byte[] stored = file("/api/documents/" + pairId + "/file");
        assertThat(java.util.Arrays.copyOf(stored, plainPdf.length)).isEqualTo(plainPdf);
        assertThat(mvc.perform(get("/api/documents/" + pairId + "/e-invoice").session(adminSession)).andReturn()
                .getResponse().getContentAsByteArray()).isEqualTo(pairXml);
        // dasselbe PDF noch einmal allein: schon da
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files", "nochmal.pdf", "application/pdf",
                        plainPdf)).with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("DUPLICATE"));
        // ein PDF, das die Rechnungsnummer nicht nennt, gehört nicht dazu: zwei Dokumente
        byte[] otherXml = new String(cii, java.nio.charset.StandardCharsets.UTF_8).replace("RE-2026-004711", "RE-2026-1001")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("files", "agb.pdf", "application/pdf", pdf("Allgemeine Geschäftsbedingungen")))
                        .file(new MockMultipartFile("files", "RE-2026-1001.xml", "application/xml", otherXml))
                        .with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$[1].outcome").value("CREATED"));

        // erst die XML-Datei, dann das ZUGFeRD-PDF derselben Rechnung: ebenfalls ein Duplikat
        byte[] laterXml = new String(cii, java.nio.charset.StandardCharsets.UTF_8).replace("RE-2026-004711", "RE-2026-1002")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String laterId = jsonValue(mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files",
                        "RE-2026-1002.xml", "application/xml", laterXml)).with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("CREATED")).andReturn(), "$[0].documentId");
        Path laterPdf = Files.createTempFile(dataDir, "zugferd", ".pdf");
        new de.aidocuments.einvoice.EInvoicePdf(de.aidocuments.i18n.TestMessages.create()).render(
                de.aidocuments.einvoice.EInvoiceReader.parse(laterXml), laterXml, "factur-x.xml", Locale.GERMAN, laterPdf);
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files", "RE-2026-1002.pdf",
                        "application/pdf", Files.readAllBytes(laterPdf))).with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("DUPLICATE"))
                .andExpect(jsonPath("$[0].documentId").value(laterId));
        awaitProcessed((int) documents.count());

        // eine Rechnung mit angehängten Seiten trennen: Der abgetrennte Teil ist keine zweite Rechnung
        byte[] splitXml = new String(cii, java.nio.charset.StandardCharsets.UTF_8).replace("RE-2026-004711", "RE-2026-1003")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] withAttachment;
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pages("Rechnung RE-2026-1003", "Mietvertrag Seite 1",
                "Mietvertrag Seite 2")); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            de.aidocuments.einvoice.EInvoiceReader.attachTo(doc, splitXml, "factur-x.xml");
            doc.save(out);
            withAttachment = out.toByteArray();
        }
        String invoiceId = jsonValue(mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files",
                        "rechnung-mit-vertrag.pdf", "application/pdf", withAttachment)).with(csrf()).session(adminSession))
                .andExpect(jsonPath("$[0].outcome").value("CREATED")).andReturn(), "$[0].documentId");
        awaitProcessed((int) documents.count());
        String rest = jsonValue(mvc.perform(post("/api/documents/" + invoiceId + "/split").with(csrf())
                        .session(adminSession).contentType(MediaType.APPLICATION_JSON).content("{\"fromPage\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.original.eInvoice.number").value("RE-2026-1003")).andReturn(), "$.createdId");
        awaitProcessed((int) documents.count());
        mvc.perform(get("/api/documents/" + rest).session(adminSession))
                .andExpect(jsonPath("$.pageCount").value(2))
                .andExpect(jsonPath("$.eInvoice").doesNotExist())
                .andExpect(jsonPath("$.category").value(org.hamcrest.Matchers.not("Rechnung")));
        mvc.perform(get("/api/documents/" + rest + "/e-invoice").session(adminSession)).andExpect(status().isNotFound());

        // eine E-Rechnung an ein anderes Dokument anhängen geht nicht (ihre XML-Datei käme mit) – umgekehrt schon
        mvc.perform(post("/api/documents/" + rest + "/merge").with(csrf()).session(adminSession)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "de")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"documentIds\":[\"" + invoiceId + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ist eine E-Rechnung")));
        mvc.perform(post("/api/documents/" + invoiceId + "/merge").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"documentIds\":[\"" + rest + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageCount").value(3))
                .andExpect(jsonPath("$.eInvoice.number").value("RE-2026-1003"));
    }

    @Test
    @Order(31)
    void aPdfAndItsXRechnungArrivingTogetherBecomeOneDocument() throws Exception {
        String cii = new String(einvoice("xrechnung-cii.xml"), java.nio.charset.StandardCharsets.UTF_8);

        // per E-Mail: das PDF des Absenders und die XRechnung dazu
        GreenMailUser mailbox = greenMail.setUser("rechnungen@example.test", "rechnungen@example.test",
                "imap-test-secret");
        MimeMessage mail = mailWithPdf("Ihre Rechnung", "Rechnung_2001.pdf",
                pdf("Stadtwerke Musterstadt GmbH", "Rechnung RE-2026-2001"));
        MimeMultipart parts = (MimeMultipart) mail.getContent();
        MimeBodyPart xml = new MimeBodyPart();
        xml.setDataHandler(new DataHandler(new ByteArrayDataSource(cii.replace("RE-2026-004711", "RE-2026-2001")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/xml")));
        xml.setFileName("RE-2026-2001.xml");
        parts.addBodyPart(xml);
        mail.setContent(parts);
        mail.saveChanges();
        mailbox.deliver(mail);
        String accountId = jsonValue(mvc.perform(post("/api/mail-accounts").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Rechnungen","host":"127.0.0.1","port":%d,"security":"NONE",
                                 "username":"rechnungen@example.test","password":"imap-test-secret","folder":"INBOX",
                                 "enabled":false}""".formatted(greenMail.getImap().getPort())))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        long before = documents.count();
        mailSync.sync(accountId);
        awaitProcessed((int) before + 1);
        Document fromMail = documents.findAll().stream().filter(doc -> accountId.equals(doc.getMailAccountId()))
                .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(),
                        list -> { assertThat(list).hasSize(1); return list.getFirst(); }));
        assertThat(fromMail.getOriginalFilename()).isEqualTo("Rechnung_2001.pdf");
        assertThat(fromMail.getEInvoice().number()).isEqualTo("RE-2026-2001");

        // im Eingangsordner: gleicher Name, einmal .pdf und einmal .xml
        mvc.perform(put("/api/settings/import").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervalMinutes\":15,\"folderEnabled\":true,\"folderPath\":\"%s\",\"folderMode\":\"MOVE\"}"
                                .formatted(inbox)))
                .andExpect(status().isOk());
        Path pdfFile = inbox.resolve("Rechnung 2002.pdf");
        Path xmlFile = inbox.resolve("Rechnung 2002.XML");
        Files.write(pdfFile, pdf("Stadtwerke Musterstadt GmbH", "Rechnung RE-2026-2002"));
        Files.writeString(xmlFile, cii.replace("RE-2026-004711", "RE-2026-2002"));
        FileTime settled = FileTime.from(Instant.now().minusSeconds(60));
        Files.setLastModifiedTime(pdfFile, settled);
        Files.setLastModifiedTime(xmlFile, settled);
        before = documents.count();
        folderImport.scan();
        awaitProcessed((int) before + 1);
        assertThat(documents.findAll().stream().filter(doc -> doc.getEInvoice() != null
                && "RE-2026-2002".equals(doc.getEInvoice().number())).map(Document::getOriginalFilename))
                .containsExactly("Rechnung 2002.pdf");
        assertThat(pdfFile).doesNotExist();
        assertThat(xmlFile).doesNotExist();
        assertThat(inbox.resolve("importiert").resolve("Rechnung 2002.XML")).exists();
    }

    private static byte[] einvoice(String name) throws IOException {
        try (java.io.InputStream in = ApplicationFlowTest.class.getResourceAsStream("/einvoice/" + name)) {
            return in.readAllBytes();
        }
    }

    private Boolean lastRunOk() throws Exception {
        String json = mvc.perform(get("/api/settings/backup/auto").session(adminSession)).andReturn().getResponse()
                .getContentAsString();
        return JSON.readTree(json).path("running").asBoolean() ? null
                : JSON.readTree(json).path("lastRunOk").isBoolean() ? JSON.readTree(json).path("lastRunOk").asBoolean() : null;
    }

    /** Sicherung vorbereiten und herunterladen; der Dateiname verrät, ob verschlüsselt */
    private byte[] downloadBackup(String body) throws Exception {
        MvcResult prepared = mvc.perform(post("/api/settings/backup").with(csrf()).session(adminSession)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(prepared.getResponse().getStatus()).as(prepared.getResponse().getContentAsString()).isEqualTo(200);
        String token = jsonValue(prepared, "$.token");
        return mvc.perform(get("/api/settings/backup/" + token).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("-verschluesselt.zip")))
                .andReturn().getResponse().getContentAsByteArray();
    }

    /** ein AES-verschlüsseltes ZIP so lesen, wie es 7-Zip & Co. tun */
    private static java.util.Map<String, byte[]> unzip(byte[] bytes, String password) throws IOException {
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (var zip = new net.lingala.zip4j.io.inputstream.ZipInputStream(new java.io.ByteArrayInputStream(bytes),
                password.toCharArray(), java.nio.charset.StandardCharsets.UTF_8)) {
            for (net.lingala.zip4j.model.LocalFileHeader entry; (entry = zip.getNextEntry()) != null; ) {
                assertThat(entry.isEncrypted()).isTrue();
                assertThat(entry.getEncryptionMethod()).isEqualTo(net.lingala.zip4j.model.enums.EncryptionMethod.AES);
                entries.put(entry.getFileName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static java.util.Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static byte[] zip(java.util.Map<String, byte[]> entries) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private List<jakarta.mail.internet.MimeMessage> mailsTo(String address) throws Exception {
        List<jakarta.mail.internet.MimeMessage> result = new java.util.ArrayList<>();
        for (jakarta.mail.internet.MimeMessage message : greenMail.getReceivedMessages()) {
            jakarta.mail.Address[] to = message.getRecipients(jakarta.mail.Message.RecipientType.TO);
            if (to != null && java.util.Arrays.stream(to).anyMatch(a -> a.toString().contains(address))) {
                result.add(message);
            }
        }
        return result;
    }

    /** Lädt eine einseitige PDF hoch und wartet, bis sie verarbeitet ist. */
    private String uploadAs(MockHttpSession session, String filename, String text) throws Exception {
        int before = documents.findAll().size();
        String id = jsonValue(mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("files", filename, "application/pdf", pdf(text)))
                        .with(csrf()).session(session))
                .andExpect(jsonPath("$[0].outcome").value("CREATED")).andReturn(), "$[0].documentId");
        awaitProcessed(before + 1);
        return id;
    }

    /** Legt einen Benutzer an und meldet ihn in einer eigenen Sitzung an. */
    private MockHttpSession userWithSession(String name) throws Exception {
        mvc.perform(post("/api/users").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"test-Passwort-7\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated());
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").with(csrf()).session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"test-Passwort-7\"}"))
                .andExpect(status().isOk());
        return session;
    }

    private List<String> mailFiles(String accountId) {
        return documents.findAll().stream().filter(doc -> accountId.equals(doc.getMailAccountId()))
                .map(Document::getOriginalFilename).toList();
    }

    /** Der Code der Authenticator-App für das aktuelle (oder ein benachbartes) 30-Sekunden-Intervall. */
    private static String totp(String secret, int offset) {
        long step = Math.floorDiv(java.time.Instant.now().getEpochSecond(), 30) + offset;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(base32(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
            int o = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[o] & 0x7f) << 24) | ((hash[o + 1] & 0xff) << 16) | ((hash[o + 2] & 0xff) << 8)
                    | (hash[o + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] base32(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write(buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static long jsonNumber(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    @Test
    @Order(32)
    void businessesUnlockWithASignedCodeEveryoneElseUsesItForFree() throws Exception {
        mvc.perform(post("/api/users").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lena\",\"password\":\"test-Passwort-32\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated());
        MockHttpSession lena = new MockHttpSession();
        mvc.perform(post("/api/auth/login").with(csrf()).session(lena).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lena\",\"password\":\"test-Passwort-32\"}"))
                .andExpect(status().isOk());
        // noch nicht angegeben (die Ersteinrichtung in Order 1 lief ohne Nutzungsart)
        mvc.perform(get("/api/system").session(lena))
                .andExpect(jsonPath("$.license.state").value("UNSET"));
        mvc.perform(put("/api/settings/license").with(csrf()).session(lena)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"usage\":\"PRIVATE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/license").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        // Unternehmen ohne Code: alles nutzbar, nur der Hinweis
        mvc.perform(put("/api/settings/license").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"usage\":\"BUSINESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("UNLICENSED"))
                .andExpect(jsonPath("$.codeStored").value(false));
        mvc.perform(get("/api/documents").session(lena)).andExpect(status().isOk());

        // ausgedacht, mit fremdem Schlüssel oder abgelaufen: abgelehnt, nichts gespeichert
        java.security.PrivateKey foreign = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                .getPrivate();
        String forged = de.aidocuments.license.LicenseCodeTest.issue(new de.aidocuments.license.LicenseCode.License(
                "12345678", "Fremd GmbH", java.time.LocalDate.of(2026, 1, 1), null), foreign);
        String expired = de.aidocuments.license.LicenseCodeTest.issueWithTestKey(
                new de.aidocuments.license.LicenseCode.License("0badc0de", "Alt GmbH",
                        java.time.LocalDate.of(2025, 1, 1), java.time.LocalDate.of(2025, 12, 31)));
        for (String bad : java.util.List.of("AIDOCS-HALLO-WELT", forged)) {
            mvc.perform(put("/api/settings/license").with(csrf()).session(adminSession)
                            .header(HttpHeaders.ACCEPT_LANGUAGE, "de")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"usage\":\"BUSINESS\",\"code\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("Freischaltcode")));
        }
        mvc.perform(put("/api/settings/license").with(csrf()).session(adminSession)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "de")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usage\":\"BUSINESS\",\"code\":\"" + expired + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("2025")));
        mvc.perform(get("/api/settings/license").session(adminSession))
                .andExpect(jsonPath("$.state").value("UNLICENSED"))
                .andExpect(jsonPath("$.codeStored").value(false));

        // gültiger Code, auch klein geschrieben und umbrochen eingegeben
        String code = de.aidocuments.license.LicenseCodeTest.issueWithTestKey(
                new de.aidocuments.license.LicenseCode.License("c0ffee42", "Beispiel & Söhne GmbH",
                        java.time.LocalDate.of(2026, 11, 1), java.time.LocalDate.of(2030, 12, 31)));
        mvc.perform(put("/api/settings/license").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usage\":\"BUSINESS\",\"code\":\"" + code.toLowerCase().replace("-", "\\n") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LICENSED"))
                .andExpect(jsonPath("$.licensee").value("Beispiel & Söhne GmbH"))
                .andExpect(jsonPath("$.validUntil").value("2030-12-31"))
                .andExpect(jsonPath("$.licenseId").value("c0ffee42"));
        // alle sehen, für wen freigeschaltet ist – den Code selbst bekommt niemand zurück
        String system = mvc.perform(get("/api/system").session(lena))
                .andExpect(jsonPath("$.license.state").value("LICENSED"))
                .andExpect(jsonPath("$.license.licensee").value("Beispiel & Söhne GmbH"))
                .andReturn().getResponse().getContentAsString();
        assertThat(system.toUpperCase()).doesNotContain(code.substring(20, 40));

        // ohne Code gespeichert: der bisherige bleibt
        mvc.perform(put("/api/settings/license").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"usage\":\"BUSINESS\"}"))
                .andExpect(jsonPath("$.state").value("LICENSED"));
        // Verein: kostenlos, der Code wird verworfen
        mvc.perform(put("/api/settings/license").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"usage\":\"CLUB\",\"code\":\"" + code + "\"}"))
                .andExpect(jsonPath("$.state").value("FREE"))
                .andExpect(jsonPath("$.usage").value("CLUB"))
                .andExpect(jsonPath("$.codeStored").value(false));
        mvc.perform(put("/api/settings/license").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"usage\":\"BUSINESS\"}"))
                .andExpect(jsonPath("$.state").value("UNLICENSED"));
        mvc.perform(put("/api/settings/license").with(csrf()).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"usage\":\"PRIVATE\"}"))
                .andExpect(jsonPath("$.state").value("FREE"));
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
        return mailWithPdf(subject, filename, pdf, new InternetAddress("kopierer@example.test", "Kopierer 2. OG"));
    }

    private static MimeMessage mailWithPdf(String subject, String filename, byte[] pdf, InternetAddress from)
            throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(from);
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
