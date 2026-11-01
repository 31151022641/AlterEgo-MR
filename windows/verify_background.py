"""Synthetic TLS video + local background composition, no installed camera or user imagery."""
from contextlib import closing
import io
import struct
import time
from PIL import Image, ImageDraw
from verify import Receiver, frame_packet, wait_state, FRAMES
from verify_quality import paired
from verify_upgrade import receipt


def fixture(width, height):
    image = Image.new('RGB', (960, 540), (220, 230, 240))
    draw = ImageDraw.Draw(image)
    draw.rectangle((390, 60, 570, 130), fill=(16, 20, 25))
    draw.ellipse((400, 90, 560, 260), fill=(210, 142, 95))
    draw.rectangle((80, 285, 190, 345), fill=(210, 142, 95))
    draw.rectangle((770, 285, 880, 345), fill=(210, 142, 95))
    draw.rectangle((300, 255, 660, 539), fill=(45, 85, 145))
    draw.rectangle((445, 310, 515, 370), fill=(220, 230, 240))
    image = image.resize((width, height), Image.Resampling.LANCZOS)
    encoded = io.BytesIO()
    image.save(encoded, 'JPEG', quality=97, subsampling=0)
    return encoded.getvalue()


def pixels(width, height):
    with FRAMES.open('rb') as channel:
        header = channel.read(24)
        version, w, h, revision = struct.unpack_from('<IIII', header)
        channel.seek(64)
        data = channel.read(width * height * 4)
        channel.seek(0)
        assert channel.read(24) == header and revision % 2 == 0, 'Torn shared-frame read'
    assert (version, w, h) == (2, width, height), 'Background changed source resolution'
    def pixel(x, y):
        offset = (int(y * height / 540) * width + int(x * width / 960)) * 4
        return tuple(data[offset:offset+3])[::-1]
    assert pixel(2, 2) == (150, 20, 55), 'Camera channel still has the original backdrop'
    for point, colour in [((450, 70), (16, 20, 25)), ((480, 160), (210, 142, 95)),
                          ((100, 300), (210, 142, 95)), ((820, 300), (210, 142, 95)),
                          ((350, 400), (45, 85, 145)), ((480, 340), (220, 230, 240))]:
        assert all(abs(a-b) <= 5 for a, b in zip(pixel(*point), colour)), f'Foreground changed at synthetic point {point}'


def main():
    receiver = Receiver('--receiver-background-test')
    try:
        with closing(paired(receiver)) as client:
            sequence = 0
            for width, height in [(960, 540), (1280, 720), (1600, 900), (1920, 1080)]:
                client.sendall(frame_packet(sequence, int(time.monotonic()*1000), fixture(width, height)))
                receipt(client, sequence, 1)
                pixels(width, height)
                sequence += 1
            encoded = fixture(1920, 1080)
            deadline = time.monotonic() + 60
            confirmations = 0
            while time.monotonic() < deadline:
                started = time.monotonic()
                client.sendall(frame_packet(sequence, int(started*1000), encoded))
                receipt(client, sequence, 1)
                confirmations += 1
                sequence += 1
                if confirmations % 100 == 0:
                    pixels(1920, 1080)
                time.sleep(max(0, .1-(time.monotonic()-started)))
            client.sendall(frame_packet(sequence, int(time.monotonic()*1000), b'', length=0))
            receipt(client, sequence, 0)
            wait_state(lambda state: state[0] == 0, 'Paused camera retained a background-composed avatar')
            sequence += 1
            client.sendall(frame_packet(sequence, int(time.monotonic()*1000), encoded))
            receipt(client, sequence, 1)
            pixels(1920, 1080)
        wait_state(lambda state: state[0] == 0, 'Disconnect retained a composed avatar')
        print(f'Background receiver checks passed: 4 source sizes, detail retained, {confirmations} confirmed 1080p images over 60 seconds, pause/recovery and disconnect clearing')
    finally:
        receiver.close()


if __name__ == '__main__':
    main()
