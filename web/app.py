"""
The P.A.C.K. Web App
Flask backend: TBA API integration, job queue, heatmap serving.
Run from project root:  python web/app.py
"""

import os
import sys
import json
import glob
import sqlite3
import subprocess
import threading
import time
import shutil
from pathlib import Path

import requests
from flask import Flask, jsonify, render_template, send_file, request, abort

# ── Paths ──────────────────────────────────────────────────────────────────────
WEB_DIR     = Path(__file__).parent
ROOT        = WEB_DIR.parent
DATA_DIR    = ROOT / "data"
MATCHES_DIR = ROOT / "matches"
TEMP_DIR    = ROOT / "temp"
CAL_DIR     = ROOT / "calibrations"
DB_PATH     = WEB_DIR / "jobs.db"

for d in (DATA_DIR, MATCHES_DIR, TEMP_DIR, CAL_DIR):
    d.mkdir(parents=True, exist_ok=True)

# ── Config ─────────────────────────────────────────────────────────────────────
from dotenv import load_dotenv
_env_file = ROOT / ".env"
try:
    load_dotenv(_env_file)
except UnicodeDecodeError:
    # .env was saved as UTF-16 (common on Windows) — retry with that encoding
    load_dotenv(_env_file, encoding="utf-16")

TBA_KEY      = os.getenv("TBA_API_KEY", "")
TBA_BASE     = "https://www.thebluealliance.com/api/v3"
SEASON       = 2026
JAVA_COMPILED = False
_compile_lock = threading.Lock()

if not TBA_KEY:
    print("WARNING: TBA_API_KEY not set in .env — team lookups will fail.", file=sys.stderr)

# ── Flask app ──────────────────────────────────────────────────────────────────
app = Flask(__name__, template_folder="templates", static_folder="static")

# ── Database ───────────────────────────────────────────────────────────────────
def get_db():
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    with get_db() as db:
        db.execute("""
            CREATE TABLE IF NOT EXISTS jobs (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                match_key   TEXT NOT NULL,
                event_key   TEXT NOT NULL,
                status      TEXT NOT NULL DEFAULT 'queued',
                progress    TEXT,
                error       TEXT,
                created_at  REAL DEFAULT (unixepoch())
            )
        """)
        db.commit()

init_db()

# ── TBA helpers ────────────────────────────────────────────────────────────────
def tba_get(path):
    if not TBA_KEY:
        return None
    try:
        r = requests.get(f"{TBA_BASE}{path}", headers={"X-TBA-Auth-Key": TBA_KEY}, timeout=10)
        r.raise_for_status()
        return r.json()
    except Exception as e:
        print(f"TBA request failed ({path}): {e}", file=sys.stderr)
        return None

def youtube_key_for_match(match):
    for v in match.get("videos", []):
        if v.get("type") == "youtube":
            return v["key"]
    return None

def csv_has_data(event_key, team_num):
    """Return True if a CSV exists and has at least one data line for this team."""
    p = DATA_DIR / event_key / f"{team_num}.csv"
    return p.exists() and p.stat().st_size > 0

# ── Java compilation ───────────────────────────────────────────────────────────
def ensure_java_compiled():
    global JAVA_COMPILED
    with _compile_lock:
        if JAVA_COMPILED:
            return
        # Use glob.glob() to expand *.java — Windows cmd does not do shell glob expansion
        src_files = (
            glob.glob(str(ROOT / "src" / "*.java"))
            + glob.glob(str(ROOT / "json" / "*.java"))
        )
        result = subprocess.run(
            ["javac"] + src_files,
            cwd=str(ROOT), capture_output=True, text=True
        )
        if result.returncode != 0:
            raise RuntimeError(f"Java compilation failed:\n{result.stderr}")
        JAVA_COMPILED = True

# ── Job worker ─────────────────────────────────────────────────────────────────
_job_lock = threading.Lock()
_worker_thread = None

def update_job(job_id, status, progress=None, error=None):
    with get_db() as db:
        db.execute(
            "UPDATE jobs SET status=?, progress=?, error=? WHERE id=?",
            (status, progress, error, job_id)
        )
        db.commit()

