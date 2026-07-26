#!/usr/bin/env python3
"""Validate ELF load and RELRO layout for Android 16 KB pages.

arm64-v8a is what ships and is the default. x86_64 is accepted only when asked for
explicitly, because it exists purely so the dev build runs on an emulator and must never
reach a release APK.
"""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path

PAGE_SIZE = 16_384
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
EM_AARCH64 = 183
EM_X86_64 = 62
ABI_MACHINES = {"arm64-v8a": EM_AARCH64, "x86_64": EM_X86_64}
DEFAULT_ABIS = ("arm64-v8a",)
ET_DYN = 3
ELF_HEADER_SIZE = 64
PROGRAM_HEADER_SIZE = 56
SECTION_HEADER_SIZE = 64


def parse_elf(
    name: str,
    data: bytes,
    allowed_machines: dict[str, int],
) -> tuple[list[str], str | None]:
    failures: list[str] = []
    if len(data) < ELF_HEADER_SIZE or data[:4] != b"\x7fELF":
        return [f"{name}: not an ELF file"], None
    if data[4] != 2 or data[5] != 1:
        return [f"{name}: expected little-endian ELF64"], None
    elf_type = struct.unpack_from("<H", data, 16)[0]
    if elf_type != ET_DYN:
        return [f"{name}: expected a position-independent ET_DYN ELF, found type {elf_type}"], None
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine not in allowed_machines.values():
        expected = ", ".join(
            f"{abi} (machine {value})" for abi, value in sorted(allowed_machines.items())
        )
        return [f"{name}: expected {expected}, found machine {machine}"], None

    phoff = struct.unpack_from("<Q", data, 32)[0]
    shoff = struct.unpack_from("<Q", data, 40)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 58)
    if phentsize < PROGRAM_HEADER_SIZE:
        return [f"{name}: invalid program header size {phentsize}"], None

    loads: list[dict[str, int]] = []
    relros: list[dict[str, int]] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if offset + PROGRAM_HEADER_SIZE > len(data):
            return [f"{name}: truncated program header table"], None
        fields = struct.unpack_from("<IIQQQQQQ", data, offset)
        segment = {
            "type": fields[0],
            "offset": fields[2],
            "vaddr": fields[3],
            "filesz": fields[5],
            "memsz": fields[6],
            "align": fields[7],
        }
        if segment["type"] == PT_LOAD:
            loads.append(segment)
        elif segment["type"] == PT_GNU_RELRO:
            relros.append(segment)

    if not loads:
        return [f"{name}: has no PT_LOAD segments"], None
    for segment in loads:
        if segment["align"] < PAGE_SIZE:
            failures.append(
                f"{name}: PT_LOAD alignment 0x{segment['align']:x} is below 0x{PAGE_SIZE:x}"
            )
        if segment["offset"] % PAGE_SIZE != segment["vaddr"] % PAGE_SIZE:
            failures.append(
                f"{name}: PT_LOAD file offset and virtual address are incongruent at 16 KB"
            )

    section_names = read_section_names(data, shoff, shentsize, shnum, shstrndx)
    if not relros:
        failures.append(f"{name}: has no GNU_RELRO segment")
    for relro in relros:
        relro_start = relro["vaddr"]
        relro_end = relro_start + relro["memsz"]
        owner = next(
            (
                load
                for load in loads
                if load["vaddr"] <= relro_start
                and relro_end <= load["vaddr"] + load["memsz"]
            ),
            None,
        )
        if owner is None:
            failures.append(f"{name}: GNU_RELRO is not contained in a PT_LOAD segment")
            continue
        load_end = owner["vaddr"] + owner["memsz"]
        if relro_end != load_end and relro_end % PAGE_SIZE != 0:
            failures.append(
                f"{name}: GNU_RELRO end 0x{relro_end:x} is not 16 KB-aligned "
                "and does not cover its PT_LOAD"
            )

    if failures:
        return failures, None
    padding = " relro-padding" if ".relro_padding" in section_names else ""
    return [], f"OK   {name}: {len(loads)} PT_LOAD, {len(relros)} GNU_RELRO{padding}"


def read_section_names(
    data: bytes,
    shoff: int,
    shentsize: int,
    shnum: int,
    shstrndx: int,
) -> set[str]:
    if not shoff or not shnum or shentsize < SECTION_HEADER_SIZE or shstrndx >= shnum:
        return set()
    headers = []
    for index in range(shnum):
        offset = shoff + index * shentsize
        if offset + SECTION_HEADER_SIZE > len(data):
            return set()
        headers.append(struct.unpack_from("<IIQQQQIIQQ", data, offset))
    strings = headers[shstrndx]
    start, size = strings[4], strings[5]
    string_table = data[start : start + size]
    names = set()
    for header in headers:
        name_offset = header[0]
        if name_offset >= len(string_table):
            continue
        end = string_table.find(b"\0", name_offset)
        if end >= 0:
            names.add(string_table[name_offset:end].decode("ascii", "replace"))
    return names


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("elf", nargs="*", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument(
        "--abis",
        default=",".join(DEFAULT_ABIS),
        help=(
            "comma-separated ABIs to accept "
            f"(known: {', '.join(sorted(ABI_MACHINES))}; default: {','.join(DEFAULT_ABIS)})"
        ),
    )
    args = parser.parse_args()
    if bool(args.apk) == bool(args.elf):
        parser.error("pass either --apk APK or one or more ELF files")

    abis = [abi.strip() for abi in args.abis.split(",") if abi.strip()]
    unknown = [abi for abi in abis if abi not in ABI_MACHINES]
    if unknown:
        parser.error(f"unknown ABI(s): {', '.join(unknown)}")
    allowed_machines = {abi: ABI_MACHINES[abi] for abi in abis}

    failures: list[str] = []
    successes: list[str] = []
    if args.apk:
        with zipfile.ZipFile(args.apk) as apk:
            native_entries = sorted(
                (
                    info
                    for info in apk.infolist()
                    if info.filename.startswith("lib/") and info.filename.endswith(".so")
                ),
                key=lambda info: info.filename,
            )
            if not native_entries:
                failures.append("APK contains no native libraries")
            for info in native_entries:
                parts = info.filename.split("/")
                if len(parts) != 3 or parts[1] not in allowed_machines:
                    failures.append(
                        f"{info.filename}: unexpected ABI, allowed: {', '.join(sorted(abis))}"
                    )
                    continue
                errors, success = parse_elf(info.filename, apk.read(info), allowed_machines)
                failures.extend(errors)
                if success:
                    successes.append(success)
    else:
        for path in args.elf:
            errors, success = parse_elf(str(path), path.read_bytes(), allowed_machines)
            failures.extend(errors)
            if success:
                successes.append(success)

    print("\n".join(successes))
    if failures:
        for message in failures:
            print(f"FAIL {message}", file=sys.stderr)
        print(
            f"16 KB compatibility check failed with {len(failures)} error(s)",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
