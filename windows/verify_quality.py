"""Synthetic encrypted high-detail reception; does not touch installed cameras or wearer video."""
from contextlib import closing
import io
import struct
import time
from PIL import Image, ImageDraw
from verify import Receiver, frame_packet, wait_state, assert_closed, FRAMES
from verify_upgrade import exact, receipt


def paired(receiver):
    deadline=time.monotonic()+4
    while time.monotonic()<deadline:
        connection=receiver.tls()
        connection.sendall(b'AEMR0005'+receiver.pin.encode())
        result=exact(connection,1)
        if result==b'\1': return connection
        connection.close()
        assert result==b'\2', 'High-detail protocol rejected'
        time.sleep(.03)
    raise AssertionError('Previous owner did not exit')


def pattern(width,height):
    image=Image.new('RGB',(width,height),(36,115,179)); draw=ImageDraw.Draw(image)
    for x in range(0,width,2): draw.line((x,0,x,height//3),fill='white')
    draw.rectangle((0,height-8,width-1,height-1),fill=(230,27,90))
    encoded=io.BytesIO(); image.save(encoded,'JPEG',quality=97,subsampling=0)
    return encoded.getvalue()


def main():
    receiver=Receiver()
    try:
        with closing(paired(receiver)) as client:
            for sequence,(width,height) in enumerate(((1024,576),(1280,720),(1600,900),(1920,1080))):
                jpeg=pattern(width,height)
                client.sendall(frame_packet(sequence,int(time.monotonic()*1000),jpeg)); receipt(client,sequence,1)
                state=wait_state(lambda state: state[0]>0,'No high-detail publication')
                with FRAMES.open('rb') as channel:
                    version,w,h=struct.unpack('<III',channel.read(12)); channel.seek(64)
                    pixels=channel.read(width*height*4)
                assert (version,w,h)==(2,width,height), 'Source was resized before virtual-camera negotiation'
                assert len(pixels)==width*height*4
                # A single-pixel neutral edge must survive reception and native storage.
                a=pixels[0:3]; b=pixels[4:7]
                assert all(a[c]>b[c]+45 for c in range(3)), 'Fine edge detail disappeared'
                middle=((height//2)*width+1)*4
                assert pixels[-2]>pixels[middle+2]+100 and pixels[-3]<pixels[middle+1]-40, 'Bottom orientation changed'
        wait_state(lambda state: state[0]==0,'High-detail disconnect retained personal pixels')
        invalid=[pattern(1922,1080),pattern(1600,1200)]
        # A decompression-bomb size must be rejected from its header before decoding.
        invalid.append(b'\xff\xd8\xff\xc0\x00\x11\x08\x7f\xff\x7f\xff\x03\x01\x11\x00\x02\x11\x00\x03\x11\x00')
        for jpeg in invalid:
            with closing(paired(receiver)) as client:
                client.sendall(frame_packet(0,int(time.monotonic()*1000),jpeg))
                assert_closed(client,'Invalid high-detail image was retained')
            wait_state(lambda state: state[0]==0,'Invalid image retained pixels')
        print('High-detail checks passed: 1024/1280/1600/1920 source widths retained; one-pixel contrast, receipts, dimensions-before-decode, orientation and disconnect clearing')
    finally: receiver.close()


if __name__=='__main__': main()
