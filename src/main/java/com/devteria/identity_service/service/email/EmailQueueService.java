package com.devteria.identity_service.service.email;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailQueueService {
    private final JavaMailSender mailSender;

    private final BlockingQueue<EmailJob> queue = new ArrayBlockingQueue<>(1000);

    public void enqueue(EmailJob job) {
        boolean offered = queue.offer(job);
        if (!offered) {
            log.warn("Email queue is full, dropping job: {}", job);
        }
    }

    @PostConstruct
    void startWorker() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    EmailJob job = queue.take();
                    send(job);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error sending queued email", e);
                }
            }
        }, "email-queue-worker");
        t.setDaemon(true);
        t.start();
    }

    private void send(EmailJob job) {
        // For simplicity: send a plain text message using provided context
        String body = buildBody(job);
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(job.getTo());
            message.setSubject(job.getSubject());
            message.setText(body);
            mailSender.send(message);
            log.info("Queued email sent to {}", job.getTo());
        } catch (Exception e) {
            log.error("Failed to send email to {}", job.getTo(), e);
        }
    }

    private String buildBody(EmailJob job) {
        // minimal plain-text template handling
        if (job.getContext() == null)
            return "";
        Object name = job.getContext().get("name");
        Object newPassword = job.getContext().get("newPassword");
        StringBuilder sb = new StringBuilder();
        if (name != null)
            sb.append("Hello ").append(name).append(",\n\n");
        sb.append("Your password has been reset.\n");
        if (newPassword != null)
            sb.append("New password: ").append(newPassword).append("\n");
        sb.append("\nRegards,\nDevteria Team");
        return sb.toString();
    }
}
