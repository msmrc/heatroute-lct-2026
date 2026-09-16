package ru.lct.heatroute.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class OfficialJobExecutorConfiguration {
    @Bean(name = "officialJobTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor officialJobTaskExecutor(
            @Value("${heatroute.jobs.concurrency:2}") int concurrency) {
        int boundedConcurrency = Math.max(1, Math.min(concurrency, 16));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(boundedConcurrency);
        executor.setMaxPoolSize(boundedConcurrency);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("heatroute-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
