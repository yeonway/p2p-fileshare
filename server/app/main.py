from __future__ import annotations

import asyncio
import io
from contextlib import asynccontextmanager
from pathlib import Path

import segno
from fastapi import FastAPI, HTTPException, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from .admin import router as admin_router
from .cleanup import cleanup_loop
from .config import get_settings
from .db import init_db
from .models import QrSvgRequest
from .rooms import router as rooms_router
from .signaling import router as signaling_router
from .stored import router as stored_router


APP_ROOT = Path(__file__).resolve().parent


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    task = asyncio.create_task(cleanup_loop())
    try:
        yield
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass


settings = get_settings()
app = FastAPI(title="send honey where", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT"],
    allow_headers=["Content-Type", "Authorization", "X-Upload-Token", "X-Download-Token", "Range"],
    expose_headers=["Content-Length", "Content-Range", "Content-Disposition", "Accept-Ranges"],
)
app.mount("/static", StaticFiles(directory=APP_ROOT / "static"), name="static")
templates = Jinja2Templates(directory=APP_ROOT / "templates")


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; "
        "script-src 'self'; "
        "style-src 'self'; "
        "connect-src 'self' ws: wss:; "
        "img-src 'self' data:; "
        "object-src 'none'; "
        "base-uri 'self'; "
        "frame-ancestors 'none'"
    )
    return response


@app.exception_handler(Exception)
async def generic_exception_handler(request: Request, exc: Exception):
    if request.url.path.startswith("/api/") or request.url.path.startswith("/ws/"):
        return JSONResponse(status_code=500, content={"detail": "Internal server error"})
    raise exc


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/manifest.webmanifest", include_in_schema=False)
def web_manifest():
    return FileResponse(APP_ROOT / "static" / "manifest.webmanifest", media_type="application/manifest+json")


@app.get("/sw.js", include_in_schema=False)
def service_worker():
    return FileResponse(
        APP_ROOT / "static" / "sw.js",
        media_type="application/javascript",
        headers={"Service-Worker-Allowed": "/"},
    )


@app.get("/.well-known/assetlinks.json", include_in_schema=False)
def android_asset_links():
    fingerprints = get_settings().android_app_sha256_cert_fingerprints
    if not fingerprints:
        return JSONResponse(content=[])
    return JSONResponse(
        content=[
            {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                    "namespace": "android_app",
                    "package_name": "site.sexyminup.p2pfileshare",
                    "sha256_cert_fingerprints": fingerprints,
                },
            },
        ],
    )


@app.post("/api/qr/svg", include_in_schema=False)
def qr_svg(payload: QrSvgRequest):
    value = payload.value.strip()
    if not value or len(value) > 2048:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid QR value")
    out = io.BytesIO()
    qr = segno.make(value, error="m")
    qr.save(out, kind="svg", scale=5, border=2, xmldecl=False, svgns=True)
    return Response(
        content=out.getvalue().decode("utf-8"),
        media_type="image/svg+xml",
        headers={"Cache-Control": "no-store"},
    )


@app.get("/", response_class=HTMLResponse)
def index(request: Request):
    return templates.TemplateResponse(request, "index.html")


@app.get("/send", response_class=HTMLResponse)
def send_page(request: Request):
    return templates.TemplateResponse(request, "send.html")


@app.get("/receive", response_class=HTMLResponse)
def receive_page(request: Request):
    return templates.TemplateResponse(request, "receive.html")


@app.get("/p2p/send", response_class=HTMLResponse)
def p2p_send_page(request: Request):
    return templates.TemplateResponse(request, "p2p_send.html")


@app.get("/p2p/receive", response_class=HTMLResponse)
def p2p_receive_page(request: Request):
    return templates.TemplateResponse(request, "p2p_receive.html")


app.include_router(rooms_router)
app.include_router(signaling_router)
app.include_router(stored_router)
app.include_router(admin_router)
