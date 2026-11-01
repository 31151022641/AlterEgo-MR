using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Threading.Tasks;

// Generated shapes only. No webcam, account avatar, personal image or installed
// frame channel is used. The receiver integration test also uses these shapes.
static class AvatarBackgroundTests {
    static void Check(bool valid,string failure) {if(!valid)throw new InvalidDataException(failure);}
    internal static Bitmap Fixture(int width=960,int height=540,bool gradient=false) {
        var bitmap=new Bitmap(width,height,PixelFormat.Format32bppArgb);
        using(var graphics=Graphics.FromImage(bitmap)) {
            if(gradient)using(var brush=new LinearGradientBrush(new Rectangle(0,0,width,height),Color.FromArgb(220,230,240),Color.FromArgb(184,200,215),90f))graphics.FillRectangle(brush,0,0,width,height);
            else graphics.Clear(Color.FromArgb(220,230,240));
            graphics.ScaleTransform((float)width/960,(float)height/540);
            using(var hair=new SolidBrush(Color.FromArgb(16,20,25)))graphics.FillRectangle(hair,390,60,180,70);
            using(var skin=new SolidBrush(Color.FromArgb(210,142,95))) {
                graphics.FillEllipse(skin,400,90,160,170);
                graphics.FillRectangle(skin,80,285,110,60);graphics.FillRectangle(skin,770,285,110,60);
            }
            using(var clothing=new SolidBrush(Color.FromArgb(45,85,145)))graphics.FillRectangle(clothing,300,255,360,285);
            // Same colour as the background, but enclosed inside the body.
            // Connected keying must preserve it instead of punching a hole.
            using(var logo=new SolidBrush(Color.FromArgb(220,230,240)))graphics.FillRectangle(logo,445,310,70,60);
        } return bitmap;
    }
    static void DisposeResult(Bitmap result,Bitmap source) {if(!Object.ReferenceEquals(result,source))result.Dispose();}
    public static void Run() {
        string root=Environment.GetEnvironmentVariable("ALTEREGO_MR_STATE_DIR");
        if(string.IsNullOrEmpty(root))throw new InvalidOperationException("Background tests require isolated test state");
        string folder=Path.Combine(root,"background-fixture-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(folder);
        int checks=0;
        using(var source=Fixture())using(var background=new AvatarBackground(folder)) {
            Check(Object.ReferenceEquals(source,background.Apply(source)),"Original mode changed image ownership");checks++;
            background.Configure(AvatarBackgroundStyle.Colour,Color.FromArgb(150,20,55),26);
            using(var output=background.Apply(source)) {
                Check(output.GetPixel(2,2).ToArgb()==Color.FromArgb(150,20,55).ToArgb(),"Plain background was not replaced");checks++;
                foreach(var point in new[]{new Point(450,70),new Point(480,160),new Point(100,300),new Point(820,300),new Point(350,400)})
                    Check(output.GetPixel(point.X,point.Y)==source.GetPixel(point.X,point.Y),"Hair, face, hand or body detail changed");checks++;
                Check(output.GetPixel(480,340)==source.GetPixel(480,340),"Background-coloured enclosed clothing disappeared");checks++;
                Check(output.GetPixel(2,2).A==255 && output.GetPixel(480,160).A==255,"Camera composition is not opaque");checks++;
            }
            using(var gradient=Fixture(960,540,true))using(var output=background.Apply(gradient)) {
                Check(output.GetPixel(2,2).R==150 && output.GetPixel(2,537).R==150,"Studio gradient was not removed");checks++;
            }
            using(var covered=Fixture()) {
                using(var graphics=Graphics.FromImage(covered))using(var hand=new SolidBrush(Color.FromArgb(210,142,95)))graphics.FillRectangle(hand,0,0,90,90);
                using(var output=background.Apply(covered))Check(output.GetPixel(10,10)==covered.GetPixel(10,10) && output.GetPixel(950,5).R==150,"A hand at one corner broke the cutout");checks++;
            }
            using(var expected=new AvatarBackground(folder))Check(expected.Style==AvatarBackgroundStyle.Colour && expected.Colour.R==150 && expected.Strength==26,"Preferences did not survive reload");checks++;
            string image=Path.Combine(folder,"synthetic-picture.png");
            using(var pattern=new Bitmap(1920,1080)) {
                using(var graphics=Graphics.FromImage(pattern)){graphics.Clear(Color.Red);using(var blue=new SolidBrush(Color.Blue))graphics.FillRectangle(blue,0,540,1920,540);}
                pattern.Save(image,ImageFormat.Png);
            }
            background.ChoosePicture(image);
            using(var output=background.Apply(source)) {
                Check(output.GetPixel(2,2).R>240 && output.GetPixel(2,537).B>240,"Picture background orientation changed");
                Check(output.GetPixel(480,160)==source.GetPixel(480,160),"Picture changed the foreground");checks++;
            }
            using(var reloaded=new AvatarBackground(folder))using(var output=reloaded.Apply(source)) {
                Check(reloaded.Style==AvatarBackgroundStyle.Picture && output.GetPixel(2,537).B>240,"Saved picture did not reload");checks++;
            }
            string invalid=Path.Combine(folder,"invalid.png");File.WriteAllBytes(invalid,new byte[]{1,2,3});
            bool rejected=false;try{background.ChoosePicture(invalid);}catch(InvalidDataException){rejected=true;}
            Check(rejected && background.Style==AvatarBackgroundStyle.Picture,"Bad picture replaced the active choice");checks++;
            byte[] bomb=new byte[33];Array.Copy(new byte[]{137,80,78,71,13,10,26,10},bomb,8);
            bomb[12]=73;bomb[13]=72;bomb[14]=68;bomb[15]=82;bomb[16]=127;bomb[17]=255;bomb[18]=255;bomb[19]=255;bomb[20]=127;
            File.WriteAllBytes(invalid,bomb);rejected=false;try{using(var ignored=AvatarBackground.LoadPicture(invalid)){} }catch(InvalidDataException){rejected=true;}
            Check(rejected,"Oversized image was sent to the decoder");checks++;
            background.Configure(AvatarBackgroundStyle.GreenScreen,Color.Black,26);
            using(var output=background.Apply(source))Check(output.GetPixel(2,2).ToArgb()==Color.Lime.ToArgb(),"Green screen is not pure green");checks++;
            using(var detailed=new Bitmap(960,540)) {
                using(var graphics=Graphics.FromImage(detailed))for(int x=0;x<960;x+=3)using(var brush=new SolidBrush(x%2==0?Color.Black:Color.White))graphics.FillRectangle(brush,x,0,3,540);
                Check(Object.ReferenceEquals(detailed,background.Apply(detailed)),"Detailed corner backdrop was incorrectly keyed");checks++;
            }
            using(var large=Fixture(1920,1080)) {
                for(int warmup=0;warmup<2;warmup++){var output=background.Apply(large);DisposeResult(output,large);}
                var watch=Stopwatch.StartNew();
                for(int frame=0;frame<10;frame++){var output=background.Apply(large);DisposeResult(output,large);}
                watch.Stop();Console.WriteLine("Synthetic 1080p background average ms: "+(watch.Elapsed.TotalMilliseconds/10).ToString("F1",System.Globalization.CultureInfo.InvariantCulture));
                Check(watch.Elapsed.TotalMilliseconds/10<150,"Background processing exceeds its local frame budget");checks++;
            }
            var updates=Task.Run(delegate{for(int step=0;step<12;step++)background.Configure(step%2==0?AvatarBackgroundStyle.DarkStudio:AvatarBackgroundStyle.LightStudio,Color.Black,26);});
            for(int frame=0;frame<12;frame++){var output=background.Apply(source);Check(!output.Size.IsEmpty,"Concurrent selection lost a frame");DisposeResult(output,source);}
            updates.Wait();checks++;
        }
        Console.WriteLine("Synthetic background checks passed: "+checks);
    }
}
