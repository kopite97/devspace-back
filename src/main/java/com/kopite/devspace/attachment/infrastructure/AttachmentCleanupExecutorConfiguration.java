package com.kopite.devspace.attachment.infrastructure;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
@Configuration(proxyBeanMethods=false)
public class AttachmentCleanupExecutorConfiguration {
    @Bean ThreadPoolTaskExecutor attachmentCleanupExecutor(){var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(1);executor.setThreadNamePrefix("attachment-cleanup-");return executor;}
}
