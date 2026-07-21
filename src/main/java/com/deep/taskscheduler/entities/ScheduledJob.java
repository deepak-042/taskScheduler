package com.deep.taskscheduler.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "scheduled_jobs")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ScheduledJob {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobType jobType;

    private String cronExpression;
    @Column(nullable = false)
    private LocalDateTime nextRunAt;
    private LocalDateTime lastRunAt;
    private String payload;

    @Column(nullable = false)
    private String executorType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private int maxRetries = 3;
    private int timeout = 300;

}
