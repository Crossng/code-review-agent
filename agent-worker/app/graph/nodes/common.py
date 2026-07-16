from typing import Any, Optional


def text_preview(value: Any, limit: int) -> str:
    if value is None:
        return ""
    text = str(value).replace("\r\n", "\n")
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + "\n..."


def add_query(queries: list[str], value: Optional[str]) -> None:
    if value:
        normalized = value.strip()
        if normalized and normalized not in queries:
            queries.append(normalized)


def unique_values(values: Any) -> list[Any]:
    unique = []
    for value in values:
        if value is not None and value not in unique:
            unique.append(value)
    return unique
