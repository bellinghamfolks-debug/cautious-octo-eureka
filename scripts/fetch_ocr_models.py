#!/usr/bin/env python3
"""Fetches the four PP-OCR ONNX models that get packaged into the APK.

The models are not committed. They are ~26 MB of binaries that would sit in every
clone of this repository forever, and they are reproducible from a pinned URL and
a pinned SHA-256, which is a stronger guarantee than "someone committed a file
called this once".

The checksums below come from RapidOCR's own release registry (the
``default_models.yaml`` shipped inside the ``rapidocr`` wheel), not from a web
page. The checksum, not the URL, is what decides whether a download is accepted,
so a mirror that serves something else fails the build instead of shipping a
model that quietly reads gibberish.

Run with --check to verify what is already on disk without downloading.
"""
from __future__ import annotations

import argparse
import hashlib
import pathlib
import sys
import urllib.error
import urllib.request

REVISION = "v3.9.2"

# Mirrors are tried in order. ModelScope is the host named in RapidOCR's own
# registry; Hugging Face carries the same release and is usually the faster of
# the two from a CI runner outside China.
MIRRORS = (
    "https://huggingface.co/RapidAI/RapidOCR/resolve/{revision}/onnx/{path}",
    "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/{revision}/onnx/{path}",
)


class Model:
    def __init__(self, local_name: str, remote_path: str, sha256: str, label: str):
        self.local_name = local_name
        self.remote_path = remote_path
        self.sha256 = sha256
        self.label = label


MODELS = (
    Model(
        "ppocr-det.onnx",
        "PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx",
        "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
        "PP-OCRv5 mobile text detection",
    ),
    Model(
        "ppocr-rec-ar.onnx",
        "PP-OCRv5/rec/arabic_PP-OCRv5_rec_mobile.onnx",
        "c1192e632d0baa9146ae5b756a0e635e3dc63c1733737ebfd1629e87144e9295",
        "PP-OCRv5 Arabic recognition",
    ),
    Model(
        "ppocr-rec-en.onnx",
        "PP-OCRv5/rec/en_PP-OCRv5_rec_mobile.onnx",
        "c3461add59bb4323ecba96a492ab75e06dda42467c9e3d0c18db5d1d21924be8",
        "PP-OCRv5 English recognition",
    ),
    Model(
        "ppocr-cls.onnx",
        "PP-OCRv4/cls/ch_ppocr_mobile_v2.0_cls_mobile.onnx",
        "e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c",
        "text line orientation classifier",
    ),
)

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "app/src/main/assets/ppocr"


def digest(path: pathlib.Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            hasher.update(block)
    return hasher.hexdigest()


def already_correct(model: Model) -> bool:
    target = ASSET_DIR / model.local_name
    return target.is_file() and digest(target) == model.sha256


def download(model: Model) -> None:
    target = ASSET_DIR / model.local_name
    partial = target.with_suffix(target.suffix + ".partial")
    failures = []

    for template in MIRRORS:
        url = template.format(revision=REVISION, path=model.remote_path)
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                with partial.open("wb") as handle:
                    while True:
                        block = response.read(1 << 20)
                        if not block:
                            break
                        handle.write(block)
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            failures.append(f"{url}: {error}")
            partial.unlink(missing_ok=True)
            continue

        actual = digest(partial)
        if actual != model.sha256:
            failures.append(f"{url}: sha256 {actual}, expected {model.sha256}")
            partial.unlink(missing_ok=True)
            continue

        partial.replace(target)
        size = target.stat().st_size
        print(f"  fetched {model.local_name} ({size:,} bytes) from {url}")
        return

    raise SystemExit(
        f"Could not obtain {model.local_name} ({model.label}).\n  "
        + "\n  ".join(failures)
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify what is on disk and exit non-zero if anything is missing",
    )
    args = parser.parse_args()

    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    missing = [model for model in MODELS if not already_correct(model)]

    if args.check:
        for model in MODELS:
            state = "ok" if model not in missing else "MISSING OR CORRUPT"
            print(f"  {model.local_name:18s} {state}")
        return 1 if missing else 0

    if not missing:
        print("All PP-OCR models present and matching their pinned checksums.")
        return 0

    for model in missing:
        print(f"Fetching {model.label}")
        download(model)
    print("All PP-OCR models present and matching their pinned checksums.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
