"""
Mock Meta Quest Pro UDP Telemetry Streamer.

Simulates 60 FPS facial tracking, 6DoF head pose, and eye gaze tracking data
streamed over UDP datagrams to test the PC Avatar Webcam and Eye Mouse application.
"""

import argparse
import json
import math
import socket
import time


def main():
    parser = argparse.ArgumentParser(description="Mock Quest Pro UDP Streamer")
    parser.add_argument("--host", type=str, default="127.0.0.1", help="Target PC host/IP (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=9005, help="Target UDP port (default: 9005)")
    parser.add_argument("--fps", type=int, default=60, help="Target transmission rate (default: 60)")
    parser.add_argument("--duration", type=float, default=0.0, help="Run duration in seconds (0 = infinite)")
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    print(f"[MockSender] Streaming synthetic Quest Pro tracking data to {args.host}:{args.port} at {args.fps} FPS")
    print("             Press Ctrl+C to stop.\n")

    seq = 0
    start_time = time.time()
    dt = 1.0 / args.fps

    try:
        while True:
            t = time.time() - start_time
            if args.duration > 0 and t >= args.duration:
                break

            seq += 1

            # 1. 6DoF Head Pose Simulation: Smooth head turns & nods
            yaw = math.sin(t * 0.8) * 16.0       # Turn left-right +/- 16 deg
            pitch = math.sin(t * 1.2) * 8.0      # Nod up-down +/- 8 deg
            roll = math.sin(t * 0.5) * 4.0       # Gentle tilt +/- 4 deg
            pos_x = math.sin(t * 0.6) * 0.05
            pos_y = math.cos(t * 0.9) * 0.03
            pos_z = -0.65

            # 2. Facial Blendshape Speech & Emotion Simulation
            # Speech syllables rhythm
            speech_cycle = (math.sin(t * 9.0) + 1.0) * 0.5
            jaw_open = speech_cycle * 0.65 if (int(t) % 6 < 4) else 0.0
            smile = (math.sin(t * 0.4) + 1.0) * 0.35
            brow_up = (math.sin(t * 1.5) + 1.0) * 0.20

            # Periodic natural blinks (every ~3.5 seconds, lasting ~150ms)
            blink_phase = (t % 3.5)
            is_blinking = blink_phase < 0.15
            blink_val = math.sin(blink_phase / 0.15 * math.pi) if is_blinking else 0.0

            # Occasional wink demonstration around 10-12 seconds
            is_right_wink = (10.0 <= (t % 18.0) <= 10.8)
            right_blink = 0.95 if is_right_wink else blink_val
            left_blink = 0.05 if is_right_wink else blink_val

            # 3. Eye Gaze Tracking Simulation
            # Smooth reading path across screen with dwell pauses
            gaze_x = 0.5 + math.sin(t * 0.5) * 0.35
            gaze_y = 0.5 + math.cos(t * 0.3) * 0.30

            # Check if dwelling near center
            dist_to_center = math.hypot(gaze_x - 0.5, gaze_y - 0.5)
            dwell_ms = min(800.0, (t % 2.5) * 1000.0) if dist_to_center < 0.15 else 0.0

            packet = {
                "timestamp": time.time(),
                "device": "Meta Quest Pro",
                "sequence": seq,
                "pose": {
                    "pos": {"x": round(pos_x, 4), "y": round(pos_y, 4), "z": round(pos_z, 4)},
                    "rot": {"pitch": round(pitch, 2), "yaw": round(yaw, 2), "roll": round(roll, 2)},
                },
                "eyes": {
                    "gazeX": round(gaze_x, 4),
                    "gazeY": round(gaze_y, 4),
                    "dwellTimeMs": round(dwell_ms, 1),
                    "isFixating": dwell_ms > 400.0,
                    "leftEyeClosed": round(left_blink, 3),
                    "rightEyeClosed": round(right_blink, 3),
                    "confidence": 0.98,
                },
                "blendshapes": {
                    "jawOpen": round(jaw_open, 3),
                    "mouthSmileLeft": round(smile, 3),
                    "mouthSmileRight": round(smile, 3),
                    "eyeBlinkLeft": round(left_blink, 3),
                    "eyeBlinkRight": round(right_blink, 3),
                    "browInnerUp": round(brow_up, 3),
                    "mouthPucker": 0.0,
                    "mouthFunnel": round(jaw_open * 0.25, 3),
                },
            }

            raw_bytes = json.dumps(packet).encode("utf-8")
            sock.sendto(raw_bytes, (args.host, args.port))

            time.sleep(dt)

    except KeyboardInterrupt:
        print("\n[MockSender] Stopped by user.")
    finally:
        sock.close()
        print(f"[MockSender] Sent {seq} packets successfully.")


if __name__ == "__main__":
    main()
