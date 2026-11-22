package de.aidocuments.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Datenbankanschluss. Ohne weitere Angabe ist das eine SQLite-Datei im Datenverzeichnis (eingebaut, nichts zu
 * installieren); mit {@code AIDOCS_DB_URL=jdbc:mariadb://…} eine externe MariaDB.
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseConfig {

    private static final String SQLITE = "jdbc:sqlite:";

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties) throws IOException {
        String url = properties.determineUrl();
        HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        if (url != null && url.startsWith(SQLITE)) {
            // SQLite legt die Datei selbst an, aber nicht das Verzeichnis darüber
            String file = url.substring(SQLITE.length()).split("\\?", 2)[0];
            Path parent = Path.of(file).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // WAL: Lesen läuft weiter, während geschrieben wird; schreiben zwei Vorgänge zugleich, wartet einer
            dataSource.addDataSourceProperty("journal_mode", "WAL");
            dataSource.addDataSourceProperty("synchronous", "NORMAL");
            dataSource.addDataSourceProperty("busy_timeout", "30000");
        }
        return dataSource;
    }
}
