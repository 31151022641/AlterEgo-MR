using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Authentication;
using System.Security.Principal;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
[assembly: AssemblyVersion("1.5.0.0")]
[assembly: AssemblyFileVersion("1.5.0.0")]

static class NativeCamera {
    [DllImport("AlterEgoCamera.dll",CallingConvention=CallingConvention.StdCall,CharSet=CharSet.Unicode)] internal static extern int BeginAvatarCamera(string framePath);
    [DllImport("AlterEgoCamera.dll",CallingConvention=CallingConvention.StdCall)] internal static extern void EndAvatarCamera();
    [DllImport("AlterEgoCamera.dll",CallingConvention=CallingConvention.StdCall)] internal static extern int TestAvatarSource();
    [DllImport("AlterEgoCamera.dll",CallingConvention=CallingConvention.StdCall,CharSet=CharSet.Unicode)] internal static extern int TestRegisteredAvatarCamera(string framePath);
    [DllImport("kernel32.dll")] internal static extern ulong GetTickCount64();
}

sealed unsafe class FramePublisher : IDisposable {
    const int Width=1920,Height=1080,Size=Width*Height*4;
    readonly object gate=new object();
    FileStream file; MemoryMappedFile mapping; MemoryMappedViewAccessor view; byte* data; bool disposed;
    public readonly string Path;
    public FramePublisher() {
        Path=Environment.GetEnvironmentVariable("ALTEREGO_MR_FRAME_FILE") ?? System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),"AlterEgoMR",WindowsIdentity.GetCurrent().User.Value,"frames.dat");
        if(!Directory.Exists(System.IO.Path.GetDirectoryName(Path))) throw new InvalidOperationException("Install webcam first to create your private avatar channel.");
        try {
            file=new FileStream(Path,FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.ReadWrite|FileShare.Delete);
            if(file.Length!=64+Size) file.SetLength(64+Size);
            mapping=MemoryMappedFile.CreateFromFile(file,null,64+Size,MemoryMappedFileAccess.ReadWrite,null,HandleInheritability.None,true);
            view=mapping.CreateViewAccessor(0,64+Size,MemoryMappedFileAccess.ReadWrite); view.SafeMemoryMappedViewHandle.AcquirePointer(ref data);
            *(int*)data=2; *(int*)(data+4)=960; *(int*)(data+8)=540; Clear();
        } catch {
            if(data!=null) view.SafeMemoryMappedViewHandle.ReleasePointer();
            if(view!=null) view.Dispose(); if(mapping!=null) mapping.Dispose(); if(file!=null) file.Dispose(); throw;
        }
    }
    public void Publish(Bitmap image) {
        if(!AvatarProtocol.ValidVideoSize(image.Width,image.Height)) throw new InvalidDataException("Invalid avatar resolution");
        int width=image.Width,height=image.Height;
        using(var converted=new Bitmap(width,height,PixelFormat.Format32bppRgb)) {
            using(var graphics=Graphics.FromImage(converted)) graphics.DrawImageUnscaled(image,0,0);
            var pixels=converted.LockBits(new Rectangle(0,0,width,height),ImageLockMode.ReadOnly,PixelFormat.Format32bppRgb);
            try { lock(gate) {
                if(disposed) return;
                Interlocked.Increment(ref *(int*)(data+12)); Thread.MemoryBarrier();
                *(int*)(data+4)=width; *(int*)(data+8)=height;
                for(int y=0;y<height;y++) {
                    // The media source advertises a positive RGB32 stride (top-down).
                    byte* source=(byte*)pixels.Scan0+y*pixels.Stride; byte* destination=data+64+y*width*4;
                    Buffer.MemoryCopy(source,destination,width*4,width*4);
                }
                *(ulong*)(data+16)=NativeCamera.GetTickCount64(); Thread.MemoryBarrier(); Interlocked.Increment(ref *(int*)(data+12));
            } } finally { converted.UnlockBits(pixels); }
        }
    }
    public void Clear() { lock(gate) {
        if(disposed) return;
        Interlocked.Increment(ref *(int*)(data+12)); Thread.MemoryBarrier(); *(ulong*)(data+16)=0;
        for(int n=64;n<64+Size;n+=4) { data[n]=35; data[n+1]=23; data[n+2]=15; data[n+3]=255; }
        Thread.MemoryBarrier(); Interlocked.Increment(ref *(int*)(data+12));
    } }
    public void Dispose() { lock(gate) {
        if(disposed) return; Clear(); disposed=true; view.SafeMemoryMappedViewHandle.ReleasePointer(); data=null; view.Dispose(); mapping.Dispose(); file.Dispose();
    } }
}

