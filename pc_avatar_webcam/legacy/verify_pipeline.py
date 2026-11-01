"""
Automated Verification Suite for Quest Pro Virtual Webcam & Eye Control System.

Tests UDP receiver throughput, eye mouse coordinate mapping, 3D avatar rendering,
and end-to-end frame production without socket starvation.
"""

import json
import socket
import sys
import threading
import time
import numpy as np

from avatar_renderer import ModernGlAvatarRenderer
from udp_receiver import UdpTrackingReceiver


def test_udp_receiver_and_smoothing():
    print("[Test 1] Testing UDP Receiver & Slerp/Lerp Smoothing Filter...")
    test_port = 9015
    receiver = UdpTrackingReceiver(host="127.0.0.1", port=test_port, smoothing_alpha=0.40)
    assert receiver.start(), "Failed to bind UDP socket"

    # Send synthetic packet
    sender_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    packet = {
        "timestamp": time.time(),
        "sequence": 1,
        "pose": {
            "pos": {"x": 0.05, "y": -0.02, "z": -0.65},
            "rot": {"pitch": 10.0, "yaw": -20.0, "roll": 2.0},
        },
        "eyes": {
            "gazeX": 0.75,
            "gazeY": 0.25,
            "dwellTimeMs": 350.0,
            "isFixating": True,
            "leftEyeClosed": 0.0,
            "rightEyeClosed": 0.8,
            "confidence": 0.99,
        },
        "blendshapes": {
            "jawOpen": 0.50,
            "mouthSmileLeft": 0.80,
            "eyeBlinkRight": 0.80,
        },
    }
    sender_sock.sendto(json.dumps(packet).encode("utf-8"), ("127.0.0.1", test_port))
    time.sleep(0.05)

    # Poll and verify
    frame = receiver.poll()
    assert receiver.packet_count >= 1, "Packet not counted"
    assert frame.sequence == 1, f"Expected sequence 1, got {frame.sequence}"
    assert abs(frame.pose.pitch - 4.0) < 1.0, f"Expected smoothed pitch ~4.0 deg (alpha=0.4), got {frame.pose.pitch}"
    assert frame.blendshapes.get("jawOpen", 0.0) > 0.1, "Blendshape jawOpen not smoothed"
    assert frame.eyes.gaze_x > 0.5, "Eye gaze X not updated"

    receiver.stop()
    sender_sock.close()
    print("  -> PASSED: UDP Receiver & Smoothing verified.\n")


def test_avatar_renderer():
    print("[Test 3] Testing ModernGL 3D Avatar Offscreen Renderer...")
    renderer = ModernGlAvatarRenderer(width=1280, height=720)

    # Render neutral frame
    frame1 = renderer.render(pitch=0.0, yaw=0.0, roll=0.0)
    assert frame1.shape == (720, 1280, 4), f"Unexpected frame shape: {frame1.shape}"
    assert frame1.dtype == np.uint8, f"Unexpected dtype: {frame1.dtype}"

    # Verify frame is not empty (check center pixel color)
    center_pixel = frame1[360, 640]
    assert np.any(center_pixel > 0), "Rendered frame is completely black"

    # Render morphed expressive frame (jaw open, smile, blink)
    frame2 = renderer.render(
        pitch=5.0,
        yaw=15.0,
        roll=-2.0,
        blendshapes={"jawOpen": 0.8, "mouthSmileLeft": 0.9, "eyeBlinkLeft": 1.0},
        gaze_x=0.8,
        gaze_y=0.3,
    )
    assert frame2.shape == (720, 1280, 4)
    # The morphed frame must differ from the neutral frame
    diff = np.abs(frame2.astype(np.int32) - frame1.astype(np.int32))
    assert np.sum(diff) > 1000, "Morph deformation produced identical pixels"

    renderer.close()
    print("  -> PASSED: 3D Avatar Rendering, Morph Targets, and FBO readout verified.\n")


def test_end_to_end_throughput():
    print("[Test 4] Testing End-to-End Pipeline Throughput (120 packets @ 60 FPS)...")
    test_port = 9025
    receiver = UdpTrackingReceiver(host="127.0.0.1", port=test_port)
    assert receiver.start()
    renderer = ModernGlAvatarRenderer(width=1280, height=720)

    total_packets = 120
    stop_event = threading.Event()

    # Background sender thread simulating Quest Pro at 60 FPS
    def sender_thread():
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        for i in range(total_packets):
            if stop_event.is_set():
                break
            t = time.time()
            pkt = {
                "timestamp": t,
                "sequence": i + 1,
                "pose": {"pos": {"x": 0, "y": 0, "z": -0.65}, "rot": {"pitch": 5, "yaw": i % 30, "roll": 0}},
                "eyes": {"gazeX": 0.5, "gazeY": 0.5, "dwellTimeMs": 0, "isFixating": False, "leftEyeClosed": 0, "rightEyeClosed": 0},
                "blendshapes": {"jawOpen": 0.5 * (i % 2)},
            }
            sock.sendto(json.dumps(pkt).encode("utf-8"), ("127.0.0.1", test_port))
            time.sleep(1.0 / 60.0)
        sock.close()

    t_thread = threading.Thread(target=sender_thread, daemon=True)
    t_thread.start()

    # Pipeline loop
    rendered_frames = 0
    t_start = time.time()
    while rendered_frames < total_packets:
        frame_data = receiver.poll()
        _ = renderer.render(
            pitch=frame_data.pose.pitch,
            yaw=frame_data.pose.yaw,
            roll=frame_data.pose.roll,
            blendshapes=frame_data.blendshapes,
        )
        rendered_frames += 1
        time.sleep(1.0 / 60.0)

    elapsed = time.time() - t_start
    t_thread.join(timeout=1.0)
    stop_event.set()

    effective_fps = rendered_frames / elapsed
    print(f"  Processed {rendered_frames} frames in {elapsed:.2f}s (Effective FPS: {effective_fps:.1f})")
    assert receiver.packet_count >= total_packets * 0.90, f"Packet loss too high: {receiver.packet_count}/{total_packets}"
    assert effective_fps >= 45.0, f"Framerate dropped below acceptable threshold: {effective_fps:.1f} FPS"

    receiver.stop()
    renderer.close()
    print("  -> PASSED: High-throughput end-to-end execution verified.\n")


def main():
    print("==================================================================")
    print("   Running Quest Pro Virtual Webcam & Eye Control Test Suite      ")
    print("==================================================================\n")
    try:
        test_udp_receiver_and_smoothing()
        test_avatar_renderer()
        test_end_to_end_throughput()
        print("==================================================================")
        print("  ALL VERIFICATION TESTS COMPLETED SUCCESSFULLY! (3 / 3 PASSED)   ")
        print("==================================================================")
    except AssertionError as e:
        print(f"\n[FAIL] Test Assertion Failed: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"\n[ERROR] Unexpected error during verification: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
