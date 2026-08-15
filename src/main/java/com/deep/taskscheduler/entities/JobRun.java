package com.deep.taskscheduler.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
@Entity
@Table(name = "JobRun")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class JobRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long jobId;

    private String workerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(nullable = false)
    private int attemptNumber;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

}