def process_job(job):
    job_id    = job["id"]
    match_key = job["match_key"]
    event_key = job["event_key"]

    # Pull fresh match data from TBA to get video + team info
    update_job(job_id, "fetching", "Fetching match data from TBA...")
    match_data = tba_get(f"/match/{match_key}")
    if not match_data:
        update_job(job_id, "error", error="Could not fetch match data from TBA.")
        return

    yt_key = youtube_key_for_match(match_data)
    if not yt_key:
        update_job(job_id, "error", error="No YouTube video found for this match.")
        return

    red_teams  = [k.replace("frc", "") for k in match_data["alliances"]["red"]["team_keys"]]
    blue_teams = [k.replace("frc", "") for k in match_data["alliances"]["blue"]["team_keys"]]

    # Pad to exactly 3 each (shouldn't be needed for standard matches)
    while len(red_teams)  < 3: red_teams.append("no_show")
    while len(blue_teams) < 3: blue_teams.append("no_show")

    video_path = MATCHES_DIR / f"{match_key}.mp4"
    json_path  = TEMP_DIR    / f"{match_key}.json"

    try:
        # Step 1 — download video
        update_job(job_id, "downloading", "Downloading match video...")
        dl = subprocess.run(
            [
                "yt-dlp",
                "-o", str(video_path),
                "-f", "bestvideo[height=720][ext=mp4]",
                "--no-playlist",
                f"https://www.youtube.com/watch?v={yt_key}"
            ],
            cwd=str(ROOT), capture_output=True, text=True
        )
        if dl.returncode != 0 or not video_path.exists():
            update_job(job_id, "error", error=f"Video download failed:\n{dl.stderr[-500:]}")
            return

        # Step 2 — run Roboflow detector
        update_job(job_id, "detecting", "Running AI detection...")
        det = subprocess.run(
            [sys.executable, "detector.py", "--input", str(video_path), "--output", str(json_path)],
            cwd=str(ROOT), capture_output=True, text=True
        )
        if det.returncode != 0:
            update_job(job_id, "error", error=f"Detector failed:\n{det.stderr[-500:]}")
            return

        # Step 3 — compile Java (once)
        update_job(job_id, "analyzing", "Compiling analyzer...")
        ensure_java_compiled()

        # Step 4 — run Java analyzer
        update_job(job_id, "analyzing", "Analyzing robot positions...")
        cp_sep = ";" if sys.platform == "win32" else ":"
        java_args = (
            ["java", "-cp", f"src{cp_sep}json", "AIScout", event_key]
            + red_teams
            + blue_teams
            + ["--auto", "--json", str(json_path)]
        )
        ana = subprocess.run(
            java_args, cwd=str(ROOT), capture_output=True, text=True
        )
        if ana.returncode != 0:
            update_job(job_id, "error", error=f"Analyzer failed:\n{ana.stderr[-500:]}")
            return

        update_job(job_id, "done", "Complete.")

    except Exception as e:
        update_job(job_id, "error", error=str(e))
    finally:
        # Clean up temp files
        try: video_path.unlink(missing_ok=True)
        except: pass
        try: json_path.unlink(missing_ok=True)
        except: pass

def worker_loop():
    while True:
        with get_db() as db:
            row = db.execute(
                "SELECT * FROM jobs WHERE status='queued' ORDER BY created_at LIMIT 1"
            ).fetchone()
        if row:
            process_job(dict(row))
        else:
            time.sleep(2)

def start_worker():
    global _worker_thread
    if _worker_thread is None or not _worker_thread.is_alive():
        _worker_thread = threading.Thread(target=worker_loop, daemon=True)
        _worker_thread.start()

start_worker()

# ── Routes ─────────────────────────────────────────────────────────────────────
@app.route("/")
def index():
    return render_template("index.html")

@app.route("/field.png")
def field_image():
    return send_file(ROOT / "field.png")

