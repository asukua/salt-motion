"""Check distribution metadata and reject bundled host libraries."""
import argparse
import json
import io
import re
import zipfile
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("package", type=Path)
args = p.parse_args()
project = Path(__file__).resolve().parents[1]
version = re.search(r'version = "([^"]+)"', (project / "build.gradle.kts").read_text(encoding="utf-8"))[1]
with zipfile.ZipFile(args.package) as package:
    names = set(package.namelist())
    manifest = package.read("META-INF/MANIFEST.MF")
    assert manifest == package.read("classes/META-INF/MANIFEST.MF"), "Descriptor copies differ"
    unfolded = manifest.replace(b"\r\n ", b"").replace(b"\n ", b"").decode("utf-8")
    headers = dict(line.split(":", 1) for line in unfolded.splitlines() if ":" in line)
    headers = {key: value.strip() for key, value in headers.items()}
    for key, value in {"Plugin-Id": "dev.saltmotion", "Plugin-Version": version,
                       "Plugin-Class": "dev.saltmotion.SaltMotionPlugin", "Plugin-Name": "Salt Motion"}.items():
        assert headers.get(key) == value, (key, headers.get(key), value)
    permission_line = re.search(r"^Plugin-Permissions:\s*([^\r\n]*)", unfolded, re.MULTILINE)
    permissions = set(re.split(r"[,;\s]+", permission_line[1].strip())) if permission_line else set()
    assert permissions == {"class-transform", "library-read", "library-write"}, "Wrong or missing plugin permissions: " + repr(permissions)
    assert "classes/dev/saltmotion/SaltMotionPlugin.class" in names
    assert "classes/dev/saltmotion/artwork/SoftwareRenderFrames.class" in names
    assert "classes/dev/saltmotion/artwork/MotionVideoCache.class" in names
    for required in ("editor/HostUiBridge", "editor/EditorHooks", "editor/CoverStudioUiKt", "editor/CoverSearch", "editor/CoverStore", "editor/CoverTagWriter"):
        assert "classes/dev/saltmotion/" + required + ".class" in names, required
    assert "classes/dev/saltmotion/artwork/HostGpuVideo.class" not in names, "GPU experiment must not enter the normal package"
    assert b"readImage" in package.read("classes/dev/saltmotion/artwork/SoftwareRenderFrames.class")
    assert "classes/dev/saltmotion/artwork/SoftwareVideoFrames.class" not in names
    preferences = json.loads(package.read("classes/preference_config.json"))
    assert preferences["configs"][0]["config"] == "motion.json"
    frame_rate = next(item for item in preferences["configs"][0]["preferences"] if item["key"] == "frame_rate")
    assert frame_rate["entry_values"] == ["24", "30"] and frame_rate["default_value"] == "30", frame_rate
    assert not any(name.startswith("classes/dev/saltmotion30/") for name in names), "Temporary fork namespace must not ship"
    assert not any(name.lower().endswith((".ttf", ".otf", ".ttc")) for name in names), "Use the host's selected font, never bundle a second copy"
    libraries = {Path(name).name: name for name in names if name.startswith("lib/") and name.endswith(".jar")}
    assert set(libraries) == {"okhttp-4.12.0.jar", "okio-jvm-3.9.1.jar"}, libraries
    for name, archive in libraries.items():
        with zipfile.ZipFile(io.BytesIO(package.read(archive))) as library:
            for entry in library.namelist():
                if entry.endswith(".class"):
                    assert b"java/net/http/" not in library.read(entry), "Optional JDK HTTP dependency: " + name
                    assert b"jdk/net/" not in library.read(entry), "Optional JDK network dependency: " + name
    for prefix in ("kotlin/", "androidx/", "com/xuncorp/", "com/sun/jna/", "org/jetbrains/", "org/junit/"):
        assert not any(name.startswith("classes/" + prefix) for name in names), prefix
    assert "LICENSE" in names and "NOTICE" in names
    for name in names:
        if name.startswith("classes/") and name.endswith(".class"):
            assert b"java/net/http/" not in package.read(name), "Optional JDK HTTP dependency: " + name
print("PASS descriptors and required cover/metadata permissions, HTTP dependency jars/licenses, no optional JDK HTTP or bundled host/test libraries")
