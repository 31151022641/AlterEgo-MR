using System;
using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

// The self-signed transport identity is authenticated by the pairing key's
// full certificate fingerprint. It is not an Authenticode publisher certificate.
sealed class PairingIdentity : IDisposable {
    public readonly X509Certificate2 Certificate;
    public readonly string Pin;
    public string Fingerprint {
        get { using(var hash=SHA256.Create()) return BitConverter.ToString(hash.ComputeHash(Certificate.RawData)).Replace("-",""); }
    }
    public string PairingKey { get { return Pin+"-"+Fingerprint; } }
    PairingIdentity(X509Certificate2 certificate,string pin) { Certificate=certificate; Pin=pin; }
    static string StatePath {
        get {
            string folder=Environment.GetEnvironmentVariable("ALTEREGO_MR_STATE_DIR") ??
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"AlterEgoMR");
            Directory.CreateDirectory(folder);
            return Path.Combine(folder,"pairing.dpapi");
        }
    }
    public static PairingIdentity LoadOrCreate(bool reset=false) {
        string path=StatePath;
        if(!reset && File.Exists(path)) {
            byte[] stored=File.ReadAllBytes(path);
            byte[] plain=ProtectedData.Unprotect(stored,null,DataProtectionScope.CurrentUser);
            try {
                if(plain.Length<7) throw new InvalidDataException("Pairing identity is damaged. Choose Reset pairing.");
                string pin=Encoding.ASCII.GetString(plain,0,6);
                foreach(char value in pin) if(value<'0' || value>'9') throw new InvalidDataException("Pairing identity is damaged. Choose Reset pairing.");
                byte[] pfx=new byte[plain.Length-6]; Buffer.BlockCopy(plain,6,pfx,0,pfx.Length);
                try {
                    // .NET Framework's Windows TLS provider needs a user key container.
                    // Disposal removes the temporary import; the durable PFX stays DPAPI-protected.
                    var certificate=new X509Certificate2(pfx,"",X509KeyStorageFlags.Exportable|X509KeyStorageFlags.UserKeySet);
                    if(!certificate.HasPrivateKey || DateTime.UtcNow>certificate.NotAfter.ToUniversalTime()) {
                        certificate.Dispose(); throw new InvalidDataException("Pairing identity expired. Choose Reset pairing and pair again.");
                    }
                    return new PairingIdentity(certificate,pin);
                } finally { Array.Clear(pfx,0,pfx.Length); }
            } finally { Array.Clear(plain,0,plain.Length); }
        }
        using(var rsa=RSA.Create()) {
            rsa.KeySize=2048;
            var request=new CertificateRequest("CN=AlterEgo MR Webcam",rsa,HashAlgorithmName.SHA256,RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false,false,0,true));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature|X509KeyUsageFlags.KeyEncipherment,true));
            var usages=new OidCollection(); usages.Add(new Oid("1.3.6.1.5.5.7.3.1"));
            request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(usages,true));
            using(var generated=request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1),DateTimeOffset.UtcNow.AddYears(5))) {
                byte[] random=new byte[4]; using(var rng=RandomNumberGenerator.Create()) rng.GetBytes(random);
                string pin=(100000+BitConverter.ToUInt32(random,0)%900000).ToString();
                byte[] pfx=generated.Export(X509ContentType.Pfx,"");
                byte[] plain=new byte[6+pfx.Length]; Encoding.ASCII.GetBytes(pin).CopyTo(plain,0); pfx.CopyTo(plain,6);
                try {
                    byte[] protectedBytes=ProtectedData.Protect(plain,null,DataProtectionScope.CurrentUser);
                    // Atomic identity update: a failed write keeps the existing pairing usable.
                    string temporary=path+".new";
                    File.WriteAllBytes(temporary,protectedBytes);
                    if(File.Exists(path)) File.Replace(temporary,path,null); else File.Move(temporary,path);
                    return new PairingIdentity(new X509Certificate2(pfx,"",X509KeyStorageFlags.Exportable|X509KeyStorageFlags.UserKeySet),pin);
                } finally { Array.Clear(plain,0,plain.Length); Array.Clear(pfx,0,pfx.Length); }
            }
        }
    }
    public void Dispose() { Certificate.Dispose(); }
}

