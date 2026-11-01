"""
Main Application: Quest Pro LAN Face & Eye Tracking to Windows Virtual Webcam & Eye Mouse.

Orchestrates the UDP Receiver, Windows Eye Mouse Controller, ModernGL 3D Avatar Engine,
and Virtual Webcam output pipeline.
"""

import argparse
import sys
import time
import cv2
import numpy as np

from avatar_renderer import ModernGlAvatarRenderer
from udp_receiver import UdpTrackingReceiver
from webcam_feed import VirtualWebcamOutput


def main():
    parser = argparse.ArgumentParser(description="Quest Pro to Windows Virtual Webcam & Eye Control")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="UDP bind host (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=9005, help="UDP bind port (default: 9005)")
    parser.add_argument("--fps", type=int, default=60, help="Target framerate (default: 60)")
    parser.add_argument("--width", type=int, default=1280, help="Frame width (default: 1280)")
    parser.add_argument("--height", type=int, default=720, help="Frame height (default: 720)")
    parser.add_argument("--no-preview", action="store_true", help="Disable desktop preview GUI window")
    args = parser.parse_args()

    print("==================================================================")
    print("  Quest Pro LAN 3D Virtual Webcam System   ")
    print("==================================================================")
    print(f" * UDP Listener    : {args.host}:{args.port}")
    print(f" * Resolution      : {args.width}x{args.height} @ {args.fps} FPS")
    print(f" * Preview Window  : {'DISABLED' if args.no_preview else 'ENABLED'}")
    print("------------------------------------------------------------------")

    # 1. Initialize Subsystems
    receiver = UdpTrackingReceiver(host=args.host, port=args.port)
    if not receiver.start():
        sys.exit(1)

    renderer = ModernGlAvatarRenderer(width=args.width, height=args.height)
    webcam = VirtualWebcamOutput(width=args.width, height=args.height, fps=args.fps)

    # Telemetry tracking
    frame_count = 0
    fps_timer = time.time()
    current_fps = 0.0

    print("[Main] Pipeline running. Press 'q' or 'ESC' in the preview window or Ctrl+C to quit.\n")

    try:
        while True:
            t_start = time.time()

            # A. Poll newest Quest tracking frame from UDP
            frame_data = receiver.poll()

            # C. Render 3D Avatar
            rendered_rgba = renderer.render(
                pitch=frame_data.pose.pitch,
                yaw=frame_data.pose.yaw,
                roll=frame_data.pose.roll,
                pos_x=frame_data.pose.x,
                pos_y=frame_data.pose.y,
                pos_z=frame_data.pose.z,
                blendshapes=frame_data.blendshapes,
                gaze_x=frame_data.eyes.gaze_x,
                gaze_y=frame_data.eyes.gaze_y,
            )

            # D. Push to Virtual Webcam (Discord / OBS / Zoom)
            webcam.send(rendered_rgba)

            # Frame rate measurement
            frame_count += 1
            if time.time() - fps_timer >= 1.0:
                current_fps = frame_count / (time.time() - fps_timer)
                frame_count = 0
                fps_timer = time.time()

            # E. Optional Live Preview Window with Telemetry HUD
            if not args.no_preview:
                preview_bgr = cv2.cvtColor(rendered_rgba, cv2.COLOR_RGBA2BGR)
                _draw_telemetry_hud(preview_bgr, frame_data, receiver, current_fps, webcam)
                cv2.imshow("Quest Pro Avatar Webcam", preview_bgr)

                key = cv2.waitKey(1) & 0xFF
                if key in (ord("q"), ord("Q"), 27):  # 'q' or ESC
                    break

            # F. Frame Pacing at 60 FPS
            webcam.sleep_until_next_frame()

    except KeyboardInterrupt:
        print("\n[Main] Shutdown signal received.")
    finally:
        print("[Main] Cleaning up resources...")
        receiver.stop()
        webcam.close()
        renderer.close()
        if not args.no_preview:
            cv2.destroyAllWindows()
        print("[Main] Shutdown complete.")


def _draw_telemetry_hud(
    img: np.ndarray,
    tracking,
    receiver: UdpTrackingReceiver,
    fps: float,
    webcam: VirtualWebcamOutput,
):
    """Draws a clean semi-transparent telemetry HUD overlay onto the preview image."""
    h, w, _ = img.shape

    # Semi-transparent top HUD banner
    overlay = img.copy()
    cv2.rectangle(overlay, (0, 0), (w, 54), (16, 20, 28), -1)
    cv2.addWeighted(overlay, 0.75, img, 0.25, 0, img)

    # 1. Connection Status Pill
    is_conn = receiver.is_connected
    status_text = f"QUEST PRO: {'CONNECTED' if is_conn else 'SEARCHING LAN'}"
    status_color = (0, 230, 118) if is_conn else (75, 75, 235)  # Green or Orange-Red
    cv2.circle(img, (20, 27), 7, status_color, -1)
    cv2.putText(img, status_text, (35, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 2)

    # 2. FPS & UDP PPS
    pps_text = f"FPS: {fps:.1f} | UDP: {receiver.packets_per_sec:.0f} pps"
    cv2.putText(img, pps_text, (340, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.52, (0, 229, 255), 1)

    # 3. Webcam Driver Status
    cam_text = f"CAM: {webcam.backend_name if webcam.is_virtual_cam_active else 'LOCAL PREVIEW ONLY'}"
    cam_color = (0, 230, 118) if webcam.is_virtual_cam_active else (255, 179, 0)
    cv2.putText(img, cam_text, (620, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.50, cam_color, 1)

    # 4. Bottom HUD: Pose & Blendshapes
    jaw = tracking.blendshapes.get("jawOpen", 0.0)
    smile = tracking.blendshapes.get("mouthSmileLeft", 0.0)
    blink = (tracking.blendshapes.get("eyeBlinkLeft", 0.0) + tracking.blendshapes.get("eyeBlinkRight", 0.0)) * 0.5
    pose_str = f"Head: Yaw {tracking.pose.yaw:+.1f} deg | Pitch {tracking.pose.pitch:+.1f} deg | Jaw: {jaw:.2f} | Smile: {smile:.2f} | Blink: {blink:.2f}"
    cv2.putText(img, pose_str, (16, h - 16), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (200, 220, 240), 1)

    # 5. Eye Gaze Reticle
    gx = int(tracking.eyes.gaze_x * w)
    gy = int(tracking.eyes.gaze_y * h)
    if 0 <= gx < w and 0 <= gy < h:
        # Crosshair dot
        cv2.circle(img, (gx, gy), 5, (0, 229, 255), -1)


if __name__ == "__main__":
    main()
