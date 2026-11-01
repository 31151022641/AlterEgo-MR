"""Check the compiled native source in-process without installing a Windows camera."""
import ctypes
import mmap
import os
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parent
fixture = ROOT / "dist" / "native-test-frames.dat"
kernel = ctypes.WinDLL("kernel32", use_last_error=True)
kernel.GetTickCount64.restype = ctypes.c_ulonglong
pixels = bytearray(bytes((237, 149, 100, 255)) * (960 * 540))
pixels[(539 * 960) * 4 :] = bytes((17, 31, 53, 255)) * 960
with fixture.open("w+b") as output:
    output.truncate(64 + 1920*1080*4)
    with mmap.mmap(output.fileno(), 0, access=mmap.ACCESS_WRITE) as frame:
        frame[:16] = struct.pack("<4I", 2, 960, 540, 2)
        frame[16:24] = struct.pack("<Q", kernel.GetTickCount64())
        frame[64:64+len(pixels)] = pixels
        os.environ["ALTEREGO_MR_FRAME_FILE"] = str(fixture)
        camera = ctypes.WinDLL(str(ROOT / "dist" / "AlterEgoCamera.dll"))
        camera.TestAvatarSource.restype = ctypes.c_long
        result = camera.TestAvatarSource()
        assert result == 0, f"Native contract test failed: 0x{result & 0xffffffff:08X}"
        camera.BeginAvatarCamera.argtypes = [ctypes.c_wchar_p]
        camera.BeginAvatarCamera.restype = ctypes.c_long
        for invalid in (str(fixture), r"C:\ProgramData\AlterEgoMR\frames.dat",
                        r"C:\ProgramData\AlterEgoMR\S-1-5-18\..\frames.dat"):
            result = camera.BeginAvatarCamera(invalid)
            assert result == -2147024891, f"Unsafe frame path accepted: {invalid}"
        print("Native checks passed: QPC samples/start events, activation metadata, "
              "required services, top/bottom orientation, stop/Pause contract, "
              "private path filtering, channel token authentication, "
              "seqlock cache continuity and privacy-clear invalidation")
fixture.unlink()
