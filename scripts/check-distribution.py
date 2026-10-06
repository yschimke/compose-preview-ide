#!/usr/bin/env python3
"""Check the installed artifact boundary, rather than only the compilation classpath."""
import io
import json
from pathlib import Path
import zipfile
import xml.etree.ElementTree as ET

version = json.loads(Path(".release-please-manifest.json").read_text())["."]
assert Path("version.txt").read_text().strip() == version
archives = list(Path("build/distributions").glob("*.zip"))
assert len(archives) == 1, archives
required = {
    "schemas/compose-ui-builder-document-v1.schema.json",
    "schemas/compose-ui-builder-production-v1.schema.json",
    "schemas/compose-ui-builder-mutation-v1.schema.json",
}
found = set()
descriptor = None
with zipfile.ZipFile(archives[0]) as distribution:
    for entry in distribution.namelist():
        if not entry.endswith(".jar"):
            continue
        with zipfile.ZipFile(io.BytesIO(distribution.read(entry))) as jar:
            names = set(jar.namelist())
            # The platform owns the runtime, UI, foundation, animation and native Skiko loader.
            forbidden = [name for name in names if name.startswith((
                "androidx/compose/runtime/", "androidx/compose/ui/node/",
                "androidx/compose/foundation/", "androidx/compose/animation/",
                "org/jetbrains/skiko/",
            )) and name.endswith(".class")]
            assert not forbidden, (entry, forbidden[:3])
            found |= names & required
            if "META-INF/plugin.xml" in names:
                candidate = ET.fromstring(jar.read("META-INF/plugin.xml"))
                if candidate.findtext("id") == "ee.schimke.composeai.ui-builder-poc":
                    descriptor = candidate
                    # Providers resolve their resources from the plugin's own JAR.
                    assert required <= names, (entry, required - names)
assert found == required, required - found
assert descriptor is not None, "Missing stable plugin ID"
assert descriptor.find("idea-version").attrib["since-build"] == "262.10968.63"
assert descriptor.find("idea-version").attrib["until-build"] == "262.*"
print(f"Verified {archives[0]}: plugin identity, schemas and platform-owned class exclusions")
