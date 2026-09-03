package com.deep.taskscheduler.Component;

import com.deep.taskscheduler.Configuration.RabbitMQConfig;
import com.deep.taskscheduler.Repositories.JobRunRepository;
import com.deep.taskscheduler.Repositories.ScheduledJobRepository;
import com.deep.taskscheduler.entities.JobMessage;
import com.deep.taskscheduler.entities.JobRun;
import com.deep.taskscheduler.entities.RunStatus;
import com.deep.taskscheduler.entities.ScheduledJob;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private final ScheduledJobRepository jobRepository;
    private final JobRunRepository jobRunRepository;
    private final JobExecutorRegistry executorRegistry;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = RabbitMQConfig.JOBS_QUEUE)
    public void handleJob(JobMessage message , Message rawmessage){
        Optional<ScheduledJob> maybeJob = jobRepository.findById(message.jobId());
        if (maybeJob.isEmpty()) {
            log.warn("Job {} no longer exists, dropping message", message.jobId());
            return; // ack — nothing to retry, the job was deleted
        }
        ScheduledJob job = maybeJob.get();
        String workerId = Thread.currentThread().getName();
        int attempt =  getDeathCount(rawmessage) +1;
        try {
            executorRegistry.get(job.getExecutorType()).execute(job.getPayload());
            recordOutcome(job.getId(), attempt, RunStatus.SUCCESS, null, workerId);
            log.info("Job {} succeeded on attempt {}", job.getId(),attempt);

        }catch (Exception e) {
            if (attempt >= job.getMaxRetries()) {
                recordOutcome(job.getId(), attempt, RunStatus.DEAD, e.getMessage(), workerId);
                log.error("Job {} exhausted {} retries, sending to DLQ", job.getId(), job.getMaxRetries(), e);
                rabbitTemplate.convertAndSend(RabbitMQConfig.DLQ_EXCHANGE,RabbitMQConfig.JOBS_ROUTING_KEY,message);
            } else {
                recordOutcome(job.getId(), attempt, RunStatus.RETRYING, e.getMessage(), workerId);
                log.warn("Job {} failed attempt {}, will retry", job.getId(), message.attemptNumber(), e);
                throw new AmqpRejectAndDontRequeueException("Job execution failed", e);
            }
            // Reject without requeueing to the SAME queue — this routes the message to
            // jobs.queue's dead-letter-exchange (the retry exchange), whose TTL provides
            // the backoff delay before it comes back around.

        }
    }

    private int getDeathCount(Message rawMessage) {
        List<Map<String, ?>> xDeath = rawMessage.getMessageProperties().getXDeathHeader();
        if (xDeath == null || xDeath.isEmpty()) {
            return 0; // first delivery, never dead-lettered yet
        }
        return ((Number) xDeath.get(0).get("count")).intValue();
    }

    private void recordOutcome(Long jobId, int attempt, RunStatus status, String error, String workerId) {
        JobRun run = JobRun.builder()
                .jobId(jobId)
                .attemptNumber(attempt)
                .status(status)
                .workerId(workerId)
                .errorMessage(error)
                .finishedAt(LocalDateTime.now())
                .build();
        jobRunRepository.save(run);
    }
}
