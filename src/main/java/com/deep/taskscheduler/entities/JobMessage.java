package com.deep.taskscheduler.entities;

import java.io.Serializable;

public record JobMessage(Long jobId , int attemptNumber) implements Serializable {
}
