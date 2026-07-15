package com.repopilot.notification.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentStep;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.repository.AgentStepRepository;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.common.ApiException;
import com.repopilot.notification.dto.AgentEventResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class TaskStreamService {

    private static final long STREAM_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final EnumSet<AgentTaskStatus> LIVE_STATUSES = EnumSet.of(
            AgentTaskStatus.REPO_INDEXING,
            AgentTaskStatus.PLANNING,
            AgentTaskStatus.RETRIEVING_CONTEXT,
            AgentTaskStatus.GENERATING_PATCH,
            AgentTaskStatus.APPLYING_PATCH_IN_SANDBOX,
            AgentTaskStatus.RUNNING_TESTS,
            AgentTaskStatus.REPAIRING,
            AgentTaskStatus.REVIEWING_PATCH,
            AgentTaskStatus.CREATING_PULL_REQUEST
    );

    private final AgentTaskRepository agentTaskRepository;
    private final AgentStepRepository agentStepRepository;
    private final ObjectMapper objectMapper;
    private final Map<Long, CopyOnWriteArrayList<TaskSubscriber>> subscribersByTaskId = new ConcurrentHashMap<>();

    public TaskStreamService(
            AgentTaskRepository agentTaskRepository,
            AgentStepRepository agentStepRepository,
            ObjectMapper objectMapper
    ) {
        this.agentTaskRepository = agentTaskRepository;
        this.agentStepRepository = agentStepRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SseEmitter stream(Long taskId, Long userId) {
        AgentTask task = getAuthorizedTask(taskId, userId);
        boolean live = isLive(task);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        TaskSubscriber subscriber = new TaskSubscriber(emitter);
        if (live) {
            addSubscriber(taskId, subscriber);
        }
        List<AgentEventResponse> events = snapshotEvents(taskId, userId);
        boolean snapshotLive = !events.isEmpty() && isLiveStatus(events.get(0).taskStatus());
        if (!live && snapshotLive) {
            addSubscriber(taskId, subscriber);
            live = true;
        }
        AgentEventResponse completeEvent = null;
        for (AgentEventResponse event : events) {
            if (event.eventType().equals("STREAM_COMPLETE")) {
                completeEvent = event;
                continue;
            }
            send(subscriber, event);
        }
        if (!snapshotLive && completeEvent != null) {
            send(subscriber, completeEvent);
            emitter.complete();
            if (live) {
                removeSubscriber(taskId, subscriber);
            }
        }
        return emitter;
    }

    @Transactional(readOnly = true)
    public String snapshotStream(Long taskId, Long userId) {
        List<AgentEventResponse> events = snapshotEvents(taskId, userId);
        StringBuilder builder = new StringBuilder();
        for (AgentEventResponse event : events) {
            builder.append("event:").append(event.eventType().toLowerCase(Locale.ROOT)).append("\n");
            builder.append("data:").append(json(event)).append("\n\n");
        }
        return builder.toString();
    }

    public List<AgentEventResponse> snapshotEvents(Long taskId, Long userId) {
        AgentTask task = getAuthorizedTask(taskId, userId);
        List<AgentEventResponse> events = new ArrayList<>();
        AgentRun run = task.getCurrentRun();
        events.add(taskEvent("TASK_SNAPSHOT", task, run, "Task " + task.getStatus().name()));
        if (run != null) {
            agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId())
                    .forEach(step -> events.add(stepEvent("STEP_SNAPSHOT", task, run, step)));
        }
        events.add(completeEvent(task, run, "Snapshot stream complete"));
        return events;
    }

    public void publishTaskUpdated(AgentTask task, AgentRun run) {
        publish(task.getId(), taskEvent("TASK_UPDATED", task, run, "Task " + task.getStatus().name()), false);
    }

    public void publishStepRecorded(AgentTask task, AgentRun run, AgentStep step) {
        publish(task.getId(), stepEvent("STEP_RECORDED", task, run, step), false);
    }

    public void publishStreamComplete(AgentTask task, AgentRun run, String message) {
        publish(task.getId(), completeEvent(task, run, message), true);
    }

    private AgentTask getAuthorizedTask(Long taskId, Long userId) {
        AgentTask task = agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_TASK_NOT_FOUND", "Agent task not found"));
        if (!task.getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AGENT_TASK_FORBIDDEN", "Task does not belong to current user");
        }
        return task;
    }

    private boolean isLive(AgentTask task) {
        return LIVE_STATUSES.contains(task.getStatus());
    }

    private boolean isLiveStatus(String status) {
        try {
            return LIVE_STATUSES.contains(AgentTaskStatus.valueOf(status));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void addSubscriber(Long taskId, TaskSubscriber subscriber) {
        subscribersByTaskId.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(subscriber);
        Runnable cleanup = () -> removeSubscriber(taskId, subscriber);
        subscriber.emitter().onCompletion(cleanup);
        subscriber.emitter().onTimeout(cleanup);
        subscriber.emitter().onError(ignored -> cleanup.run());
    }

    private void removeSubscriber(Long taskId, TaskSubscriber subscriber) {
        CopyOnWriteArrayList<TaskSubscriber> subscribers = subscribersByTaskId.get(taskId);
        if (subscribers == null) {
            return;
        }
        subscribers.remove(subscriber);
        if (subscribers.isEmpty()) {
            subscribersByTaskId.remove(taskId, subscribers);
        }
    }

    private void publish(Long taskId, AgentEventResponse event, boolean closeAfterSend) {
        CopyOnWriteArrayList<TaskSubscriber> subscribers = subscribersByTaskId.get(taskId);
        if (subscribers == null) {
            return;
        }
        for (TaskSubscriber subscriber : subscribers) {
            boolean sent = send(subscriber, event);
            if (closeAfterSend && sent) {
                subscriber.emitter().complete();
            }
            if (!sent || closeAfterSend) {
                removeSubscriber(taskId, subscriber);
            }
        }
    }

    private boolean send(TaskSubscriber subscriber, AgentEventResponse event) {
        try {
            synchronized (subscriber) {
                subscriber.emitter().send(SseEmitter.event()
                        .name(event.eventType().toLowerCase(Locale.ROOT))
                        .data(event, MediaType.APPLICATION_JSON));
            }
            return true;
        } catch (Exception exception) {
            subscriber.emitter().completeWithError(exception);
            return false;
        }
    }

    private String json(AgentEventResponse event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "TASK_STREAM_JSON_FAILED", exception.getMessage());
        }
    }

    private AgentEventResponse taskEvent(String eventType, AgentTask task, AgentRun run, String message) {
        return new AgentEventResponse(
                eventType,
                task.getId(),
                task.getStatus().name(),
                run == null ? null : run.getId(),
                run == null ? null : run.getStatus().name(),
                null,
                null,
                null,
                message,
                Instant.now()
        );
    }

    private AgentEventResponse stepEvent(String eventType, AgentTask task, AgentRun run, AgentStep step) {
        return new AgentEventResponse(
                eventType,
                task.getId(),
                task.getStatus().name(),
                run.getId(),
                run.getStatus().name(),
                step.getId(),
                step.getStepName(),
                step.getStatus().name(),
                step.getStepName() + " " + step.getStatus().name(),
                step.getFinishedAt() == null ? step.getStartedAt() : step.getFinishedAt()
        );
    }

    private AgentEventResponse completeEvent(AgentTask task, AgentRun run, String message) {
        return new AgentEventResponse(
                "STREAM_COMPLETE",
                task.getId(),
                task.getStatus().name(),
                run == null ? null : run.getId(),
                run == null ? null : run.getStatus().name(),
                null,
                null,
                null,
                message,
                Instant.now()
        );
    }

    private record TaskSubscriber(SseEmitter emitter) {
    }
}
