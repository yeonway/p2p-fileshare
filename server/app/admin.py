from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from fastapi.templating import Jinja2Templates

from .rooms import latest_transfers
from .security import verify_admin_credentials


router = APIRouter()
security = HTTPBasic()
templates = Jinja2Templates(directory="app/templates")


def _short_hash(value: str | None) -> str:
    if not value:
        return ""
    return f"{value[:10]}..."


@router.get("/admin/logs")
def admin_logs(request: Request, credentials: HTTPBasicCredentials = Depends(security)):
    verify_admin_credentials(credentials)
    rows = [dict(row) for row in latest_transfers()]
    for row in rows:
        row["sender_ip_hash_short"] = _short_hash(row.get("sender_ip_hash"))
        row["receiver_ip_hash_short"] = _short_hash(row.get("receiver_ip_hash"))
    return templates.TemplateResponse(request, "admin_logs.html", {"rows": rows})
