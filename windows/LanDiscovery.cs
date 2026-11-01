using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Security.Cryptography;
using System.IO;

// Discovery is only an untrusted hint. No PIN is advertised. A fresh TLS
// transcript is confirmed by the human on both devices before releasing a PIN.
static class PairingConfirmation {
    internal static string Code(string fingerprint,byte[] clientNonce,byte[] serverNonce) {
        if(fingerprint.Length!=64 || clientNonce.Length!=32 || serverNonce.Length!=32) throw new InvalidDataException("Invalid pairing transcript");
        byte[] transcript=new byte[96];
        for(int i=0;i<32;i++) transcript[i]=Convert.ToByte(fingerprint.Substring(i*2,2),16);
        Buffer.BlockCopy(clientNonce,0,transcript,32,32); Buffer.BlockCopy(serverNonce,0,transcript,64,32);
        using(var hash=SHA256.Create()) {
            string hex=BitConverter.ToString(hash.ComputeHash(transcript),0,6).Replace("-","");
            return hex.Substring(0,4)+"-"+hex.Substring(4,4)+"-"+hex.Substring(8,4);
        }
    }
}

sealed class LanDiscovery : IDisposable {
    UdpClient socket; Task worker; volatile bool running;
    internal void Start(IPAddress address,PairingIdentity identity) {
        socket=new UdpClient(new IPEndPoint(IPAddress.Any,38992)); socket.Client.ReceiveTimeout=1000; running=true;
        worker=Task.Run(delegate {
            DateTime window=DateTime.UtcNow; int requests=0;
            while(running) try {
                IPEndPoint peer=new IPEndPoint(IPAddress.Any,0); byte[] data=socket.Receive(ref peer);
                byte[] ip=peer.Address.GetAddressBytes();
                if(ip.Length!=4 || !(ip[0]==10 || (ip[0]==172 && ip[1]>=16 && ip[1]<=31) || (ip[0]==192 && ip[1]==168) || ip[0]==127)) continue;
                if(DateTime.UtcNow-window>TimeSpan.FromMinutes(1)) { window=DateTime.UtcNow; requests=0; }
                if(++requests>120 || data.Length!=47) continue;
                string query=Encoding.ASCII.GetString(data);
                if(!query.StartsWith("AEGO_DISCOVER1|",StringComparison.Ordinal)) continue;
                string nonce=query.Substring(15);
                bool valid=true; foreach(char c in nonce) if(!Uri.IsHexDigit(c)) valid=false; if(!valid) continue;
                string name=Environment.MachineName.Replace("|",""); if(name.Length>60) name=name.Substring(0,60);
                byte[] reply=Encoding.ASCII.GetBytes("AEGO_REPLY1|"+nonce+"|"+address+"|38991|"+identity.Fingerprint+"|"+name);
                socket.Send(reply,reply.Length,peer);
            } catch(SocketException) { } catch(ObjectDisposedException) { break; }
        });
    }
    public void Dispose() { running=false; if(socket!=null) socket.Close(); if(worker!=null) worker.Wait(); }
}
