"""Check native media behavior and encrypted LAN reception without installing a camera.

All video is synthetic. Tests use loopback addresses and the EXE's isolated test
state; no user avatar, camera registration or firewall settings are involved.
"""
from contextlib import closing
import hashlib
import hmac
import io
import pathlib
import queue
import re
import socket
import ssl
import struct
import subprocess
import threading
import time

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent
EXE = ROOT / 'dist' / 'AlterEgoWebcam.exe'
FRAMES = ROOT / 'dist' / 'frames.dat'
OPTIONS = {'creationflags': subprocess.CREATE_NO_WINDOW} if hasattr(subprocess, 'CREATE_NO_WINDOW') else {}
PROTOCOL = b'AEMR0003'


def jpeg(width=960, height=540, color=(40, 160, 100)):
    buffer = io.BytesIO()
    Image.new('RGB', (width, height), color).save(buffer, 'JPEG', quality=92)
    return buffer.getvalue()


def snapshot():
    try:
        with FRAMES.open('rb') as frame:
            first = frame.read(24)
            frame.seek(64)
            pixel = frame.read(4)
            frame.seek(0)
            second = frame.read(24)
        if len(first) != 24 or first != second or struct.unpack_from('<I', first, 12)[0] & 1:
            return None
        return struct.unpack_from('<Q', first, 16)[0], pixel, struct.unpack_from('<I', first, 12)[0]
    except (FileNotFoundError, PermissionError):
        return None


def wait_state(predicate, message, timeout=3):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        current = snapshot()
        if current is not None and predicate(current):
            return current
        time.sleep(.015)
    raise AssertionError(f'{message}; last shared-frame state={snapshot()}')


def frame_packet(sequence, capture, image=None, length=None):
    image = jpeg() if image is None else image
    return struct.pack('>qqi', sequence, capture, len(image) if length is None else length) + image


def assert_closed(client, message):
    # This must be faster than the receiver's three-second idle timeout: otherwise
    # accepted bad packets could appear rejected merely because no next frame came.
    client.settimeout(1.5)
    try:
        received = client.recv(1)
        assert received == b'', f'{message}: received {received!r}'
    except (ConnectionResetError, ConnectionAbortedError, ssl.SSLError):
        pass


class Receiver:
    def __init__(self, mode='--receiver-test'):
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            self.port = reservation.getsockname()[1]
        self.lines = []
        self.pending = queue.Queue()
        self.process = subprocess.Popen([str(EXE), mode, str(self.port), '10000'], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        text=True, **OPTIONS)

        def output():
            for line in self.process.stdout:
                self.lines.append(line.rstrip())
                self.pending.put(line.rstrip())
        self.output_thread = threading.Thread(target=output, daemon=True)
        self.output_thread.start()
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            try:
                line = self.pending.get(timeout=max(.01, deadline - time.monotonic()))
            except queue.Empty:
                break
            ready = re.fullmatch(r'READY (\d{6}) ([0-9A-Fa-f]{64})', line)
            if ready:
                self.pin, self.fingerprint = ready.groups()
                return
            if self.process.poll() is not None:
                break
        self.close()
        raise AssertionError('Receiver did not become ready: ' + '\n'.join(self.lines))

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
        self.process.wait(timeout=5)
        self.output_thread.join(timeout=2)
        self.process.stdout.close()

    def tls(self, fingerprint=None, source='127.0.0.1'):
        connection = socket.create_connection(('127.0.0.1', self.port), timeout=4,
                                              source_address=(source, 0))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_2
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        try:
            secured = context.wrap_socket(connection, server_hostname='alterego.local')
            actual = hashlib.sha256(secured.getpeercert(binary_form=True)).hexdigest()
            expected = self.fingerprint if fingerprint is None else fingerprint
            if not hmac.compare_digest(actual.lower(), expected.lower()):
                secured.close()
                raise ValueError('Receiver certificate fingerprint mismatch')
            assert secured.version() == 'TLSv1.2', secured.version()
            return secured
        except BaseException:
            connection.close()
            raise

    def paired(self, source='127.0.0.1'):
        deadline = time.monotonic() + 4
        while True:
            secured = self.tls(source=source)
            secured.sendall(PROTOCOL + self.pin.encode('ascii'))
            result = secured.recv(1)
            if result == b'\1':
                return secured
            secured.close()
            if result != b'\2' or time.monotonic() >= deadline:
                raise AssertionError(f'Correct encrypted pairing was rejected: {result!r}')
            time.sleep(.03)  # Allow the preceding owner's disconnect cleanup to finish.