static class AvatarProtocol {
    public static bool ValidVideoSize(int width,int height) {
        return width>=320 && height>=180 && width<=1920 && height<=1080 &&
            (width&1)==0 && (height&1)==0 && width*9==height*16;
    }
    // Bound decoded dimensions before handing any JPEG to the Windows decoder.
    public static bool JpegSize(byte[] data,out int width,out int height) {
        width=height=0;
        if(data.Length<4 || data[0]!=255 || data[1]!=216) return false;
        int offset=2;
        while(offset<data.Length) {
            if(data[offset++]!=255) return false;
            while(offset<data.Length && data[offset]==255) offset++;
            if(offset>=data.Length) return false;
            int marker=data[offset++];
            if(marker==0 || marker==217 || marker==218) return false;
            if(marker==1 || (marker>=208 && marker<=215)) continue;
            if(offset+2>data.Length) return false;
            int length=(data[offset]<<8)|data[offset+1];
            if(length<2 || length>data.Length-offset) return false;
            bool sof=marker>=192 && marker<=207 && marker!=196 && marker!=200 && marker!=204;
            if(sof) {
                if(length<8 || data[offset+2]!=8) return false;
                height=(data[offset+3]<<8)|data[offset+4]; width=(data[offset+5]<<8)|data[offset+6];
                return true;
            }
            offset+=length;
        }
        return false;
    }
    public static byte[] Read(Stream stream,int length) {
        var result=new byte[length]; int offset=0;
        while(offset<length) { int count=stream.Read(result,offset,length-offset); if(count==0) throw new EndOfStreamException(); offset+=count; }
        return result;
    }
    public static long ReadLong(Stream stream) {
        byte[] bytes=Read(stream,8); ulong value=0;
        foreach(byte item in bytes) value=(value<<8)|item;
        return unchecked((long)value);
    }
    public static int ReadLength(Stream stream) {
        byte[] bytes=Read(stream,4);
        return (bytes[0]<<24)|(bytes[1]<<16)|(bytes[2]<<8)|bytes[3];
    }
    public static bool Authenticate(byte[] value,string pin) {
        byte[] expected=Encoding.ASCII.GetBytes("AEMR0003"+pin);
        if(value.Length!=expected.Length) return false;
        int different=0; for(int i=0;i<expected.Length;i++) different|=value[i]^expected[i];
        return different==0;
    }
}

enum AvatarFrameDecision { Accept, Delayed, ClockJump }

// Headset and PC clocks have different origins. The smallest observed offset
// estimates transit delay without treating a faster subsequent frame as "future".
sealed class AvatarFrameTimeline {
    bool anchored;
    long minimumOffset,lastCapture=-1,lastArrival;
    public bool FreshAt(long capture,long arrival) {
        return anchored && (decimal)arrival-capture-minimumOffset<=1500;
    }
    public AvatarFrameDecision Observe(long capture,long arrival) {
        if(capture<=0 || arrival<0 || capture<lastCapture) throw new InvalidDataException("Avatar capture clock moved backward");
        if(anchored && (decimal)capture-lastCapture>(decimal)arrival-lastArrival+2000) return AvatarFrameDecision.ClockJump;
        long offset=arrival-capture;
        long proposed=anchored?Math.Min(minimumOffset,offset):offset;
        if((decimal)offset-proposed>1500) return AvatarFrameDecision.Delayed;
        minimumOffset=proposed; lastCapture=capture; lastArrival=arrival; anchored=true;
        return AvatarFrameDecision.Accept;
    }
}

static class WebcamDiagnostics {
    static readonly object gate=new object();
    static readonly System.Collections.Generic.Dictionary<string,DateTime> last=new System.Collections.Generic.Dictionary<string,DateTime>();
    public static void Record(string phase,string detail="") { lock(gate) {
        try {
            DateTime now=DateTime.UtcNow,previous;
            if(last.TryGetValue(phase,out previous) && now-previous<TimeSpan.FromSeconds(5)) return;
            last[phase]=now;
            string directory=Environment.GetEnvironmentVariable("ALTEREGO_MR_STATE_DIR") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"AlterEgoMR");
            Directory.CreateDirectory(directory); string path=Path.Combine(directory,"webcam-diagnostics.log");
            if(File.Exists(path) && new FileInfo(path).Length>65536) File.WriteAllText(path,"");
            // No video, pairing key, certificate or peer address is recorded.
            File.AppendAllText(path,now.ToString("O")+" "+phase+(detail.Length==0?"":" "+detail)+Environment.NewLine);
        } catch { /* Diagnostics must never interrupt a video connection. */ }
    } }
}