sealed class AvatarReceiver : IDisposable {
    readonly object gate=new object(); readonly HashSet<TcpClient> clients=new HashSet<TcpClient>(); readonly List<Task> handlers=new List<Task>();
    readonly Dictionary<string,Queue<DateTime>> rejected=new Dictionary<string,Queue<DateTime>>();
    readonly Queue<DateTime> allRejected=new Queue<DateTime>();
    TcpListener listener; TcpClient owner; volatile bool running; Task acceptWorker; int maximumFrames;
    readonly FramePublisher frames; readonly Action<Bitmap> preview; readonly Action clearPreview; readonly Action<string> status;
    internal Func<string,Task<bool>> ConfirmPairing;
    internal AvatarBackground Background;
    internal Action CancelPairing;
    int pendingPair;
    public AvatarReceiver(FramePublisher f,Action<Bitmap> p,Action c,Action<string> s) { frames=f; preview=p; clearPreview=c; status=s; }
    public void Start(IPAddress address,int port,PairingIdentity identity,int frameLimit=0) {
        if(running) throw new InvalidOperationException("Receiver is already listening");
        maximumFrames=frameLimit; listener=new TcpListener(address,port); listener.Start(8); running=true;
        acceptWorker=Task.Run(delegate {
            while(running) {
                try {
                    var client=listener.AcceptTcpClient(); client.NoDelay=true; client.ReceiveTimeout=3000; client.SendTimeout=3000;
                    string peer=((IPEndPoint)client.Client.RemoteEndPoint).Address.ToString();
                    lock(gate) {
                        if(!running || clients.Count>=8 || IsLimited(peer)) { client.Close(); continue; }
                        clients.Add(client); handlers.RemoveAll(delegate(Task task) { return task.IsCompleted; });
                        handlers.Add(Task.Run(delegate { Handle(client,peer,identity); }));
                    }
                } catch(SocketException) { if(running) status("Network listener stopped. Stop and restart the receiver."); }
                catch(ObjectDisposedException) { }
            }
        });
    }
    bool IsLimited(string peer) {
        while(allRejected.Count>0 && DateTime.UtcNow-allRejected.Peek()>TimeSpan.FromMinutes(1)) allRejected.Dequeue();
        if(allRejected.Count>=30) return true;
        Queue<DateTime> attempts; if(!rejected.TryGetValue(peer,out attempts)) return false;
        while(attempts.Count>0 && DateTime.UtcNow-attempts.Peek()>TimeSpan.FromMinutes(1)) attempts.Dequeue(); return attempts.Count>=5;
    }
    void Reject(string peer) { lock(gate) {
        Queue<DateTime> attempts; if(!rejected.TryGetValue(peer,out attempts)) {
            if(rejected.Count>=256) rejected.Clear(); attempts=new Queue<DateTime>(); rejected.Add(peer,attempts);
        } attempts.Enqueue(DateTime.UtcNow); allRejected.Enqueue(DateTime.UtcNow);
    } }
    void Handle(TcpClient client,string peer,PairingIdentity identity) {
        bool owns=false;
        try {
            int authenticationDeadline=0;
            using(var deadline=new System.Threading.Timer(delegate {
                if(Interlocked.CompareExchange(ref authenticationDeadline,1,0)==0) { try { client.Close(); } catch { } }
            },null,3000,Timeout.Infinite))
            using(var stream=new SslStream(client.GetStream(),false)) {
                stream.ReadTimeout=3000; stream.WriteTimeout=3000; stream.AuthenticateAsServer(identity.Certificate,false,SslProtocols.Tls12,false);
                byte[] magic=AvatarProtocol.Read(stream,8);
                if(System.Text.Encoding.ASCII.GetString(magic)=="AEPAIR01") {
                    if(ConfirmPairing==null || Interlocked.CompareExchange(ref pendingPair,1,0)!=0) { stream.WriteByte(0); Reject(peer); return; }
                    try {
                        byte[] clientNonce=AvatarProtocol.Read(stream,32),serverNonce=new byte[32];
                        using(var random=System.Security.Cryptography.RandomNumberGenerator.Create()) random.GetBytes(serverNonce);
                        if(Interlocked.CompareExchange(ref authenticationDeadline,2,0)!=0) throw new TimeoutException("Pairing handshake expired");
                        deadline.Change(Timeout.Infinite,Timeout.Infinite);
                        stream.ReadTimeout=90000;
                        var approval=ConfirmPairing(PairingConfirmation.Code(identity.Fingerprint,clientNonce,serverNonce));
                        stream.WriteByte(1); stream.Write(serverNonce,0,32); stream.Flush();
                        // Both independent confirmations are required. Waiting is bounded.
                        if(stream.ReadByte()!=1 || !approval.Wait(90000) || !approval.Result || !running) { stream.WriteByte(0); Reject(peer); return; }
                        stream.WriteByte(1); byte[] pin=System.Text.Encoding.ASCII.GetBytes(identity.Pin); stream.Write(pin,0,pin.Length); stream.Flush();
                        status("Pairing approved · waiting for Quest video");
                    } finally { if(CancelPairing!=null) CancelPairing(); Interlocked.Exchange(ref pendingPair,0); }
                    return;
                }
                byte[] header=new byte[14]; Buffer.BlockCopy(magic,0,header,0,8); Buffer.BlockCopy(AvatarProtocol.Read(stream,6),0,header,8,6);
                bool highDetail=System.Text.Encoding.ASCII.GetString(magic)=="AEMR0005";
                bool receipts=highDetail || System.Text.Encoding.ASCII.GetString(magic)=="AEMR0004";
                if(receipts) Buffer.BlockCopy(System.Text.Encoding.ASCII.GetBytes("AEMR0003"),0,header,0,8);
                if(!AvatarProtocol.Authenticate(header,identity.Pin)) { stream.WriteByte(0); Reject(peer); return; }
                if(Interlocked.CompareExchange(ref authenticationDeadline,2,0)!=0) throw new TimeoutException("Pairing handshake expired");
                lock(gate) {
                    if(!running || owner!=null) { stream.WriteByte(2); return; }
                    owner=client; owns=true; rejected.Remove(peer); frames.Clear(); clearPreview();
                }
                deadline.Change(Timeout.Infinite,Timeout.Infinite); stream.WriteByte(1); stream.Flush(); stream.ReadTimeout=5000;
                status("Headset paired securely · waiting for a fresh avatar frame");
                WebcamDiagnostics.Record("paired");
                long lastSequence=-1,lastPublished=0; int count=0,lastWidth=0,lastHeight=0; bool blank=true,waitingReported=false;
                var timeline=new AvatarFrameTimeline();
                Action<long,byte> receipt=delegate(long seq,byte state) {
                    if(!receipts) return;
                    byte[] bytes=BitConverter.GetBytes(seq); if(BitConverter.IsLittleEndian) Array.Reverse(bytes);
                    stream.Write(bytes,0,bytes.Length); stream.WriteByte(state); stream.Flush();
                };
                Action pauseImage=delegate {
                    if(!blank) { frames.Clear(); clearPreview(); blank=true; lastPublished=0; }
                    if(!waitingReported) { waitingReported=true; status("Headset connected · waiting for a fresh mirror image"); WebcamDiagnostics.Record("image-paused"); }
                };
                using(var imageExpiry=new System.Threading.Timer(delegate { lock(gate) {
                    if(running && owner==client && !blank && (long)NativeCamera.GetTickCount64()-lastPublished>2000) pauseImage();
                } },null,250,250)) {
                while(running) {
                    long sequence=AvatarProtocol.ReadLong(stream),capture=AvatarProtocol.ReadLong(stream); int length=AvatarProtocol.ReadLength(stream);
                    if(sequence<=lastSequence || capture<=0 || length<0 || (length>0 && length<4) || length>(highDetail?8:3)*1024*1024) throw new InvalidDataException("Invalid avatar frame header");
                    lastSequence=sequence;
                    if(length==0) { lock(gate) { if(!running || owner!=client) break; pauseImage(); } receipt(sequence,0); continue; }
                    byte[] jpeg=AvatarProtocol.Read(stream,length); if(jpeg[0]!=0xff || jpeg[1]!=0xd8) throw new InvalidDataException("Expected a JPEG avatar frame");
                    int width,height;
                    if(!AvatarProtocol.JpegSize(jpeg,out width,out height) || !AvatarProtocol.ValidVideoSize(width,height) ||
                        (!highDetail && (width!=960 || height!=540))) throw new InvalidDataException("Invalid avatar image size");
                    long arrival=(long)NativeCamera.GetTickCount64(); AvatarFrameDecision decision;
                    lock(gate) { if(!running || owner!=client) break; decision=timeline.Observe(capture,arrival); }
                    if(decision!=AvatarFrameDecision.Accept) { WebcamDiagnostics.Record("frame-dropped",decision.ToString()); receipt(sequence,2); continue; }
                    using(var encoded=new MemoryStream(jpeg)) using(var image=Image.FromStream(encoded,false,true)) {
                        if(image.Width!=width || image.Height!=height) throw new InvalidDataException("Avatar image dimensions disagree");
                        Bitmap picture=new Bitmap(image);
                        try {
                            if(Background!=null) {
                                var composed=Background.Apply(picture);
                                if(!Object.ReferenceEquals(composed,picture)){picture.Dispose();picture=composed;}
                            }
                            lock(gate) {
                            if(!running || owner!=client) { picture.Dispose(); picture=null; break; }
                            if(!timeline.FreshAt(capture,(long)NativeCamera.GetTickCount64())) { WebcamDiagnostics.Record("frame-dropped","DecodeDelay"); receipt(sequence,2); continue; }
                            frames.Publish(picture); preview(picture); picture=null; lastPublished=(long)NativeCamera.GetTickCount64();
                            if(blank || waitingReported || lastWidth!=width || lastHeight!=height) { blank=false; waitingReported=false; lastWidth=width;lastHeight=height; status("Avatar webcam is live · "+width+" × "+height+" source · Up to 1920 × 1080 output"); WebcamDiagnostics.Record("image-live"); }
                        } } finally { if(picture!=null) picture.Dispose(); }
                    }
                    receipt(sequence,1);
                    count++;
                    if(maximumFrames>0 && count>=maximumFrames) { running=false; listener.Stop(); status("RECEIVED "+count); break; }
                }
                }
            }
        } catch(Exception error) {
            if(owns && running) { WebcamDiagnostics.Record("connection-ended",error.GetType().Name); status(error is EndOfStreamException?"Headset disconnected · webcam cleared · waiting to reconnect":"Stream paused · webcam cleared: "+error.Message); }
            else if(!owns && !(error is ObjectDisposedException)) { Reject(peer); if(maximumFrames>0) status("TEST authentication failed: "+error.ToString()); }
        } finally {
            lock(gate) { if(owner==client) { frames.Clear(); clearPreview(); owner=null; } clients.Remove(client); } client.Close();
        }
    }
    public void Wait() { if(acceptWorker!=null) acceptWorker.Wait(); Task[] pending; lock(gate) pending=handlers.ToArray(); Task.WaitAll(pending); }
    public void Dispose() { running=false; if(listener!=null) listener.Stop(); lock(gate) foreach(var client in clients) client.Close(); Wait(); frames.Clear(); clearPreview(); }
}

