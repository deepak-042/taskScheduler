package com.deep.taskscheduler.Configuration;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.deep.taskscheduler.Repositories.JobRunRepository;
import com.deep.taskscheduler.Repositories.ScheduledJobRepository;
import com.deep.taskscheduler.entities.*;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.print.attribute.standard.JobMessageFromOperator;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class JobDispatcher {
    //private final ScheduledJob scheduledJob;
    private final ScheduledJobRepository jobRepository;
    private final JobRunRepository jobRunRepository;
    private final ScheduledJobRepository scheduledJobRepository;
    //private final RabbitMQConfig rabbitMQConfig;
    private final RabbitTemplate rabbitTemplate;
    private CronParser cronParser = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING));

    public JobDispatcher( ScheduledJobRepository jobRepository,
                         JobRunRepository jobRunRepository,
                         ScheduledJobRepository scheduledJobRepository,
                         RabbitTemplate rabbitTemplate) {
        //this.scheduledJob = scheduledJob;
        this.jobRepository = jobRepository;
        this.jobRunRepository = jobRunRepository;
        this.scheduledJobRepository = scheduledJobRepository;
        this.rabbitTemplate = rabbitTemplate;

    }

    @Scheduled(fixedRate = 5000)
    @SchedulerLock(name = "dispatchDueJob" , lockAtLeastFor = "PT4S" , lockAtMostFor = "PT30S")
    public void dispatchJobs(){
        LocalDateTime now = LocalDateTime.now();
        List<ScheduledJob> scheduledJobList = scheduledJobRepository.findDueJobs(now);
        for(ScheduledJob job : scheduledJobList){
            try{
                enqueue(job,1);
                advanceOrDeactivate(job,now);
            } catch (Exception e) {
                System.out.println("failed to dispatch job");
            }
        }
    }
    private void enqueue(ScheduledJob job, int attempt){
        JobRun run = JobRun.builder()
                .jobId(job.getId())
                .status(RunStatus.QUEUED)
                .attemptNumber(attempt)
                .startedAt(LocalDateTime.now())
                .build();
        jobRunRepository.save(run);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.JOBS_EXCHANGE,
                RabbitMQConfig.JOBS_ROUTING_KEY,
                new JobMessage(job.getId(),attempt)
        );
    }
    private void advanceOrDeactivate(ScheduledJob job , LocalDateTime now){
        job.setLastRunAt(now);
        if (job.getJobType() == JobType.CRON){
            Optional<LocalDateTime> next = ExecutionTime.forCron(cronParser.parse(job.getCronExpression()))
                    .nextExecution(now.atZone(ZoneId.systemDefault()))
                    .map(z -> z.toLocalDateTime());
            if (next.isPresent()){
                job.setNextRunAt(next.get());
            }
        }else {
            job.setStatus(Status.DISABLED);
        }
        jobRepository.save(job);
    }
}
