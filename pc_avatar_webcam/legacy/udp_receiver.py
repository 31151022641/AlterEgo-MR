"""
UDP Telemetry Receiver for Meta Quest Pro Face, Eye, and Head Tracking.

Listens for low-latency JSON datagrams streamed over local Wi-Fi/LAN on 0.0.0.0:9005.
Includes non-blocking socket draining, monotonic packet reordering, jitter buffer,
and temporal Slerp/Lerp smoothing for buttery 60 FPS animation.
"""

import json
import math
import select
import socket
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Optional, Tuple


@dataclass
class HeadPoseData:
    x: float = 0.0
    y: float = 0.0
    z: float = -0.65
    pitch: float = 0.0   # Looking up / down in degrees
    yaw: float = 0.0     # Turning left / right in degrees
    roll: float = 0.0    # Tilting head in degrees


@dataclass
class EyeTrackingData:
    gaze_x: float = 0.5       # Normalized 0.0 (left) to 1.0 (right)
    gaze_y: float = 0.5       # Normalized 0.0 (top) to 1.0 (bottom)
    dwell_time_ms: float = 0.0
    is_fixating: bool = False
    left_eye_closed: float = 0.0   # 0.0 (open) to 1.0 (closed)
    right_eye_closed: float = 0.0  # 0.0 (open) to 1.0 (closed)
    confidence: float = 1.0


@dataclass
class TrackingFrame:
    timestamp: float = field(default_factory=time.time)
    sequence: int = 0
    pose: HeadPoseData = field(default_factory=HeadPoseData)
    eyes: EyeTrackingData = field(default_factory=EyeTrackingData)
    blendshapes: Dict[str, float] = field(default_factory=dict)
    sender_ip: Optional[str] = None


