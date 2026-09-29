package com.kopite.devspace.attachment.infrastructure;
import com.kopite.devspace.attachment.application.AttachmentCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
@Configuration(proxyBeanMethods=false) @EnableScheduling @RequiredArgsConstructor
@ConditionalOnProperty(name="app.attachment.cleanup.enabled",havingValue="true",matchIfMissing=true)
public class AttachmentCleanupScheduler {
    private final AttachmentCleanupService cleanup;
    @Scheduled(fixedDelayString="${app.attachment.cleanup.delay-ms:600000}",initialDelayString="${app.attachment.cleanup.delay-ms:600000}")
    public void run(){cleanup.runOnce();}
}
