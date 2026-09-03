package com.deep.taskscheduler.Component;

public interface JobExecutor {
    String key();
    void execute(String payloadJson) throws Exception;
}
