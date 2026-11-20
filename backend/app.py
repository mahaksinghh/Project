"""Store only consented static inspection metadata in private workspaces."""
import hashlib
import os
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID, uuid4

import psycopg
from fastapi import Depends, FastAPI, HTTPException, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from pydantic import BaseModel, Field, StringConstraints

SIGNALS = {
    "READ_SMS": (25, "May read text messages, including sensitive codes if Android grants access."),
    "RECEIVE_SMS": (15, "May receive incoming text messages if Android grants access."),
    "READ_CONTACTS": (15, "May read address book entries if granted."),
    "ACCESS_FINE_LOCATION": (15, "May access precise device location if granted."),
    "RECORD_AUDIO": (15, "May use the microphone if granted."),
    "CAMERA": (10, "May use the camera if granted."),
    "QUERY_ALL_PACKAGES": (15, "Declares broad visibility into installed packages."),
    "REQUEST_INSTALL_PACKAGES": (20, "Can request package installation subject to Android controls."),
    "SYSTEM_ALERT_WINDOW": (20, "Requests special access to draw over other apps."),
}
Permission = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_.]+$", max_length=255)]


class Scan(BaseModel):
    consent: Literal[True]
    package_name: Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*$", max_length=255)]
    sha256: Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
    permissions: list[Permission] = Field(max_length=512)


class Evidence(BaseModel):
    permission: str
    weight: int
    explanation: str


class Report(BaseModel):
    id: UUID
    package_name: str
    sha256: str
    score: int
    level: Literal["low", "moderate", "high"]
    evidence: list[Evidence]
    created_at: datetime


def assess(permissions: list[str]) -> tuple[int, str, list[dict]]:
    evidence = [
        {"permission": permission, "weight": weight, "explanation": explanation}
        for name, (weight, explanation) in SIGNALS.items()
        if (permission := f"android.permission.{name}") in permissions
    ]
    score = min(100, sum(item["weight"] for item in evidence))
    level = "high" if score >= 50 else "moderate" if score >= 20 else "low"
    return score, level, evidence


@contextmanager
def database():
    with psycopg.connect(os.environ["DATABASE_URL"], row_factory=dict_row, connect_timeout=5) as connection:
        yield connection


@asynccontextmanager
async def lifespan(_app):
    from starlette.concurrency import run_in_threadpool

    def initialize():
        with database() as connection:
            connection.execute("SELECT pg_advisory_xact_lock(2837110)")
            connection.execute("""
                CREATE TABLE IF NOT EXISTS reports (
                    id uuid PRIMARY KEY, workspace text NOT NULL, package_name text NOT NULL,
                    sha256 text NOT NULL, score int NOT NULL, level text NOT NULL,
                    evidence jsonb NOT NULL, created_at timestamptz DEFAULT now() NOT NULL
                )
            """)
            connection.execute("""
                CREATE INDEX IF NOT EXISTS reports_workspace_recent
                ON reports (workspace, created_at DESC, id DESC)
            """)

    await run_in_threadpool(initialize)
    yield


app = FastAPI(title="AppGuardian", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=[origin.strip() for origin in os.getenv("WEB_ORIGINS", "").split(",") if origin.strip()],
    allow_methods=["GET", "POST", "DELETE"],
    allow_headers=["Authorization", "Content-Type"],
)
bearer = HTTPBearer(auto_error=False)


def workspace(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]) -> str:
    import re

    if not credentials or not re.fullmatch(r"[0-9a-f]{64}", credentials.credentials):
        raise HTTPException(401, "A 64-character workspace key is required", headers={"WWW-Authenticate": "Bearer"})
    return hashlib.sha256(credentials.credentials.encode("ascii")).hexdigest()


Workspace = Annotated[str, Depends(workspace)]
FIELDS = "id, package_name, sha256, score, level, evidence, created_at"


@app.get("/health")
def health():
    with database() as connection:
        connection.execute("SELECT 1")
    return {"status": "ok"}


@app.post("/v1/reports", status_code=201, response_model=Report)
def create_report(scan: Scan, owner: Workspace):
    score, level, evidence = assess(scan.permissions)
    with database() as connection:
        return connection.execute(
            f"""INSERT INTO reports (id, workspace, package_name, sha256, score, level, evidence)
                VALUES (%s, %s, %s, %s, %s, %s, %s) RETURNING {FIELDS}""",
            (uuid4(), owner, scan.package_name, scan.sha256, score, level, Jsonb(evidence)),
        ).fetchone()


@app.get("/v1/reports", response_model=list[Report])
def list_reports(owner: Workspace, before: UUID | None = None):
    with database() as connection:
        if before is None:
            return connection.execute(
                f"SELECT {FIELDS} FROM reports WHERE workspace = %s ORDER BY created_at DESC, id DESC LIMIT 50",
                (owner,),
            ).fetchall()
        return connection.execute(
            f"""SELECT {FIELDS} FROM reports WHERE workspace = %s AND (created_at, id) < (
                SELECT created_at, id FROM reports WHERE workspace = %s AND id = %s
            ) ORDER BY created_at DESC, id DESC LIMIT 50""",
            (owner, owner, before),
        ).fetchall()


@app.delete("/v1/reports/{report_id}", status_code=204)
def delete_report(report_id: UUID, owner: Workspace):
    with database() as connection:
        deleted = connection.execute(
            "DELETE FROM reports WHERE id = %s AND workspace = %s", (report_id, owner),
        ).rowcount
    if not deleted:
        raise HTTPException(404, "Report not found")
    return Response(status_code=204)
