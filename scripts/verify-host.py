"""Verify exact native bytecode callsites, without loading the host into the running player."""
import argparse
import hashlib
import re
import struct
import zipfile
from pathlib import Path


class ClassFile:
    def __init__(self, data):
        self.data = data
        self.at = 8
        self.cp = [None] * self.u2()
        i = 1
        while i < len(self.cp):
            tag = self.u1()
            if tag == 1:
                self.cp[i] = self.take(self.u2()).decode("utf-8", errors="replace")
            elif tag in (7, 8, 16, 19, 20):
                self.cp[i] = (tag, self.u2())
            elif tag in (9, 10, 11, 12, 17, 18):
                self.cp[i] = (tag, self.u2(), self.u2())
            elif tag in (3, 4):
                self.take(4)
            elif tag in (5, 6):
                self.take(8)
                i += 1
            elif tag == 15:
                self.take(3)
            else:
                raise AssertionError("Unknown constant pool tag: " + str(tag))
            i += 1
        self.take(6)
        self.take(self.u2() * 2)
        self.members(False)
        self.methods = self.members(True)

    def take(self, size):
        value = self.data[self.at:self.at + size]
        self.at += size
        return value

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack(">H", self.take(2))[0]

    def u4(self):
        return struct.unpack(">I", self.take(4))[0]

    def members(self, methods):
        result = {}
        for _ in range(self.u2()):
            self.u2()
            name, descriptor = self.cp[self.u2()], self.cp[self.u2()]
            for _ in range(self.u2()):
                attribute, size = self.cp[self.u2()], self.u4()
                raw = self.take(size)
                if methods and attribute == "Code":
                    length = struct.unpack(">I", raw[4:8])[0]
                    result[(name, descriptor)] = raw[8:8 + length]
        return result

    def method_ref(self, index):
        _, owner, named = self.cp[index]
        _, name, descriptor = self.cp[named]
        return self.cp[self.cp[owner][1]], self.cp[name], self.cp[descriptor]


p = argparse.ArgumentParser()
p.add_argument("--jar", required=True, type=Path)
args = p.parse_args()
root = Path(__file__).resolve().parents[1]
source = (root / "src/main/kotlin/dev/saltmotion/hook/CoverTarget.kt").read_text(encoding="utf-8")
profiles = [
    ("androidx/compose/ui/\u0207.class", re.search(r'const val SHA256 = "([^"]+)"', source)[1]),
    ("androidx/compose/ui/\u0b26.class", re.search(r'const val NEXT_SHA256 = "([^"]+)"', source)[1]),
]
with zipfile.ZipFile(args.jar) as host:
    candidates = [(name, host.read(name)) for name, expected in profiles
                  if name in host.namelist() and hashlib.sha256(host.read(name)).hexdigest() == expected]
assert len(candidates) == 1, "No unique verified native cover profile"
name, raw = candidates[0]
print("PASS host profile", name.encode("unicode_escape").decode())
cover = ClassFile(raw)
for symbol, offset_symbol, owner, name in (
    ("SQUARE_DESCRIPTOR", "SQUARE_BCI", "androidx/compose/ui/draw/DrawModifierKt", "drawWithCache"),
    ("CIRCLE_DESCRIPTOR", "CIRCLE_BCI", "androidx/compose/ui/graphics/GraphicsLayerModifierKt", "graphicsLayer"),
):
    offset = int(re.search(r"const val " + offset_symbol + r" = (\d+)", source)[1])
    descriptor = re.search(r'const val ' + symbol + r' = "([^"]+)"', source)[1]
    code = cover.methods[("\u037f", descriptor)]
    assert code[offset] == 0xB8, "Expected INVOKESTATIC"
    reference = cover.method_ref(struct.unpack(">H", code[offset + 1:offset + 3])[0])
    assert reference == (owner, name,
        "(Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function1;)Landroidx/compose/ui/Modifier;"), reference
    print("PASS", symbol, "BCI=" + str(offset), owner + "." + name)
print("PASS exact host class SHA-256 and both native cover Modifier callsites")
