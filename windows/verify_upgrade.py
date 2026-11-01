"""Exercises production LAN discovery, both approvals, and explicit image receipts."""
import hashlib
import os
import queue
import socket
import struct
import time
from contextlib import closing
from verify import Receiver, frame_packet, wait_state


def exact(connection, count):
    output = b''
    while len(output) < count:
        data = connection.recv(count-len(output))
        assert data, 'Unexpected disconnect'
        output += data
    return output


def receipt(connection, sequence, state):
    assert struct.unpack('>QB', exact(connection, 9)) == (sequence, state)


def confirmation(receiver, client):
    nonce = os.urandom(32)
    client.sendall(b'AEPAIR01'+nonce)
    assert exact(client, 1) == b'\1'
    server_nonce = exact(client, 32)
    digest = hashlib.sha256(bytes.fromhex(receiver.fingerprint)+nonce+server_nonce).hexdigest()[:12].upper()
    code = '-'.join(digest[i:i+4] for i in range(0,12,4))
    deadline = time.monotonic()+4
    while time.monotonic() < deadline:
        line = receiver.pending.get(timeout=4)
        if line.startswith('CONFIRM '):
            assert line == 'CONFIRM '+code, 'PC/Quest transcript code mismatch'
            return
    raise AssertionError('PC confirmation not shown')


def approve(receiver, text):
    receiver.process.stdin.write(text+'\n')
    receiver.process.stdin.flush()


def main():
    receiver = Receiver('--easy-pairing-test')
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as discovery:
            nonce = os.urandom(16).hex().upper()
            discovery.settimeout(2)
            discovery.sendto(('AEGO_DISCOVER1|'+nonce).encode(), ('127.0.0.1',38992))
            reply, peer = discovery.recvfrom(512)
            fields = reply.decode().split('|')
            assert fields[:2] == ['AEGO_REPLY1', nonce]
            assert fields[2:5] == ['127.0.0.1','38991',receiver.fingerprint]
            assert len(fields) == 6, 'Discovery must not advertise a pairing PIN'
        with closing(receiver.tls()) as client:
            confirmation(receiver, client)
            client.sendall(b'\1')
            client.settimeout(.2)
            try:
                leaked = client.recv(7)
                raise AssertionError('PIN released before PC approval: '+repr(leaked))
            except socket.timeout:
                pass
            approve(receiver, 'APPROVE'); client.settimeout(4)
            assert exact(client,7) == b'\1'+receiver.pin.encode()
        time.sleep(.15)
        with closing(receiver.tls()) as client:
            confirmation(receiver,client); client.sendall(b'\1'); approve(receiver,'DECLINE')
            assert exact(client,1) == b'\0', 'PC refusal released a PIN'
        time.sleep(.15)
        with closing(receiver.tls()) as client:
            confirmation(receiver,client); approve(receiver,'APPROVE'); client.sendall(b'\0')
            assert exact(client,1) == b'\0', 'Quest refusal released a PIN'
        time.sleep(.15)
        print('Discovery and pairing passed: fresh 48-bit transcript codes match; both approvals required; refusal releases no PIN')
        with closing(receiver.tls(source='127.0.0.8')) as client:
            client.sendall(b'AEMR0004'+receiver.pin.encode()); assert exact(client,1) == b'\1'
            started = int(time.monotonic()*1000)
            client.sendall(frame_packet(0,started)); receipt(client,0,1)
            client.sendall(frame_packet(1,started+1,image=b'')); receipt(client,1,0)
            time.sleep(1.65)
            client.sendall(frame_packet(2,started+2)); receipt(client,2,2)
            sequence = 3
            deadline = time.monotonic()+5
            while time.monotonic() < deadline:
                client.sendall(frame_packet(sequence,int(time.monotonic()*1000)))
                receipt(client,sequence,1); sequence += 1; time.sleep(.03)
            client.sendall(frame_packet(sequence,int(time.monotonic()*1000),image=b'')); receipt(client,sequence,0)
        wait_state(lambda state: state[0] == 0, 'Receipted stream disconnect retained video')
        print(f'AEMR0004 passed: accepted/paused/delayed receipts match sequences; {sequence-3} confirmed images; live recovery and disconnect clear')
    finally:
        receiver.close()


if __name__ == '__main__':
    main()