def check_authentication(receiver):
    with socket.create_connection(('127.0.0.1', receiver.port), timeout=4) as plain:
        plain.sendall(b'AEMR0001' + receiver.pin.encode('ascii'))
        try:
            assert plain.recv(1) != b'\1', 'Plaintext pairing was accepted'
        except (ConnectionResetError, ConnectionAbortedError):
            pass
    for old_protocol, source in ((b'AEMR0001', '127.0.0.1'), (b'AEMR0002', '127.0.0.5')):
        with closing(receiver.tls(source=source)) as wrong_protocol:
            wrong_protocol.sendall(old_protocol + receiver.pin.encode('ascii'))
            assert wrong_protocol.recv(1) == b'\0', 'Old protocol was accepted over TLS'
    wrong_pin = f'{(int(receiver.pin) + 1) % 1_000_000:06d}'
    with closing(receiver.tls()) as wrong:
        wrong.sendall(PROTOCOL + wrong_pin.encode('ascii'))
        assert wrong.recv(1) == b'\0', 'Incorrect pairing code was accepted'
    try:
        receiver.tls(fingerprint='00' * 32)
    except ValueError:
        pass
    else:
        raise AssertionError('A different full certificate fingerprint was accepted')
    wait_state(lambda state: state[0] == 0, 'Unauthenticated traffic published a frame')
    print('TLS checks passed: plaintext/old-protocol rejection, wrong PIN, full fingerprint pinning')


def check_invalid_frames(receiver):
    cases = [
        ('negative frame size', frame_packet(1, 1000, image=b'', length=-1)),
        ('oversized frame size', frame_packet(1, 1000, image=b'', length=3 * 1024 * 1024 + 1)),
        ('negative capture timestamp', frame_packet(1, -1)),
        ('negative sequence', frame_packet(-1, 1000)),
        ('unexpected dimensions', frame_packet(1, 1000, jpeg(32, 32))),
        ('non-JPEG payload', frame_packet(1, 1000, b'not an image')),
        ('malformed JPEG', frame_packet(1, 1000, b'\xff\xd8\x00\x01invalid')),
    ]
    for description, packet in cases:
        with closing(receiver.paired()) as client:
            client.sendall(packet)
            assert_closed(client, 'Receiver retained ' + description)
        wait_state(lambda state: state[0] == 0, description + ' retained personal pixels')
    with closing(receiver.paired()) as client:
        maximum_jpeg = jpeg()
        maximum_jpeg += bytes(3 * 1024 * 1024 - len(maximum_jpeg))
        client.sendall(frame_packet(0, 1000, maximum_jpeg))
        wait_state(lambda state: state[0] > 0, 'Initial Android sequence zero or exact three-MiB bound was rejected')
        client.sendall(frame_packet(0, 1001))
        assert_closed(client, 'Receiver retained duplicate initial sequence zero')
    wait_state(lambda state: state[0] == 0, 'Duplicate initial sequence did not clear video')
    for description, sequence, capture in [('duplicate sequence', 1, 1001),
                                           ('backward sequence', 0, 1001),
                                           ('backward capture clock', 2, 999)]:
        with closing(receiver.paired()) as client:
            client.sendall(frame_packet(1, 1000))
            wait_state(lambda state: state[0] > 0, 'Valid first frame did not arrive')
            client.sendall(frame_packet(sequence, capture))
            assert_closed(client, 'Receiver retained ' + description)
        wait_state(lambda state: state[0] == 0, description + ' did not clear video')
    with closing(receiver.paired()) as client:
        client.sendall(frame_packet(0, 1000))
        first = wait_state(lambda state: state[0] > 0, 'First capture-clock fixture did not arrive')
        client.sendall(frame_packet(1, 1001))
        wait_state(lambda state: state[2] > first[2], 'Second capture-clock fixture did not arrive')
        client.sendall(frame_packet(2, 1000))
        assert_closed(client, 'Capture time regressed after a newer accepted capture')
    wait_state(lambda state: state[0] == 0, 'Regressing capture clock did not clear video')
    print('Frame validation passed: initial sequence zero, exact three-MiB bound, JPEG/dimensions, timestamps and sequence ordering')


