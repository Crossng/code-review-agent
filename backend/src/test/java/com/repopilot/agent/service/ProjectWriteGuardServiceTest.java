package com.repopilot.agent.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.common.ApiException;
import com.repopilot.project.domain.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProjectWriteGuardServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AgentTaskRepository agentTaskRepository;

    private ProjectWriteGuardService projectWriteGuardService;

    @BeforeEach
    void setUp() {
        projectWriteGuardService = new ProjectWriteGuardService(projectRepository, agentTaskRepository);
    }

    @Test
    void allowsProjectWhenNoOtherWriteTaskIsActive() {
        Fixture fixture = fixture();
        when(projectRepository.findByIdForUpdate(fixture.project().getId())).thenReturn(Optional.of(fixture.project()));
        when(agentTaskRepository.existsByProjectIdAndStatusInAndIdNot(
                eq(fixture.project().getId()),
                any(),
                eq(fixture.task().getId())
        )).thenReturn(false);

        projectWriteGuardService.ensureProjectWriteSlot(fixture.task());

        verify(projectRepository).findByIdForUpdate(fixture.project().getId());
    }

    @Test
    void rejectsProjectWhenAnotherWriteTaskIsActive() {
        Fixture fixture = fixture();
        when(projectRepository.findByIdForUpdate(fixture.project().getId())).thenReturn(Optional.of(fixture.project()));
        when(agentTaskRepository.existsByProjectIdAndStatusInAndIdNot(
                eq(fixture.project().getId()),
                any(),
                eq(fixture.task().getId())
        )).thenReturn(true);

        assertThatThrownBy(() -> projectWriteGuardService.ensureProjectWriteSlot(fixture.task()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Another write task is already running");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AgentTaskStatus>> statusesCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(agentTaskRepository).existsByProjectIdAndStatusInAndIdNot(
                eq(fixture.project().getId()),
                statusesCaptor.capture(),
                eq(fixture.task().getId())
        );
        org.assertj.core.api.Assertions.assertThat(statusesCaptor.getValue())
                .contains(
                        AgentTaskStatus.GENERATING_PATCH,
                        AgentTaskStatus.RUNNING_TESTS,
                        AgentTaskStatus.REPAIRING,
                        AgentTaskStatus.REVIEWING_PATCH,
                        AgentTaskStatus.CREATING_PULL_REQUEST
                );
    }

    @Test
    void rejectsWhenLatestTaskStatusNoLongerAllowsOperationAfterProjectLock() {
        Fixture fixture = fixture();
        when(projectRepository.findByIdForUpdate(fixture.project().getId())).thenReturn(Optional.of(fixture.project()));
        when(agentTaskRepository.findStatusById(fixture.task().getId())).thenReturn(Optional.of(AgentTaskStatus.GENERATING_PATCH));

        assertThatThrownBy(() -> projectWriteGuardService.ensureProjectWriteSlot(
                fixture.task(),
                Set.of(AgentTaskStatus.WAITING_HUMAN_APPROVAL),
                "Task is not waiting for human approval"
        ))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Task is not waiting for human approval");

        verify(agentTaskRepository, never()).existsByProjectIdAndStatusInAndIdNot(any(), any(), any());
    }

    private Fixture fixture() {
        User user = new User("guard@example.test", "hash", "Guard", "USER");
        setId(user, 10L);
        Project project = new Project(user, "file:///tmp/demo", "demo", "main");
        setId(project, 20L);
        AgentTask task = new AgentTask(project, user, AgentTaskType.FEATURE, "Guarded task", "Check concurrency");
        setId(task, 30L);
        return new Fixture(project, task);
    }

    private void setId(Object target, Long id) {
        ReflectionTestUtils.setField(target, "id", id);
    }

    private record Fixture(Project project, AgentTask task) {
    }
}
