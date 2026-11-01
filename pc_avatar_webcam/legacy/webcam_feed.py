"""
Virtual Webcam Feeder for Windows.

Pushes rendered 1280x720 RGBA frames at 60 FPS to DirectShow / Media Foundation
virtual camera drivers (UnityCapture / OBS Virtual Camera) via pyvirtualcam.
Includes graceful fallback if no driver is currently registered.
"""

import time
from typing import Optional
import numpy as np
import pyvirtualcam
from pyvirtualcam import PixelFormat


class VirtualWebcamOutput:
    """
    Manages the virtual webcam device stream.
    """

    def __init__(self, width: int = 1280, height: int = 720, fps: int = 60):
        self.width = width
        self.height = height
        self.fps = fps

        self.cam: Optional[pyvirtualcam.Camera] = None
        self.backend_name: Optional[str] = None
        self.is_virtual_cam_active = False

        self._init_camera()

    def _init_camera(self):
        """Attempts to open a virtual camera with available backends."""
        backends_to_try = [
            ("unityvideo", "Unity Video (UnityCapture DirectShow)"),
            ("obs", "OBS Virtual Camera"),
        ]

        for backend, desc in backends_to_try:
            try:
                self.cam = pyvirtualcam.Camera(
                    width=self.width,
                    height=self.height,
                    fps=self.fps,
                    fmt=PixelFormat.RGBA,
                    backend=backend,
                )
                self.backend_name = desc
                self.is_virtual_cam_active = True
                print(f"[VirtualCam] Successfully streaming to: {desc} ({self.cam.device})")
                return
            except Exception:
                continue

        # If specific backends failed, try automatic default backend
        try:
            self.cam = pyvirtualcam.Camera(
                width=self.width,
                height=self.height,
                fps=self.fps,
                fmt=PixelFormat.RGBA,
            )
            self.backend_name = "Default Virtual Camera"
            self.is_virtual_cam_active = True
            print(f"[VirtualCam] Streaming to default virtual camera: {self.cam.device}")
            return
        except Exception as e:
            print("[VirtualCam] Notice: No active Windows Virtual Camera driver found.")
            print("             Run 'powershell -File pc_avatar_webcam/install_virtualcam.ps1' to register 'Unity Video'.")
            print("             Application will continue running in Local Preview Mode.")
            self.is_virtual_cam_active = False
            self.cam = None

    def send(self, frame_rgba: np.ndarray):
        """Pushes a single frame to the virtual camera device if available."""
        if self.is_virtual_cam_active and self.cam is not None:
            try:
                self.cam.send(frame_rgba)
            except Exception as e:
                print(f"[VirtualCam] Error sending frame: {e}")

    def sleep_until_next_frame(self):
        """Maintains smooth frame timing at target FPS."""
        if self.is_virtual_cam_active and self.cam is not None:
            try:
                self.cam.sleep_until_next_frame()
                return
            except Exception:
                pass
        time.sleep(1.0 / self.fps)

    def close(self):
        """Closes the virtual camera device."""
        if self.cam is not None:
            try:
                self.cam.close()
            except Exception:
                pass
            self.cam = None
            self.is_virtual_cam_active = False
            print("[VirtualCam] Camera stream closed.")
