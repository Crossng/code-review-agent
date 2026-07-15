# RepoPilot Backend

Spring Boot main platform for RepoPilot.

Implemented in the first slice:

- JWT register/login/current user API.
- Project create/list/detail/file scan/index placeholder API.
- Agent task create/list/detail/run/cancel/steps API.
- PostgreSQL schema migration with Flyway.
- JPA entities for users, projects, agent tasks, runs and steps.

## Run

```bash
mvn -Dmaven.repo.local=../.m2 spring-boot:run
```

## Test

```bash
mvn -q -Dmaven.repo.local=../.m2 test
```

The backend expects PostgreSQL from the root `docker-compose.yml`.

