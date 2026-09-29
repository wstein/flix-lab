#!/usr/bin/env python3
"""Write a checkout-local Flix manifest with absolute file URLs."""

from pathlib import Path


root = Path(__file__).resolve().parent.parent
template = (root / "flix.toml.in").read_text()
manifest = template.replace("@PROJECT_ROOT_URL@", root.as_uri())
destination = root / "flix.toml"
if not destination.exists() or destination.read_text() != manifest:
    destination.write_text(manifest)
