package com.kopite.devspace.attachment.application;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
@Service
public class AttachmentCleanupDispatch {
    private static final Logger log=LoggerFactory.getLogger(AttachmentCleanupDispatch.class);
    private final TaskExecutor executor;private final AttachmentCleanupService cleanup;
    public AttachmentCleanupDispatch(@Qualifier("attachmentCleanupExecutor")TaskExecutor executor,AttachmentCleanupService cleanup){this.executor=executor;this.cleanup=cleanup;}
    public void submit(){try{executor.execute(()->{try{cleanup.drain(10);}catch(RuntimeException failure){log.warn("Immediate attachment cleanup deferred to scheduler");}});}catch(RuntimeException busy){log.debug("Attachment cleanup executor busy; durable jobs retained");}}
}
