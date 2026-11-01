using System;
using System.IO;
using System.Reflection;
using System.Diagnostics;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Windows.Forms;

// Complete offline bootstrapper. Capture the user's SID before requesting UAC.
static class Setup {
    [STAThread] static int Main(string[] args) {
        string directory=null;
        try {
            string sid=WindowsIdentity.GetCurrent().User.Value;
            string root=Path.Combine(Path.GetTempPath(),"AlterEgoMR-Setup");
            if(Directory.Exists(root) && (File.GetAttributes(root)&FileAttributes.ReparsePoint)!=0) throw new IOException("Setup folder is redirected");
            Directory.CreateDirectory(root);
            directory=Path.Combine(root,Guid.NewGuid().ToString("N"));
            var acl=new DirectorySecurity(); acl.SetAccessRuleProtection(true,false);
            foreach(string identity in new[]{sid,"S-1-5-18","S-1-5-32-544"})
                acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(identity),FileSystemRights.FullControl,InheritanceFlags.ContainerInherit|InheritanceFlags.ObjectInherit,PropagationFlags.None,AccessControlType.Allow));
            Directory.CreateDirectory(directory,acl);
            var assembly=Assembly.GetExecutingAssembly();
            foreach(string name in assembly.GetManifestResourceNames()) {
                if(name.Contains("\\") || name.Contains("/") || name.Contains("..")) throw new IOException("Invalid bundle filename");
                using(var input=assembly.GetManifestResourceStream(name)) using(var output=File.Create(Path.Combine(directory,name))) input.CopyTo(output);
            }
            bool validate=args.Length==1 && args[0]=="--validate-only";
            var launch=new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),"WindowsPowerShell\\v1.0\\powershell.exe"),
                "-NoProfile -ExecutionPolicy Bypass -File \""+Path.Combine(directory,"InstallWebcam.ps1")+"\" -UserSid \""+sid+"\""+(validate?" -ValidateOnly":""));
            launch.UseShellExecute=!validate; launch.WindowStyle=ProcessWindowStyle.Hidden;
            if(validate) { launch.CreateNoWindow=true; launch.RedirectStandardOutput=true; launch.RedirectStandardError=true; }
            if(!validate) launch.Verb="runas";
            using(var process=Process.Start(launch)) {
                var output=validate?process.StandardOutput.ReadToEndAsync():null;
                var error=validate?process.StandardError.ReadToEndAsync():null;
                process.WaitForExit();
                if(validate) { Console.WriteLine(output.Result); Console.Error.WriteLine(error.Result); }
                if(process.ExitCode!=0) throw new IOException("Installation did not finish. Close webcam/video-call apps and try again.");
            }
            if(!validate) Process.Start(new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"AlterEgo MR Webcam","AlterEgoWebcam.exe")) { UseShellExecute=true });
            return 0;
        } catch(Exception error) {
            if(args.Length==0) MessageBox.Show(error.Message,"AlterEgo MR setup",MessageBoxButtons.OK,MessageBoxIcon.Error);
            else Console.Error.WriteLine(error.Message);
            return 1;
        } finally {
            // Only the exact GUID stage created above may be retired.
            if(directory!=null && Path.GetFileName(directory).Length==32 && directory.StartsWith(Path.Combine(Path.GetTempPath(),"AlterEgoMR-Setup")+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)) {
                try { Directory.Delete(directory,true); } catch { }
            }
        }
    }
}
