package com.kopite.devspace.attachment.presentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.*;
@Configuration(proxyBeanMethods=false)
public class AttachmentStreamingConfiguration {
    @Bean ThreadPoolTaskExecutor attachmentStreamExecutor(@Value("${app.attachment.stream.max-concurrent:16}")int max) {
        var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(max);executor.setMaxPoolSize(max);executor.setQueueCapacity(0);executor.setThreadNamePrefix("attachment-stream-");return executor;
    }
    @Bean WebMvcConfigurer attachmentAsyncSupport(@org.springframework.beans.factory.annotation.Qualifier("attachmentStreamExecutor") ThreadPoolTaskExecutor attachmentStreamExecutor,@Value("${app.attachment.stream.timeout-ms:30000}")long timeout) {
        if(timeout<1)throw new IllegalArgumentException("Invalid stream timeout");
        return new WebMvcConfigurer(){public void configureAsyncSupport(AsyncSupportConfigurer configurer){configurer.setDefaultTimeout(timeout).setTaskExecutor(attachmentStreamExecutor);}};
    }
}