def check_owner_protection(receiver):
    with closing(receiver.paired()) as owner:
        started = int(time.monotonic() * 1000)
        owner.sendall(frame_packet(1, started))
        wait_state(lambda state: state[0] > 0, 'Owner video did not arrive')
        before = snapshot()
        # An idle TLS/handshake client must not block the accepting owner or clear it.
        with socket.create_connection(('127.0.0.1', receiver.port), timeout=2,
                                      source_address=('127.0.0.2', 0)):
            with closing(receiver.tls(source='127.0.0.3')) as intruder:
                bad_pin = f'{(int(receiver.pin) + 1) % 1_000_000:06d}'
                intruder.sendall(PROTOCOL + bad_pin.encode('ascii'))
                assert intruder.recv(1) == b'\0'
            with closing(receiver.tls(source='127.0.0.4')) as contender:
                contender.sendall(PROTOCOL + receiver.pin.encode('ascii'))
                assert contender.recv(1) == b'\2', 'Second client took an active owner'
            owner.sendall(frame_packet(2, int(time.monotonic() * 1000), jpeg(color=(180, 60, 30))))
            wait_state(lambda state: state[0] >= before[0] and state[1][2] > 150,
                       'Unpaired clients interrupted or cleared owner video')
    wait_state(lambda state: state[0] == 0, 'Owner disconnect retained video')
    print('Active-owner checks passed: concurrent idle/wrong-PIN/paired clients cannot replace or clear video')


def check_freshness_and_sustained_video(receiver):
    with closing(receiver.paired()) as client:
        base = int(time.monotonic() * 1000)
        client.sendall(frame_packet(1, base))
        previous = wait_state(lambda state: state[0] > 0, 'First live frame did not publish')
        time.sleep(1.6)
        client.sendall(frame_packet(2, base + 1))
        assert_retained(previous, 'A delayed packet blanked a still-fresh accepted image', duration=.08)
        client.sendall(frame_packet(3, base + 6000))
        assert_retained(previous, 'A future-clock packet blanked a still-fresh accepted image', duration=.08)
        # Recovery on the same authenticated connection uses actual elapsed capture time.
        for sequence in range(4, 49):
            before_sequence = snapshot()[2]
            client.sendall(frame_packet(sequence, int(time.monotonic() * 1000), jpeg(color=(40, 160, 100))))
            wait_state(lambda state: state[0] > 0 and state[1][1] > 130 and state[2] > before_sequence,
                       'Continuous video failed to recover from stale input')
            time.sleep(.025)
        assert snapshot()[0] > 0, 'Sustained receiver stopped publishing'
    wait_state(lambda state: state[0] == 0 and state[1] == bytes((35, 23, 15, 255)),
               'Disconnect did not clear shared video')
    print('Freshness checks passed: delayed/future packets preserve recent video, recovery, 45 sustained frames, disconnect clearing')


def assert_retained(reference, message, duration=.12):
    deadline = time.monotonic() + duration
    checked = 0
    while time.monotonic() < deadline:
        state = snapshot()
        if state is not None:
            checked += 1
            assert state == reference, f'{message}: {reference!r} became {state!r}'
        time.sleep(.008)
    assert checked, message + ': shared frame header never became stable'


def publish(client, sequence, capture, message, color=(40, 160, 100)):
    prior = wait_state(lambda state: True, 'Shared frame header remained unstable')
    client.sendall(frame_packet(sequence, capture, jpeg(color=color)))
    return wait_state(lambda state: state[0] > 0 and state[2] > prior[2]
                      and all(abs(state[1][channel] - color[2 - channel]) <= 5 for channel in range(3)),
                      message)