sealed class AdapterChoice {
    public readonly string Name; public readonly IPAddress Address;
    public AdapterChoice(string name,IPAddress address) { Name=name; Address=address; }
    public override string ToString() { return Name+" · "+Address; }
}

sealed partial class WebcamWindow : Form {
    readonly AvatarBackground background;
    FramePublisher frames; AvatarReceiver receiver; PairingIdentity identity; LanDiscovery discovery;
    TaskCompletionSource<bool> pairingApproval;
    Form pairingDialog;
    readonly PictureBox picture=new PictureBox(); readonly Label status=new Label(),address=new Label(); readonly TextBox pairing=new TextBox();
    readonly ComboBox adapters=new ComboBox(); readonly Button start=new Button(),install=new Button(),copy=new Button(),reset=new Button(),checkCamera=new Button(),manual=new Button(),changeBackground=new Button();
    readonly System.Windows.Forms.Timer timer=new System.Windows.Forms.Timer(); Bitmap latest; long previewGeneration; bool closing,installing;string statusMessage;
    void ShowStatus(string message) { if(closing || !IsHandleCreated) return;
        try { BeginInvoke((Action)delegate { if(!closing){statusMessage=message;RefreshLayoutState();} }); } catch(InvalidOperationException) { }
    }
    void ReceiveFrame(Bitmap bitmap) { Interlocked.Increment(ref previewGeneration); if(closing) { bitmap.Dispose(); return; } var old=Interlocked.Exchange(ref latest,bitmap); if(old!=null) old.Dispose(); }
    void ClearPreview() {
        long generation=Interlocked.Increment(ref previewGeneration); var pending=Interlocked.Exchange(ref latest,null); if(pending!=null) pending.Dispose();
        if(closing || !IsHandleCreated) return;
        try { BeginInvoke((Action)delegate { if(previewGeneration!=generation || closing) return; var old=picture.Image; picture.Image=null; if(old!=null) old.Dispose(); }); } catch(InvalidOperationException) { }
    }
    void StopReceiver() {
        if(pairingApproval!=null) { pairingApproval.TrySetResult(false); pairingApproval=null; }
        if(discovery!=null) { discovery.Dispose(); discovery=null; }
        if(receiver!=null) { receiver.Dispose(); receiver=null; } NativeCamera.EndAvatarCamera(); if(frames!=null) { frames.Dispose(); frames=null; }
        ClearPreview(); start.Text="Start receiver"; start.Enabled=!installing; adapters.Enabled=true; reset.Enabled=true; copy.Enabled=false;
        address.Text="Receiver stopped · webcam removed"; pairing.Text="Start the receiver to display your pairing key.";
    }
    async void StartReceiver(object sender,EventArgs args) {
        if(receiver!=null) { StopReceiver(); ShowStatus("Receiver stopped · avatar cleared"); return; } start.Enabled=false; install.Enabled=false; reset.Enabled=false; adapters.Enabled=false;
        try {
            if(identity==null) identity=PairingIdentity.LoadOrCreate(); frames=new FramePublisher();
            string channel=frames.Path;
            int result=await Task.Run(delegate { return NativeCamera.BeginAvatarCamera(channel); }); if(closing) { NativeCamera.EndAvatarCamera(); return; }
            if(result<0) throw new InvalidOperationException("Webcam could not start (0x"+result.ToString("X8")+"). Check Windows camera privacy, or install the webcam first.");
            var choice=(AdapterChoice)adapters.SelectedItem; receiver=new AvatarReceiver(frames,ReceiveFrame,ClearPreview,ShowStatus);
            receiver.Background=background;
            receiver.ConfirmPairing=ConfirmPairing; receiver.Start(choice.Address,38991,identity);
            receiver.CancelPairing=delegate {
                var pending=pairingApproval; if(pending!=null) pending.TrySetResult(false);
                if(!closing && IsHandleCreated) BeginInvoke((Action)delegate { if(pairingDialog!=null) { pairingDialog.Close(); pairingDialog=null; } });
            };
            discovery=new LanDiscovery(); discovery.Start(choice.Address,identity);
            start.Text="Stop receiver"; adapters.Enabled=false; reset.Enabled=false; copy.Enabled=true; address.Text="Quest PC address: "+choice.Address+":38991"; pairing.Text=identity.PairingKey;
            ShowStatus("Webcam ready · Choose Find PC on Quest, then compare the matching code on both devices.");
        } catch(Exception error) { StopReceiver(); ShowStatus(error.Message); } finally { if(!closing) { start.Enabled=true; install.Enabled=true; } }
    }
    void ResetPairing(object sender,EventArgs args) {
        if(receiver!=null) return;
        try { var replacement=PairingIdentity.LoadOrCreate(true); if(identity!=null) identity.Dispose(); identity=replacement; ShowStatus("Pairing reset. Choose Forget pairing on Quest, then use the new key when you start the receiver."); }
        catch(Exception error) { ShowStatus(error.Message); }
    }
    async void CheckCamera(object sender,EventArgs args) {
        if(receiver==null) { ShowStatus("Start the receiver first, then choose Check camera."); return; }
        checkCamera.Enabled=false;
        try {
            string path=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"MediaCaptureProbe.exe");
            var launch=new System.Diagnostics.ProcessStartInfo(path) { UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true };
            using(var process=System.Diagnostics.Process.Start(launch)) {
                string output=await Task.Run(delegate {
                    var read=process.StandardOutput.ReadToEndAsync();
                    if(!process.WaitForExit(30000)) { process.Kill(); throw new TimeoutException("Camera check timed out. Close other camera apps and retry."); }
                    return read.Result;
                });
                ShowStatus(process.ExitCode==0?"Windows camera check passed · Live wearer video still needs Quest mirror open":"Windows camera check failed · Restart receiver/call app; check Windows camera permission. "+output.Trim());
            }
        } catch(Exception error) { ShowStatus("Camera check: "+error.Message); }
        finally { if(!closing) checkCamera.Enabled=true; }
    }
    Task<bool> ConfirmPairing(string code) {
        var approval=new TaskCompletionSource<bool>(); pairingApproval=approval;
        BeginInvoke((Action)delegate {
            if(closing || receiver==null || approval.Task.IsCompleted) { approval.TrySetResult(false); return; }
            pairingDialog=new WebcamPairingWindow(code,
                delegate{approval.TrySetResult(true);},delegate{approval.TrySetResult(false);});
            pairingDialog.Show(this);
        });
        return approval.Task;
    }
    async void InstallCamera(object sender,EventArgs args) {
        if(installing) return; installing=true; StopReceiver(); install.Enabled=false;
        try {
            string script=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"InstallWebcam.ps1"),sid=WindowsIdentity.GetCurrent().User.Value;
            var launch=new System.Diagnostics.ProcessStartInfo("powershell.exe","-NoProfile -ExecutionPolicy Bypass -File \""+script+"\" -UserSid \""+sid+"\"");
            launch.UseShellExecute=true; launch.Verb="runas"; launch.WindowStyle=System.Diagnostics.ProcessWindowStyle.Hidden; var process=System.Diagnostics.Process.Start(launch);
            string installed=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"AlterEgo MR Webcam","AlterEgoWebcam.exe");
            if(string.Equals(Path.GetFullPath(Application.ExecutablePath),Path.GetFullPath(installed),StringComparison.OrdinalIgnoreCase)) { ShowStatus("Finish Windows installation, then reopen AlterEgo MR Webcam from Start."); Close(); return; }
            await Task.Run(delegate { process.WaitForExit(); }); if(closing) return;
            if(process.ExitCode!=0) { ShowStatus("Installation did not finish. Reopen the installer to check Windows permissions."); return; }
            Program.RelaunchExecutable=installed; Close();
        } catch(Exception error) { ShowStatus("Installation did not run: "+error.Message); } finally { installing=false; if(!closing) { install.Enabled=true; start.Enabled=true; } }
    }
    void OnClosing(object sender,FormClosingEventArgs e) {
        closing=true; timer.Stop(); if(!layoutPreview)StopReceiver();else ClearPreview();background.Dispose(); if(identity!=null) { identity.Dispose(); identity=null; } var old=picture.Image; picture.Image=null; if(old!=null) old.Dispose();
    }
}