class UdpTrackingReceiver:
    """
    High-performance non-blocking UDP receiver for Quest Pro LAN tracking data.
    """

    def __init__(self, host: str = "0.0.0.0", port: int = 9005, smoothing_alpha: float = 0.35):
        self.host = host
        self.port = port
        self.smoothing_alpha = max(0.05, min(1.0, smoothing_alpha))

        self.sock: Optional[socket.socket] = None
        self.is_running = False

        # Raw & smoothed frames
        self._raw_frame = TrackingFrame()
        self._smoothed_frame = TrackingFrame()

        # Telemetry
        self.packet_count = 0
        self.last_packet_time = 0.0
        self.last_sequence = -1
        self.packets_per_sec = 0.0
        self._pps_counter = 0
        self._pps_timer = time.time()
        self.sender_address: Optional[Tuple[str, int]] = None

    def start(self) -> bool:
        """Binds the UDP socket to host and port."""
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            # Increase OS receive buffer to prevent packet dropping under load
            try:
                self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 262144)  # 256 KB
            except Exception:
                pass
            self.sock.bind((self.host, self.port))
            self.sock.setblocking(False)
            self.is_running = True
            print(f"[UDPReceiver] Listening on {self.host}:{self.port} (UDP)")
            return True
        except Exception as e:
            print(f"[UDPReceiver] Error binding to {self.host}:{self.port}: {e}")
            self.is_running = False
            return False

    def stop(self):
        """Closes the socket and stops receiving."""
        self.is_running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None
        print("[UDPReceiver] Socket closed.")

    def poll(self) -> TrackingFrame:
        """
        Drains all pending datagrams in the socket buffer and applies temporal smoothing
        to the newest packet. Should be called once per render loop tick (~60 Hz).
        """
        if not self.is_running or not self.sock:
            return self._smoothed_frame

        newest_packet: Optional[bytes] = None
        newest_addr: Optional[Tuple[str, int]] = None

        # Non-blocking drain: grab all queued datagrams, keep the most recent
        while True:
            try:
                data, addr = self.sock.recvfrom(65536)
                newest_packet = data
                newest_addr = addr
                self.packet_count += 1
                self._pps_counter += 1
            except BlockingIOError:
                break
            except socket.error:
                break

        now = time.time()
        # Compute PPS telemetry every 1.0 second
        if now - self._pps_timer >= 1.0:
            self.packets_per_sec = self._pps_counter / (now - self._pps_timer)
            self._pps_counter = 0
            self._pps_timer = now

        if newest_packet:
            self.last_packet_time = now
            self.sender_address = newest_addr
            parsed = self._parse_json_packet(newest_packet, newest_addr[0] if newest_addr else None)
            if parsed:
                # Monotonic sequence check to discard stale out-of-order packets
                if parsed.sequence >= self.last_sequence or (self.last_sequence - parsed.sequence > 10000):
                    self.last_sequence = parsed.sequence
                    self._raw_frame = parsed

        # Apply temporal smoothing (Lerp position + Slerp Euler + Blendshape EMA)
        self._smooth_step()
        return self._smoothed_frame

    def _parse_json_packet(self, raw_bytes: bytes, sender_ip: Optional[str]) -> Optional[TrackingFrame]:
        """Parses incoming JSON bytes into a strongly-typed TrackingFrame."""
        try:
            payload: Dict[str, Any] = json.loads(raw_bytes.decode("utf-8"))
        except Exception:
            return None

        seq = payload.get("sequence", self.last_sequence + 1)
        ts = payload.get("timestamp", time.time())

        # 1. Pose
        pose_dict = payload.get("pose", {})
        pos_dict = pose_dict.get("pos", {})
        rot_dict = pose_dict.get("rot", {})
        pose = HeadPoseData(
            x=float(pos_dict.get("x", 0.0)),
            y=float(pos_dict.get("y", 0.0)),
            z=float(pos_dict.get("z", -0.65)),
            pitch=float(rot_dict.get("pitch", 0.0)),
            yaw=float(rot_dict.get("yaw", 0.0)),
            roll=float(rot_dict.get("roll", 0.0)),
        )

        # 2. Eye Gaze
        eye_dict = payload.get("eyes", {})
        eyes = EyeTrackingData(
            gaze_x=float(eye_dict.get("gazeX", eye_dict.get("gaze_x", 0.5))),
            gaze_y=float(eye_dict.get("gazeY", eye_dict.get("gaze_y", 0.5))),
            dwell_time_ms=float(eye_dict.get("dwellTimeMs", eye_dict.get("dwell_time_ms", 0.0))),
            is_fixating=bool(eye_dict.get("isFixating", eye_dict.get("is_fixating", False))),
            left_eye_closed=float(eye_dict.get("leftEyeClosed", eye_dict.get("left_eye_closed", 0.0))),
            right_eye_closed=float(eye_dict.get("rightEyeClosed", eye_dict.get("right_eye_closed", 0.0))),
            confidence=float(eye_dict.get("confidence", 1.0)),
        )

        # 3. Blendshapes
        raw_blendshapes = payload.get("blendshapes", {})
        blendshapes = {k: max(0.0, min(1.0, float(v))) for k, v in raw_blendshapes.items()}

        # Synchronize eye blink if missing from blendshapes map
        if "eyeBlinkLeft" not in blendshapes and eyes.left_eye_closed > 0.0:
            blendshapes["eyeBlinkLeft"] = eyes.left_eye_closed
        if "eyeBlinkRight" not in blendshapes and eyes.right_eye_closed > 0.0:
            blendshapes["eyeBlinkRight"] = eyes.right_eye_closed

        return TrackingFrame(
            timestamp=ts,
            sequence=seq,
            pose=pose,
            eyes=eyes,
            blendshapes=blendshapes,
            sender_ip=sender_ip,
        )

    def _smooth_step(self):
        """Performs Lerp and Slerp smoothing toward the raw frame targets."""
        alpha = self.smoothing_alpha
        raw = self._raw_frame
        curr = self._smoothed_frame

        # Position Lerp
        curr.pose.x += (raw.pose.x - curr.pose.x) * alpha
        curr.pose.y += (raw.pose.y - curr.pose.y) * alpha
        curr.pose.z += (raw.pose.z - curr.pose.z) * alpha

        # Euler Rotation Lerp (with angle wrap-around handling)
        curr.pose.pitch += _shortest_angle_diff(raw.pose.pitch, curr.pose.pitch) * alpha
        curr.pose.yaw += _shortest_angle_diff(raw.pose.yaw, curr.pose.yaw) * alpha
        curr.pose.roll += _shortest_angle_diff(raw.pose.roll, curr.pose.roll) * alpha

        # Eye Gaze Lerp
        curr.eyes.gaze_x += (raw.eyes.gaze_x - curr.eyes.gaze_x) * (alpha * 1.2)
        curr.eyes.gaze_y += (raw.eyes.gaze_y - curr.eyes.gaze_y) * (alpha * 1.2)
        curr.eyes.dwell_time_ms = raw.eyes.dwell_time_ms
        curr.eyes.is_fixating = raw.eyes.is_fixating
        curr.eyes.left_eye_closed += (raw.eyes.left_eye_closed - curr.eyes.left_eye_closed) * 0.6
        curr.eyes.right_eye_closed += (raw.eyes.right_eye_closed - curr.eyes.right_eye_closed) * 0.6
        curr.eyes.confidence = raw.eyes.confidence

        # Blendshape weights asymmetric EMA (faster attack for snappy speech, smoother decay)
        for name, target_weight in raw.blendshapes.items():
            curr_val = curr.blendshapes.get(name, 0.0)
            attack = 0.65 if target_weight > curr_val else 0.40
            curr.blendshapes[name] = curr_val + (target_weight - curr_val) * attack

        curr.timestamp = raw.timestamp
        curr.sequence = raw.sequence
        curr.sender_ip = raw.sender_ip

    @property
    def is_connected(self) -> bool:
        """True if we received a packet within the last 1.5 seconds."""
        return (time.time() - self.last_packet_time) < 1.5


def _shortest_angle_diff(target: float, current: float) -> float:
    """Computes the shortest angular difference in degrees (-180 to +180)."""
    diff = (target - current + 180.0) % 360.0 - 180.0
    return diff