@app.route("/api/team/<team_num>")
def get_team(team_num):
    """Return team info + 2026 matches with video/cached status."""
    team_key = f"frc{team_num}"

    team_info = tba_get(f"/team/{team_key}") or {}
    matches   = tba_get(f"/team/{team_key}/matches/{SEASON}") or []

    result = []
    for m in matches:
        yt = youtube_key_for_match(m)
        event_key = m.get("event_key", "")
        match_key = m.get("key", "")
        red_keys  = [k.replace("frc","") for k in m["alliances"]["red"]["team_keys"]]
        blue_keys = [k.replace("frc","") for k in m["alliances"]["blue"]["team_keys"]]

        # Check if already processed for this team
        cached = csv_has_data(event_key, team_num)

        # Check job status if queued/processing
        with get_db() as db:
            job = db.execute(
                "SELECT * FROM jobs WHERE match_key=? ORDER BY created_at DESC LIMIT 1",
                (match_key,)
            ).fetchone()
        job_info = dict(job) if job else None

        result.append({
            "match_key":   match_key,
            "event_key":   event_key,
            "comp_level":  m.get("comp_level"),
            "match_number": m.get("match_number"),
            "set_number":  m.get("set_number"),
            "time":        m.get("time"),
            "red_teams":   red_keys,
            "blue_teams":  blue_keys,
            "youtube_key": yt,
            "has_video":   bool(yt),
            "cached":      cached,
            "job":         job_info,
        })

    # Sort by time
    result.sort(key=lambda x: (x["event_key"], x["time"] or 0))

    return jsonify({
        "team_num":  team_num,
        "nickname":  team_info.get("nickname", ""),
        "city":      team_info.get("city", ""),
        "state":     team_info.get("state_prov", ""),
        "matches":   result,
    })

@app.route("/api/process", methods=["POST"])
def enqueue():
    """Enqueue a match for processing."""
    body = request.get_json(force=True)
    match_key = body.get("match_key", "").strip()
    if not match_key:
        return jsonify({"error": "match_key required"}), 400

    event_key = "_".join(match_key.split("_")[:-1]) if "_" in match_key else match_key

    # Don't re-enqueue if already queued/running
    with get_db() as db:
        existing = db.execute(
            "SELECT id, status FROM jobs WHERE match_key=? AND status IN ('queued','downloading','detecting','analyzing','fetching')",
            (match_key,)
        ).fetchone()
        if existing:
            return jsonify({"job_id": existing["id"], "status": existing["status"], "already_queued": True})

        cur = db.execute(
            "INSERT INTO jobs (match_key, event_key, status) VALUES (?,?,'queued')",
            (match_key, event_key)
        )
        db.commit()
        job_id = cur.lastrowid

    start_worker()
    return jsonify({"job_id": job_id, "status": "queued"})

@app.route("/api/status/<int:job_id>")
def job_status(job_id):
    with get_db() as db:
        row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
    if not row:
        return jsonify({"error": "not found"}), 404
    return jsonify(dict(row))

@app.route("/api/heatmap/<event_key>/<team_num>")
def get_heatmap(event_key, team_num):
    """Return all path data for a team at an event as JSON."""
    csv_path = DATA_DIR / event_key / f"{team_num}.csv"
    if not csv_path.exists():
        return jsonify({"error": "No data found"}), 404

    auto_pts = []
    tele_pts = []

    with open(csv_path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("---"):
                continue
            parts = line.split(",")
            if len(parts) == 3:
                is_auto = parts[0].lower() == "true"
                try:
                    x, y = float(parts[1]), float(parts[2])
                except ValueError:
                    continue
                (auto_pts if is_auto else tele_pts).append({"x": x, "y": y})

    return jsonify({"auto": auto_pts, "tele": tele_pts})

@app.route("/api/events")
def list_events():
    """List all events for which we have cached data."""
    if not DATA_DIR.exists():
        return jsonify([])
    events = [d.name for d in DATA_DIR.iterdir() if d.is_dir()]
    return jsonify(sorted(events))

@app.route("/api/calibration/<event_key>", methods=["GET"])
def get_calibration(event_key):
    """Return calibration JSON for an event (for the UI calibration editor)."""
    for name in (event_key, "default"):
        p = CAL_DIR / f"{name}.json"
        if p.exists():
            with open(p) as f:
                return jsonify(json.load(f))
    return jsonify({"error": "not found"}), 404

@app.route("/api/calibration/<event_key>", methods=["POST"])
def save_calibration(event_key):
    """Save calibration JSON for an event."""
    cal = request.get_json(force=True)
    required = {"top_left", "bottom_left", "top_right", "bottom_right", "red_on_left"}
    if not required.issubset(cal.keys()):
        return jsonify({"error": f"Missing fields: {required - cal.keys()}"}), 400
    p = CAL_DIR / f"{event_key}.json"
    with open(p, "w") as f:
        json.dump(cal, f, indent=2)
    return jsonify({"saved": str(p)})

if __name__ == "__main__":
    app.run(debug=True, host="0.0.0.0", port=5000)