def check_latency_improvement_jitter_and_drift(receiver):
    with closing(receiver.paired()) as client:
        beginning = time.monotonic()
        base = int(beginning * 1000)
        first = publish(client, 0, base, 'First latency-calibration frame did not arrive')
        time.sleep(.05)
        # Capture advance is 450 ms greater than arrival advance: the first image
        # was delayed 500 ms and the next only 50 ms. Both clocks remain monotonic.
        lead = 450
        capture = base + int((time.monotonic() - beginning) * 1000) + lead
        improved = publish(client, 1, capture, 'Lower network latency permanently marked later frames as future',
                           color=(180, 60, 30))
        assert improved[0] > 0 and improved[2] > first[2]
        # Accelerate a small accumulating clock offset for a bounded regression
        # fixture, while alternating network delays. Every capture stays forward.
        last_capture = capture
        for sequence in range(2, 22):
            time.sleep(.07)
            network_delay = (20, 0, 35, 5)[sequence % 4]
            drift = (sequence - 1) * 8
            capture = base + int((time.monotonic() - beginning) * 1000) + lead + drift - network_delay
            assert capture > last_capture
            publish(client, sequence, capture, 'Clock drift/jitter stopped fresh publications')
            last_capture = capture
        latest = snapshot()
        time.sleep(1.6)
        for sequence in range(22, 25):
            client.sendall(frame_packet(sequence, last_capture + sequence - 21))
        assert_retained(latest, 'Late packet burst blanked recent video', duration=.12)
        capture = base + int((time.monotonic() - beginning) * 1000) + lead + 160
        publish(client, 25, capture, 'Live video did not recover after a late burst', color=(180, 60, 30))
    wait_state(lambda state: state[0] == 0, 'Jitter-test disconnect retained video')
    print('Latency regressions passed: 500 to 50 ms delay improvement, bounded clock drift/jitter, late burst and recovery')


def check_true_image_expiry(receiver):
    with closing(receiver.paired()) as client:
        base = int(time.monotonic() * 1000)
        publish(client, 0, base, 'Expiry-test image did not arrive')
        time.sleep(2.1)
        blank = wait_state(lambda state: state[0] == 0 and state[1] == bytes((35, 23, 15, 255)),
                           'A truly stale image survived the two-second image deadline', timeout=.7)
        client.sendall(frame_packet(1, base + 1))
        assert_retained(blank, 'Image expiry recalibrated a queued old JPEG as a fresh picture', duration=.12)
        publish(client, 2, int(time.monotonic() * 1000),
                'Image expiry disconnected an otherwise authenticated receiver')
    wait_state(lambda state: state[0] == 0, 'Expiry-test disconnect retained video')
    print('Image expiry passed: two-second blanking, queued stale JPEG stays blank, fresh resume on existing pairing')


def check_pause_keepalive_and_resume(receiver):
    with closing(receiver.paired()) as client:
        base = int(time.monotonic() * 1000)
        # A control record before the first JPEG has no image clock to calibrate.
        client.sendall(frame_packet(0, base + 10000, image=b''))
        publish(client, 1, base, 'First keepalive poisoned the first JPEG clock')
        client.sendall(frame_packet(2, base + 10000, image=b''))
        blank = wait_state(lambda state: state[0] == 0, 'No-image record did not immediately clear personal video')
        deadline = time.monotonic() + 6.1
        sequence = 3
        last_heartbeat_capture = base + 10000
        while time.monotonic() < deadline:
            last_heartbeat_capture = int(time.monotonic() * 1000)
            client.sendall(frame_packet(sequence, last_heartbeat_capture, image=b''))
            assert_retained(blank, 'Repeated keepalives rewrote pixels or revived stale video', duration=.05)
            sequence += 1
            time.sleep(.4)
        # A JPEG can already be in flight when a newer no-image record is sent;
        # only accepted JPEG timestamps constrain the next JPEG's capture clock.
        publish(client, sequence, last_heartbeat_capture - 100,
                'Keepalives timed out or incorrectly rejected a resumed JPEG older than the heartbeat')
    wait_state(lambda state: state[0] == 0, 'Keepalive-test disconnect retained video')
    print('Pause keepalive passed: immediate blanking once, six-second pairing, unanchored first control and JPEG resume')


