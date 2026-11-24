package de.aidocuments.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.aidocuments.config.AppProperties;
import de.aidocuments.i18n.Messages;
import de.aidocuments.i18n.MsgCodec;
import de.aidocuments.notify.Notifications;
import de.aidocuments.persistence.Db;
import de.aidocuments.persistence.SettingsStore;
import de.aidocuments.web.ApiException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Ein zu kurzes AIDOCS_BACKUP_PASSWORD wird nicht verwendet – weder stillschweigend weggelassen noch übernommen. */
class BackupPasswordTest {

    @TempDir
    Path dir;

    private AppProperties properties(String password) {
        return new AppProperties(dir.resolve("data"), null, null, null,
                new AppProperties.Backup(dir.resolve("sicherungen").toString(), password));
    }

    private BackupService service(String password) {
        return new BackupService(mock(Db.class), mock(SettingsStore.class), mock(Messages.class),
                properties(password), "jdbc:sqlite:" + dir.resolve("test.db"));
    }

    @Test
    void aTooShortConfiguredPasswordIsRefused() {
        BackupService backups = service("kurz");
        assertThat(backups.hasConfiguredPassword()).isTrue();
        assertThatThrownBy(backups::configuredPassword).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.msg().key()).isEqualTo("error.backup.passwordLength"));
        // auch der Download „mit dem eingestellten Passwort“ nicht – und erst recht nicht unverschlüsselt
        assertThatThrownBy(() -> backups.prepare(new MockHttpSession(), null, true))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.msg().key()).isEqualTo("error.backup.passwordLength"));
    }

    @Test
    void aValidConfiguredPasswordIsUsed() {
        BackupService backups = service("lang-genug-und-geheim");
        assertThat(backups.configuredPassword()).containsExactly("lang-genug-und-geheim".toCharArray());
        assertThat(backups.prepare(new MockHttpSession(), null, true)).isNotBlank();
        assertThat(service("").configuredPassword()).isNull();
    }

    @Test
    void anAutomaticBackupWithATooShortPasswordFails() throws Exception {
        BackupService backups = mock(BackupService.class);
        when(backups.status()).thenReturn(new BackupService.BackupStatus(true, null, 0, 0, true));
        when(backups.configuredPassword()).thenAnswer(call -> BackupService.checkedPassword("kurz"));
        SettingsStore store = mock(SettingsStore.class);
        Map<String, Object> stored = new HashMap<>();
        when(store.find(anyString(), any())).thenAnswer(call -> stored.get(call.<String>getArgument(0)));
        doAnswer(call -> stored.put(call.getArgument(0), call.getArgument(1))).when(store).save(anyString(), any());
        AutoBackup auto = new AutoBackup(backups, store, mock(Notifications.class), mock(Messages.class),
                mock(ThreadPoolTaskExecutor.class), properties("kurz"));

        auto.run();

        AutoBackup.LastRun last = (AutoBackup.LastRun) stored.get(AutoBackup.RUN_ID);
        assertThat(last).isNotNull();
        assertThat(last.ok()).isFalse();
        assertThat(MsgCodec.decode(last.error()).getFirst().key()).isEqualTo("error.backup.passwordLength");
        verify(backups, never()).write(any(OutputStream.class), any(), anyString(), any());
        verify(backups, never()).write(any(OutputStream.class), any(), eq("automatisch"));
        // keine halbe Datei liegen geblieben
        try (var files = Files.list(dir.resolve("sicherungen"))) {
            assertThat(files).isEmpty();
        }
    }
}
