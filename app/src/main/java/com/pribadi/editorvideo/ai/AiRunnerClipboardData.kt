package com.pribadi.editorvideo.ai

/**
 * Empat item clipboard berpasangan dari repo-runner-clipboards
 * agar pengguna dapat menyiapkan repo runner GitHub langsung dari dalam aplikasi.
 */
data class RunnerClipboardItem(
    val id: String,
    val pair: String,
    val isPath: Boolean,
    val label: String,
    val language: String,
    val value: String
)

object AiRunnerClipboardData {

    const val WORKFLOW_PATH = ".github/workflows/wanvideo-r3gm-test.yml"
    const val WORKER_PATH = "wanvideo_huggingspace/firebase_worker.py"

    val WORKFLOW_CODE: String = """name: Wan Video Firebase Runner

on:
  workflow_dispatch:
    inputs:
      mode:
        description: Generation mode
        type: choice
        options:
          - firebase-single
        default: firebase-single
        required: true
      firebase_url:
        description: Firebase Realtime Database base URL
        type: string
        required: true
      task_id:
        description: Firebase project push ID
        type: string
        required: true

permissions:
  contents: read

jobs:
  firebase-single:
    if: inputs.mode == 'firebase-single'
    runs-on: ubuntu-latest
    timeout-minutes: 22
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.11'
      - name: Install client
        run: python -m pip install gradio_client==2.7.2 requests pillow
      - name: Generate one Firebase task
        env:
          FIREBASE_URL: ${'$'}{{ inputs.firebase_url }}
          TASK_ID: ${'$'}{{ inputs.task_id }}
        run: python -u wanvideo_huggingspace/firebase_worker.py
"""