def check_sustained_jitter_gaps_and_keepalive(receiver):
    accepted = 0
    with closing(receiver.paired()) as client:
        beginning = time.monotonic()
        deadline = beginning + 60
        next_gap, next_burst = beginning + 8, beginning + 12
        sequence, last_capture = 0, -1

        def fresh(message):
            nonlocal sequence, last_capture, accepted
            elapsed = time.monotonic() - beginning
            lead = 0 if sequence == 0 else 450 + int(elapsed * 2)
            network_delay = (0, 40, 5, 25)[sequence % 4]
            capture = max(int(time.monotonic() * 1000) + lead - network_delay, last_capture + 1)
            state = publish(client, sequence, capture, message)
            sequence += 1
            last_capture = capture
            accepted += 1
            return state

        while time.monotonic() < deadline:
            latest = fresh('Sustained jitter stopped publishing on the existing pairing')
            now = time.monotonic()
            if now >= next_gap:
                assert_retained(latest, 'A short 500-ms image gap blanked recent video', duration=.5)
                next_gap = time.monotonic() + 8
            if now >= next_burst:
                # Begin from an actually fresh image even if a short-gap phase
                # occurred in the same iteration; expire truly old images separately.
                latest = fresh('Sustained receiver failed before a delayed burst')
                time.sleep(1.6)
                for index in range(3):
                    client.sendall(frame_packet(sequence, last_capture + index + 1))
                    sequence += 1
                assert_retained(latest, 'A sustained delayed burst blanked recent video', duration=.12)
                fresh('Sustained video did not recover after a delayed burst')
                next_burst = time.monotonic() + 12
            time.sleep(.07)
        # Keep the exact same TLS owner while images are explicitly unavailable.
        client.sendall(frame_packet(sequence, int(time.monotonic() * 1000), image=b''))
        sequence += 1
        blank = wait_state(lambda state: state[0] == 0, 'Sustained no-image control did not clear video')
        pause_end = time.monotonic() + 6.1
        latest_heartbeat = int(time.monotonic() * 1000)
        while time.monotonic() < pause_end:
            latest_heartbeat = int(time.monotonic() * 1000)
            client.sendall(frame_packet(sequence, latest_heartbeat, image=b''))
            sequence += 1
            assert_retained(blank, 'Sustained keepalives rewrote or revived stale video', duration=.04)
            time.sleep(.46)
        publish(client, sequence, latest_heartbeat - 100,
                'Sustained pairing failed to resume after more than five seconds without images',
                color=(180, 60, 30))
    wait_state(lambda state: state[0] == 0, 'Sustained test disconnect retained personal video')
    print(f'Sustained regression passed: 60 seconds, {accepted} confirmed images, jitter/drift, '
          '500-ms gaps, late bursts, six-second keepalive and same-pairing resume')


def check_pairing_throttle(receiver):
    source = '127.0.0.6'
    wrong_pin = f'{(int(receiver.pin) + 1) % 1_000_000:06d}'
    for _ in range(5):
        with closing(receiver.tls(source=source)) as wrong:
            wrong.sendall(PROTOCOL + wrong_pin.encode('ascii'))
            assert wrong.recv(1) == b'\0', 'Incorrect code escaped authentication throttle'
    time.sleep(.04)
    try:
        with closing(receiver.tls(source=source)) as limited:
            limited.sendall(PROTOCOL + receiver.pin.encode('ascii'))
            assert limited.recv(1) != b'\1', 'Sixth authentication attempt was not throttled'
    except (ssl.SSLError, ConnectionResetError, ConnectionAbortedError):
        pass
    assert receiver.process.poll() is None, 'Receiver exited during authentication throttling'
    wait_state(lambda state: state[0] == 0, 'Failed pairing attempts restored stale video')
    print('Authentication throttle passed: five failed attempts block further attempts from that IP')


def main():
    result = subprocess.run([str(EXE), '--self-test'], capture_output=True, text=True,
                            timeout=20, **OPTIONS)
    assert result.returncode == 0, result.stdout + result.stderr
    print(result.stdout.strip())
    result = subprocess.run([str(EXE), '--pairing-test'], capture_output=True, text=True,
                            timeout=20, **OPTIONS)
    assert result.returncode == 0, result.stdout + result.stderr
    print(result.stdout.strip())
    receiver = Receiver()
    try:
        check_authentication(receiver)
        check_invalid_frames(receiver)
        check_owner_protection(receiver)
        check_freshness_and_sustained_video(receiver)
        check_latency_improvement_jitter_and_drift(receiver)
        check_true_image_expiry(receiver)
        check_pause_keepalive_and_resume(receiver)
        check_sustained_jitter_gaps_and_keepalive(receiver)
        check_pairing_throttle(receiver)
    except BaseException:
        print('Receiver diagnostics:\n' + '\n'.join(receiver.lines))
        raise
    finally:
        receiver.close()
    print('Secure receiver integration checks passed; Windows camera registration/call-app checks are separate.')


if __name__ == '__main__':
    main()
