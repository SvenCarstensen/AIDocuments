package de.aidocuments.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ExecutorConfig {

    /**
     * Arbeitet die Dokument-Pipeline ab (PDF lesen, KI-Analyse, Indizierung) – mit genau einem Thread:
     * Die Dokumente gehen strikt nacheinander an das Sprachmodell, nie zwei gleichzeitig.
     */
    @Bean
    public ThreadPoolTaskExecutor documentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("doc-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /** Manuell angestoßene Abrufe (Postfach, Eingangsordner) laufen hier, damit der HTTP-Request sofort zurückkehrt. */
    @Bean
    public ThreadPoolTaskExecutor importExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("import-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
