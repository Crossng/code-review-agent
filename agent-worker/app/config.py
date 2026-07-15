from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    backend_base_url: str = Field(
        default="http://localhost:8080",
        validation_alias=AliasChoices("REPOPILOT_BACKEND_BASE_URL", "BACKEND_BASE_URL"),
    )
    backend_callback_token: str = Field(
        default="",
        validation_alias=AliasChoices("REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN", "BACKEND_CALLBACK_TOKEN"),
    )
    backend_timeout_seconds: int = Field(
        default=10,
        validation_alias=AliasChoices("REPOPILOT_BACKEND_TIMEOUT_SECONDS", "BACKEND_TIMEOUT_SECONDS"),
    )
    mcp_server_url: str = "http://localhost:8080"
    agent_worker_port: int = 8090

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")


settings = Settings()