    val WORKER_CODE: String = """${'"'}${'"'}${'"'}Handle one public Realtime Database project (1..N sequential items); never log images or video bytes.${'"'}${'"'}${'"'}
import base64
import binascii
import json
import os
import queue
import re
import tempfile
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

import requests
from gradio_client import Client, handle_file

SPACES = {
    "r3gm/wan2-2-fp8da-aoti-preview": "r3gm",
    "kulkas2pintu/wan777": "kulkas",
    "JCisHERE/wan2-2-i2v-v3": "jcishere",
}
MAX_IMAGE = 8 * 1024 * 1024
MAX_VIDEO = 8 * 1024 * 1024
MAX_JSON = 12 * 1024 * 1024
GENERATION_TIMEOUT_SECONDS = 3 * 60


def validate_url(value):
    parsed = urlparse(value)
    host = parsed.hostname or ""
    if (parsed.scheme != "https" or parsed.username or parsed.password or parsed.port or
            parsed.path not in ("", "/") or parsed.query or parsed.fragment or
            not re.fullmatch(r"[a-z0-9][a-z0-9-]*\.(?:firebaseio\.com|[a-z0-9-]+\.firebasedatabase\.app)", host)):
        raise ValueError("URL Firebase tidak valid")
    return value.rstrip("/")


def endpoint(root, project_id, path):
    return f"{root}/ai/wanstudio/{project_id}/{path}.json"


def read_json(url, max_bytes):
    response = requests.get(url, timeout=(10, 30), stream=True, allow_redirects=False)
    response.raise_for_status()
    if response.is_redirect:
        raise ValueError("Redirect Firebase tidak didukung")
    raw = bytearray()
    for chunk in response.iter_content(65536):
        raw.extend(chunk)
        if len(raw) > max_bytes:
            raise ValueError("Data Firebase terlalu besar")
    return json.loads(raw)


def image_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", value):
        raise ValueError("ID gambar tidak valid")
    return value


def task_id_key(value):
    if not isinstance(value, str) or not re.fullmatch(r"task-[A-Za-z0-9_-]{1,32}", value):
        raise ValueError("ID item tugas tidak valid")
    return value


def write(url, data):
    response = requests.put(url, json=data, timeout=(10, 60), allow_redirects=False)
    response.raise_for_status()
    if response.is_redirect:
        raise ValueError("Redirect Firebase tidak didukung")


def decode_image(value, directory, name):
    if not isinstance(value, str) or len(value) > ((MAX_IMAGE + 2) // 3) * 4:
        raise ValueError("Gambar tidak valid atau terlalu besar")
    try:
        data = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError("Base64 gambar tidak valid") from exc
    if not data or len(data) > MAX_IMAGE:
        raise ValueError("Gambar tidak valid atau terlalu besar")
    if data.startswith(b"\xff\xd8\xff"):
        suffix = ".jpg"
    elif data.startswith(b"\x89PNG\r\n\x1a\n"):
        suffix = ".png"
    elif len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        suffix = ".webp"
    else:
        suffix = None
    if not suffix:
        raise ValueError("Hanya gambar JPEG, PNG, atau WebP didukung")
    path = Path(directory) / (name + suffix)
    path.write_bytes(data)
    if suffix == ".webp":
        try:
            from PIL import Image
            png_path = Path(directory) / (name + ".png")
            with Image.open(path) as img:
                img.convert("RGBA" if "A" in img.getbands() else "RGB").save(png_path, format="PNG")
            return handle_file(str(png_path))
        except Exception:
            pass
    return handle_file(str(path))


def run_with_timeout(fn, timeout_seconds):
    result_queue = queue.Queue(maxsize=1)

    def target():
        try:
            result_queue.put((True, fn()))
        except BaseException as exc:
            result_queue.put((False, exc))

    thread = threading.Thread(target=target, daemon=True)
    thread.start()
    try:
        ok, payload = result_queue.get(timeout=timeout_seconds)
    except queue.Empty:
        raise TimeoutError(f"Proses melewati batas waktu {timeout_seconds} detik") from None
    if not ok:
        raise payload
    return payload


def describe_error(exc):
    msg = str(exc).lower()
    if "zero" in msg and ("quota" in msg or "exceeded" in msg):
        return "quota", "Kuota ZeroGPU tidak cukup. Tunggu kuota pulih sebelum mencoba lagi."
    if "requested gpu duration" in msg and "maximum" in msg:
        return "gpu_limit", "Durasi GPU yang diminta melebihi batas Space."
    if "gpu task aborted" in msg:
        return "gpu_aborted", "Tugas GPU dibatalkan oleh Space; penyebabnya tidak diketahui."
    if isinstance(exc, ValueError):
        return "invalid_input", "Data tugas tidak valid atau melebihi batas."
    if isinstance(exc, (TimeoutError, requests.exceptions.Timeout)):
        return "timeout", "Koneksi atau proses melewati batas waktu 3 menit. Periksa run sebelum mencoba ulang."
    if isinstance(exc, requests.exceptions.RequestException):
        return "connection", "Gagal terhubung ke layanan. Periksa koneksi dan run GitHub."
    return "unknown", "Generasi gagal. Periksa status Space dan run GitHub."


def load_project_items(root, project):
    try:
        raw_items = read_json(endpoint(root, project, "items"), 256 * 1024)
        if isinstance(raw_items, dict):
            parsed = [
                (task_id_key(k), v)
                for k, v in raw_items.items()
                if isinstance(v, dict) and "space" in v
            ]
            if parsed:
                parsed.sort(key=lambda pair: (pair[1].get("order", 0) if isinstance(pair[1].get("order"), (int, float)) else 0, pair[0]))
                return parsed
    except Exception:
        pass
    single = read_json(endpoint(root, project, "items/task-1"), 32 * 1024)
    if not isinstance(single, dict):
        raise ValueError("Tugas tidak ditemukan")
    return [("task-1", single)]


def run_single_item(root, project, task_key, data, temp, image_cache, overall_running_at, workflow_wait_seconds, index, total, ok_count, fail_count, item_results=None):
    status_url = endpoint(root, project, "status")
    item_status_url = endpoint(root, project, f"items/{task_key}/status")
    result_url = endpoint(root, project, f"results/{task_key}")
    generation_started = None
    item_running_at = None
    try:
        space = data.get("space")
        prompt = data.get("prompt")
        duration = data.get("duration_seconds")
        safe = data.get("safe_mode")
        safety = data.get("enable_safety_checker")
        if space not in SPACES or not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 2000:
            raise ValueError("Space atau prompt tidak valid")
        if type(duration) not in (int, float) or duration not in (3.5, 5, 7) or type(safe) is not bool or type(safety) is not bool:
            raise ValueError("Parameter tidak valid")
        if space != "r3gm/wan2-2-fp8da-aoti-preview" and safety:
            raise ValueError("Filter ini tidak tersedia melalui API Space tersebut")

        def read_image(ref, name):
            ref_id = image_id(ref)
            if ref_id in image_cache:
                return image_cache[ref_id]
            image = read_json(endpoint(root, project, "images/" + ref_id), MAX_JSON)
            if not isinstance(image, dict):
                raise ValueError("Gambar tidak ditemukan")
            decoded = decode_image(image.get("base64"), temp, f"{name}_{ref_id}")
            image_cache[ref_id] = decoded
            return decoded

        first = read_image(data.get("input_image"), "first")
        last = read_image(data["last_image"], "last") if data.get("last_image") else None

        item_running_at = int(time.time() * 1000)
        item_wait = workflow_wait_seconds
        created_at_ms = data.get("created_at_ms")
        if item_wait is None and type(created_at_ms) in (int, float) and 0 < created_at_ms <= item_running_at + 300_000:
            item_wait = round(max(0.0, (item_running_at - created_at_ms) / 1000.0), 2)

        write(item_status_url, "running")
        running_status = {
            "state": "running",
            "running_at_ms": overall_running_at,
            "item_running_at_ms": item_running_at,
            "item_timeout_seconds": GENERATION_TIMEOUT_SECONDS,
        }
        if total > 1:
            running_status.update({
                "total_items": total,
                "current_index": index + 1,
                "current_item": task_key,
                "completed_items": ok_count,
                "failed_items": fail_count,
            })
            if item_results:
                running_status["item_results"] = dict(item_results)
        if item_wait is not None:
            running_status["workflow_wait_seconds"] = item_wait
        write(status_url, running_status)

        options = dict(input_image=first, last_image=last, prompt=prompt, duration_seconds=duration,
                       safe_mode=safe, video_component=False, api_name="/generate_video")
        if SPACES[space] == "r3gm":
            options["enable_safety_checker"] = safety
        if SPACES[space] == "jcishere":
            options.update(lora_groups=[], auto_lora_enabled=False)

        generation_started = time.monotonic()
        result = run_with_timeout(lambda: Client(space).predict(**options), GENERATION_TIMEOUT_SECONDS)
        elapsed_seconds = round(time.monotonic() - generation_started, 2)
        completed_at_ms = int(time.time() * 1000)

        if not isinstance(result, (list, tuple)) or len(result) != 3:
            raise ValueError("Format hasil Space tidak dikenal")
        video = result[1]
        if not isinstance(video, (str, os.PathLike)) or not Path(video).is_file():
            raise ValueError("File video tidak tersedia")
        path = Path(video)
        if path.stat().st_size > MAX_VIDEO:
            raise ValueError("Video melebihi batas 8 MiB")
        payload = base64.b64encode(path.read_bytes()).decode("ascii")
        write(result_url, {"mime_type": "video/mp4", "base64": payload})
        write(item_status_url, "completed")

        metrics = {"space": space, "requested_seconds": duration, "safe_mode": safe,
                   "safety_checker": safety, "elapsed_seconds": elapsed_seconds,
                   "steps": "space_default", "measure": "client_wall_seconds"}
        if item_wait is not None:
            metrics["workflow_wait_seconds"] = item_wait
        item_completed = {
            "state": "completed",
            "seed": result[2],
            "size_bytes": path.stat().st_size,
            "running_at_ms": item_running_at,
            "completed_at_ms": completed_at_ms,
            "metrics": metrics,
        }
        if item_wait is not None:
            item_completed["workflow_wait_seconds"] = item_wait
        print(f"Item {task_key} selesai; hasil tersimpan di Realtime Database.", flush=True)
        return True, item_completed
    except Exception as exc:
        print(f"Item {task_key} gagal: {type(exc).__name__}", flush=True)
        try:
            write(item_status_url, "failed")
        except Exception:
            pass
        code, message = describe_error(exc)
        failure = {"state": "failed", "error_code": code, "error": message}
        if item_running_at is not None:
            failure["running_at_ms"] = item_running_at
        if workflow_wait_seconds is not None:
            failure["workflow_wait_seconds"] = workflow_wait_seconds
        if generation_started is not None:
            failure["completed_at_ms"] = int(time.time() * 1000)
            metrics = {"space": data.get("space"), "requested_seconds": data.get("duration_seconds"),
                       "safe_mode": data.get("safe_mode"), "safety_checker": data.get("enable_safety_checker"),
                       "elapsed_seconds": round(time.monotonic() - generation_started, 2),
                       "steps": "space_default", "measure": "client_wall_seconds"}
            if workflow_wait_seconds is not None:
                metrics["workflow_wait_seconds"] = workflow_wait_seconds
            failure["metrics"] = metrics
        return False, failure


def main():
    root = validate_url(os.environ["FIREBASE_URL"])
    project = os.environ["TASK_ID"]
    if not re.fullmatch(r"[-_A-Za-z0-9]{20}", project):
        raise ValueError("ID project Firebase tidak valid")
    status_url = endpoint(root, project, "status")
    overall_running_at = int(time.time() * 1000)
    workflow_wait_seconds = None
    try:
        items = load_project_items(root, project)
        created_values = [
            data.get("created_at_ms")
            for _, data in items
            if type(data.get("created_at_ms")) in (int, float) and 0 < data.get("created_at_ms") <= overall_running_at + 300_000
        ]
        if created_values:
            workflow_wait_seconds = round(max(0.0, (overall_running_at - min(created_values)) / 1000.0), 2)

        total = len(items)
        ok_count = 0
        fail_count = 0
        item_results = {}
        last_metrics = None

        with tempfile.TemporaryDirectory() as temp:
            image_cache = {}
            for idx, (task_key, data) in enumerate(items):
                ok, item_summary = run_single_item(
                    root=root,
                    project=project,
                    task_key=task_key,
                    data=data,
                    temp=temp,
                    image_cache=image_cache,
                    overall_running_at=overall_running_at,
                    workflow_wait_seconds=workflow_wait_seconds,
                    index=idx,
                    total=total,
                    ok_count=ok_count,
                    fail_count=fail_count,
                    item_results=item_results,
                )
                item_results[task_key] = item_summary
                if "metrics" in item_summary:
                    last_metrics = item_summary["metrics"]
                if ok:
                    ok_count += 1
                else:
                    fail_count += 1
                if total > 1:
                    try:
                        write(endpoint(root, project, f"status/item_results/{task_key}"), item_summary)
                    except Exception:
                        pass

        if total == 1:
            only_summary = item_results[items[0][0]]
            write(status_url, only_summary)
            if ok_count == 0:
                raise SystemExit(1)
        else:
            overall_completed_at = int(time.time() * 1000)
            final_state = "completed" if ok_count > 0 else "failed"
            final_status = {
                "state": final_state,
                "running_at_ms": overall_running_at,
                "completed_at_ms": overall_completed_at,
                "total_items": total,
                "completed_items": ok_count,
                "failed_items": fail_count,
                "item_results": item_results,
            }
            if workflow_wait_seconds is not None:
                final_status["workflow_wait_seconds"] = workflow_wait_seconds
            if last_metrics is not None:
                final_status["metrics"] = last_metrics
            if ok_count == 0:
                first_err = next(iter(item_results.values()), {})
                final_status["error_code"] = first_err.get("error_code", "unknown")
                final_status["error"] = first_err.get("error", "Semua item gagal diproses.")
            write(status_url, final_status)
            if ok_count == 0:
                raise SystemExit(1)
        print("Tugas selesai; hasil tersimpan di Realtime Database.", flush=True)
    except SystemExit:
        raise
    except Exception as exc:
        print(f"Tugas gagal: {type(exc).__name__}", flush=True)
        try:
            code, message = describe_error(exc)
            write(status_url, {"state": "failed", "error_code": code, "error": message, "running_at_ms": overall_running_at})
        except Exception:
            print("Gagal memperbarui status Firebase.", flush=True)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
"""

    val ITEMS: List<RunnerClipboardItem> = listOf(
        RunnerClipboardItem(
            id = "workflow-path",
            pair = "workflow",
            isPath = true,
            label = "Path workflow",
            language = "TEXT",
            value = WORKFLOW_PATH
        ),
        RunnerClipboardItem(
            id = "workflow-code",
            pair = "workflow",
            isPath = false,
            label = "Kode workflow",
            language = "YAML",
            value = WORKFLOW_CODE
        ),
        RunnerClipboardItem(
            id = "worker-path",
            pair = "worker",
            isPath = true,
            label = "Path worker",
            language = "TEXT",
            value = WORKER_PATH
        ),
        RunnerClipboardItem(
            id = "worker-code",
            pair = "worker",
            isPath = false,
            label = "Kode worker",
            language = "PYTHON",
            value = WORKER_CODE
        )
    )
}