static class Program {
    internal static string RelaunchExecutable;
    static Bitmap Fixture(bool orientation) { var image=new Bitmap(960,540); using(var graphics=Graphics.FromImage(image)) { graphics.Clear(Color.CornflowerBlue); if(orientation) using(var brush=new SolidBrush(Color.FromArgb(53,31,17))) graphics.FillRectangle(brush,0,539,960,1); } return image; }
    [STAThread] static void Main(string[] args) {
        try {
            if(args.Length==0) {
                string installed=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"AlterEgo MR Webcam","AlterEgoWebcam.exe");
                if(File.Exists(installed) && !string.Equals(Path.GetFullPath(Application.ExecutablePath),Path.GetFullPath(installed),StringComparison.OrdinalIgnoreCase)
                    && AssemblyName.GetAssemblyName(installed).Version>=Assembly.GetExecutingAssembly().GetName().Version) { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(installed) { UseShellExecute=true }); return; }
            }
            string sid=WindowsIdentity.GetCurrent().User.Value; bool instanceCreated;
            bool isolated=args.Length>0 && args[0]!="--registered-camera-test";
            using(var instance=new Mutex(true,"Global\\AlterEgoMRAvatarReceiver_"+sid+(isolated?"_Test":""),out instanceCreated)) {
                if(!instanceCreated) throw new InvalidOperationException("AlterEgo MR Webcam is already running for this Windows user.");
                bool localTest=args.Length>0 && args[0]!="--registered-camera-test";
                if(localTest) { Environment.SetEnvironmentVariable("ALTEREGO_MR_FRAME_FILE",Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"frames.dat")); Environment.SetEnvironmentVariable("ALTEREGO_MR_STATE_DIR",Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"test-state")); }
                if(args.Length==1 && args[0]=="--background-test") {AvatarBackgroundTests.Run();return;}
                if(args.Length==1 && args[0]=="--self-test") {
                    using(var frames=new FramePublisher()) using(var image=Fixture(true)) { frames.Publish(image); int result=NativeCamera.TestAvatarSource(); Console.WriteLine("Media source test: 0x"+result.ToString("X8")); Environment.ExitCode=result<0?1:0; } return;
                }
                if(args.Length==3 && (args[0]=="--receiver-test" || args[0]=="--easy-pairing-test" || args[0]=="--receiver-background-test")) {
                    using(var frames=new FramePublisher()) using(var identity=PairingIdentity.LoadOrCreate())
                    using(var background=new AvatarBackground(Path.Combine(Environment.GetEnvironmentVariable("ALTEREGO_MR_STATE_DIR"),"receiver-background")))
                    using(var receiver=new AvatarReceiver(frames,delegate(Bitmap b) { b.Dispose(); },delegate { },Console.WriteLine)) {
                        if(args[0]=="--receiver-background-test") {background.Configure(AvatarBackgroundStyle.Colour,Color.FromArgb(150,20,55),26);receiver.Background=background;}
                        if(args[0]=="--easy-pairing-test") receiver.ConfirmPairing=delegate(string code) { Console.WriteLine("CONFIRM "+code); return Task.Run(delegate { return Console.ReadLine()=="APPROVE"; }); };
                        receiver.Start(IPAddress.Loopback,int.Parse(args[1]),identity,int.Parse(args[2]));
                        using(var discovery=new LanDiscovery()) {
                            if(args[0]=="--easy-pairing-test") discovery.Start(IPAddress.Loopback,identity);
                            Console.WriteLine("READY "+identity.Pin+" "+identity.Fingerprint); receiver.Wait();
                        }
                    } return;
                }
                if(args.Length==1 && args[0]=="--pairing-test") {
                    using(var identity=PairingIdentity.LoadOrCreate()) {
                        using(var reloaded=PairingIdentity.LoadOrCreate()) if(identity.Fingerprint!=reloaded.Fingerprint || identity.Pin!=reloaded.Pin) throw new InvalidDataException("Pairing identity changed on reload");
                        File.WriteAllBytes(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"test-certificate.cer"),identity.Certificate.RawData); Console.WriteLine("Pairing identity persists; public fingerprint "+identity.Fingerprint);
                    } return;
                }
                if(args.Length==1 && args[0]=="--registered-camera-test") {
                    using(var frames=new FramePublisher()) using(var image=Fixture(false)) {
                        frames.Publish(image); var refresh=new System.Threading.Timer(delegate { frames.Publish(image); },null,250,250);
                        try { int result=NativeCamera.TestRegisteredAvatarCamera(frames.Path); Console.WriteLine("Registered camera test: 0x"+result.ToString("X8")); Environment.ExitCode=result<0?1:0; }
                        finally { using(var finished=new ManualResetEvent(false)) { refresh.Dispose(finished); finished.WaitOne(); } }
                    } return;
                }
                Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
                if(args.Length==1 && args[0]=="--ui-layout-test") {WebcamLayoutCheck.Run();return;}
                if(args.Length==1 && args[0]=="--background-ui-preview") {
                    using(var background=new AvatarBackground(Path.Combine(Environment.GetEnvironmentVariable("ALTEREGO_MR_STATE_DIR"),"ui-background")))
                    using(var window=new AvatarBackgroundWindow(background))using(var bitmap=new Bitmap(window.Width,window.Height)) {
                        window.Opacity=0;window.ShowInTaskbar=false;window.Show();Application.DoEvents();window.PerformLayout();
                        window.DrawToBitmap(bitmap,new Rectangle(0,0,bitmap.Width,bitmap.Height));bitmap.Save(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"background-preview.png"));
                    }return;
                }
                if(args.Length==1 && args[0]=="--ui-preview") {
                    using(var window=new WebcamWindow(true)) using(var bitmap=new Bitmap(window.Width,window.Height)) { window.Opacity=0; window.ShowInTaskbar=false; window.Show(); Application.DoEvents(); window.PerformLayout(); window.DrawToBitmap(bitmap,new Rectangle(0,0,bitmap.Width,bitmap.Height)); bitmap.Save(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"preview.png")); } return;
                }
                if(args.Length>0) throw new ArgumentException("Unknown option"); Application.Run(new WebcamWindow());
            }
            // Release the receiver mutex before the installed copy starts.
            if(RelaunchExecutable!=null) System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(RelaunchExecutable) { UseShellExecute=true });
        } catch(Exception error) { if(args.Length>0) { Console.WriteLine(error.ToString()); Environment.ExitCode=1; } else MessageBox.Show(error.Message,"AlterEgo MR Webcam"); }
    }
}

