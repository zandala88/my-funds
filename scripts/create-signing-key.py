#!/usr/bin/env python3
"""Create a persistent personal signing identity outside the repository; never overwrite one."""
import os
from pathlib import Path
import secrets
import shutil
import subprocess

directory = Path.home() / ".local/share/my-funds"
directory.mkdir(parents=True, exist_ok=True)
directory.chmod(0o700)
key = directory / "release.jks"
properties = directory / "signing.properties"
if key.exists() or properties.exists():
    if not (key.exists() and properties.exists()):
        raise SystemExit("Signing files are incomplete. Restore your existing key; do not replace it.")
    print(f"Using existing signing identity in {directory}")
    raise SystemExit(0)
java_home = os.environ.get("JAVA_HOME")
keytool = str(Path(java_home) / "bin/keytool") if java_home else shutil.which("keytool")
cached = Path.home() / ".cache/my-funds-tools/jdk/Contents/Home/bin/keytool"
if not java_home and cached.exists():
    keytool = str(cached)
if not keytool:
    raise SystemExit("Install JDK 17 or set JAVA_HOME first.")
password = secrets.token_hex(24)
environment = os.environ.copy()
environment["MY_FUNDS_SIGNING_PASSWORD"] = password
subprocess.run([keytool, "-genkeypair", "-keystore", str(key), "-alias", "my-funds",
    "-storepass:env", "MY_FUNDS_SIGNING_PASSWORD", "-keypass:env", "MY_FUNDS_SIGNING_PASSWORD",
    "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
    "-dname", "CN=My Funds Personal, O=Personal, C=CN"], env=environment, check=True)
key.chmod(0o600)
properties.write_text(f"storeFile={key}\nstorePassword={password}\nkeyAlias=my-funds\nkeyPassword={password}\n")
properties.chmod(0o600)
print(f"Created personal signing identity in {directory}. Keep both files for future updates.")
