"""RFC 9457 problem+json errors with stable machine-readable codes (plan 05 §10)."""
from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

TITLES = {
    "UNSUPPORTED_MEDIA_TYPE": "Not an audio file",
    "PAYLOAD_TOO_LARGE": "File is too large",
    "AUDIO_TOO_LONG": "Audio is too long",
    "AUDIO_TOO_SHORT": "Audio is too short",
    "DECODE_FAILED": "Audio could not be decoded",
    "NO_MUSIC_DETECTED": "No music detected",
    "OPTION_UNAVAILABLE": "Option not available",
    "INVALID_REQUEST": "Invalid request",
    "NOT_FOUND": "Not found",
    "NOT_READY": "Result not ready",
    "MODEL_UNAVAILABLE": "Model unavailable",
    "INTERNAL": "Internal error",
}


class ApiError(Exception):
    def __init__(self, status: int, code: str, detail: str):
        super().__init__(detail)
        self.status, self.code, self.detail = status, code, detail


def problem(status: int, code: str, detail: str, instance: str | None = None) -> JSONResponse:
    body = {"type": f"https://chordify.app/errors/{code.lower().replace('_', '-')}", "title": TITLES.get(code, code),
            "status": status, "code": code, "detail": detail}
    if instance:
        body["instance"] = instance
    return JSONResponse(body, status_code=status, media_type="application/problem+json")


def install(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(request: Request, exc: ApiError):
        return problem(exc.status, exc.code, exc.detail, request.url.path)

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError):
        first = exc.errors()[0] if exc.errors() else {}
        where = ".".join(str(p) for p in first.get("loc", []))
        return problem(422, "INVALID_REQUEST", f"{where}: {first.get('msg', 'invalid request')}", request.url.path)
