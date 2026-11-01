using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Globalization;
using System.Threading.Tasks;
using System.Windows.Forms;

enum AvatarBackgroundStyle { Original, DarkStudio, LightStudio, Colour, Picture, GreenScreen }

// A local, border-connected colour matte for the OS avatar's plain backdrop.
// It is not person segmentation and never claims to remove arbitrary scenery.
sealed unsafe class AvatarBackground : IDisposable {
    readonly object gate=new object();
    readonly string folder;
    AvatarBackgroundStyle style=AvatarBackgroundStyle.Original;
    Color colour=Color.FromArgb(38,64,92);
    int strength=26;
    Bitmap photo,backdrop;
    int cacheWidth,cacheHeight,cacheRevision=-1,revision;
    byte[] distance,connected;
    int[] queue;
    bool disposed;
    volatile string notice="Original avatar background";
    public string Notice { get { return notice; } }
    public AvatarBackgroundStyle Style { get { lock(gate) return style; } }
    public int Strength { get { lock(gate) return strength; } }
    public Color Colour { get { lock(gate) return colour; } }
    public bool HasPicture { get { lock(gate) return photo!=null; } }
    public AvatarBackground(string stateFolder=null) {
        folder=stateFolder ?? Environment.GetEnvironmentVariable("ALTEREGO_MR_STATE_DIR") ??
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"AlterEgoMR");
        try {
            string path=Path.Combine(folder,"background.txt");
            if(File.Exists(path)) {
                string[] values=File.ReadAllLines(path);int mode,argb,value;
                if(values.Length==3 && int.TryParse(values[0],out mode) && Enum.IsDefined(typeof(AvatarBackgroundStyle),mode)
                    && int.TryParse(values[1],out argb) && int.TryParse(values[2],out value) && value>=12 && value<=60) {
                    style=(AvatarBackgroundStyle)mode;colour=Color.FromArgb(255,Color.FromArgb(argb));strength=value;
                }
            }
            string picture=Path.Combine(folder,"background.png");
            if(File.Exists(picture)) photo=LoadPicture(picture);
            if(style==AvatarBackgroundStyle.Picture && photo==null)style=AvatarBackgroundStyle.Original;
        } catch { style=AvatarBackgroundStyle.Original; }
    }
    void Save() {
        Directory.CreateDirectory(folder);
        string destination=Path.Combine(folder,"background.txt"),temporary=destination+".tmp";
        File.WriteAllLines(temporary,new[]{((int)style).ToString(CultureInfo.InvariantCulture),
            colour.ToArgb().ToString(CultureInfo.InvariantCulture),strength.ToString(CultureInfo.InvariantCulture)});
        if(File.Exists(destination)) File.Replace(temporary,destination,null);else File.Move(temporary,destination);
    }
    public void Configure(AvatarBackgroundStyle selected,Color selectedColour,int edgeStrength) { lock(gate) {
        if(disposed)throw new ObjectDisposedException("Avatar background");
        if(!Enum.IsDefined(typeof(AvatarBackgroundStyle),selected) || edgeStrength<12 || edgeStrength>60)
            throw new ArgumentException("Invalid background choice");
        if(selected==AvatarBackgroundStyle.Picture && photo==null)throw new InvalidOperationException("Choose a PNG/JPG picture first.");
        style=selected;colour=Color.FromArgb(255,selectedColour);strength=edgeStrength;revision++;
        notice=style==AvatarBackgroundStyle.Original?"Original avatar background":"Background applies to the preview and webcam";
        Save();
    } }
    public void ChoosePicture(string path) {
        using(var loaded=LoadPicture(path)) {lock(gate) {
            if(disposed)throw new ObjectDisposedException("Avatar background");
            Directory.CreateDirectory(folder);
            string destination=Path.Combine(folder,"background.png"),temporary=destination+".tmp";
            loaded.Save(temporary,ImageFormat.Png);
            if(File.Exists(destination))File.Replace(temporary,destination,null);else File.Move(temporary,destination);
            var old=photo;photo=new Bitmap(loaded);if(old!=null)old.Dispose();
            style=AvatarBackgroundStyle.Picture;revision++;notice="Picture background saved on this PC";Save();
        } }
    }
    internal static Bitmap LoadPicture(string path) {
        byte[] bytes;
        using(var file=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read)) {
            if(file.Length<=0 || file.Length>16*1024*1024)throw new InvalidDataException("Choose a PNG/JPG up to 16 MB.");
            bytes=new byte[(int)file.Length];int count=0;
            while(count<bytes.Length){int read=file.Read(bytes,count,bytes.Length-count);if(read<=0)throw new EndOfStreamException("Incomplete picture file.");count+=read;}
        }
        int width=0,height=0;
        bool png=bytes.Length>=33 && bytes[0]==137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71 &&
            bytes[4]==13 && bytes[5]==10 && bytes[6]==26 && bytes[7]==10 &&
            bytes[12]==73 && bytes[13]==72 && bytes[14]==68 && bytes[15]==82;
        if(png) {
            width=(bytes[16]<<24)|(bytes[17]<<16)|(bytes[18]<<8)|bytes[19];
            height=(bytes[20]<<24)|(bytes[21]<<16)|(bytes[22]<<8)|bytes[23];
        } else if(!AvatarProtocol.JpegSize(bytes,out width,out height))throw new InvalidDataException("Choose a valid PNG or JPG picture.");
        if(width<=0 || height<=0 || width>8192 || height>8192 || (long)width*height>32000000)
            throw new InvalidDataException("The background picture is too large. Use up to 32 million pixels.");
        using(var encoded=new MemoryStream(bytes))using(var image=Image.FromStream(encoded,true,true)) {
            if(image.Width!=width || image.Height!=height ||
                image.RawFormat.Guid!=(png?ImageFormat.Png:ImageFormat.Jpeg).Guid)throw new InvalidDataException("Invalid picture format.");
            if(Array.IndexOf(image.PropertyIdList,0x0112)>=0) {
                byte[] orientation=image.GetPropertyItem(0x0112).Value;
                int value=orientation.Length>=2?orientation[0]|(orientation[1]<<8):1;
                var rotations=new[]{RotateFlipType.RotateNoneFlipNone,RotateFlipType.RotateNoneFlipX,
                    RotateFlipType.Rotate180FlipNone,RotateFlipType.Rotate180FlipX,RotateFlipType.Rotate90FlipX,
                    RotateFlipType.Rotate90FlipNone,RotateFlipType.Rotate270FlipX,RotateFlipType.Rotate270FlipNone};
                if(value>=1 && value<=8)image.RotateFlip(rotations[value-1]);
            }
            // Keep a bounded, detached private picture. Transparent files sit on
            // a studio colour; the Windows video formats carry opaque RGB/YUV.
            var output=new Bitmap(1920,1080,PixelFormat.Format32bppArgb);
            try { using(var graphics=Graphics.FromImage(output)) {
                graphics.Clear(Color.FromArgb(38,64,92));graphics.InterpolationMode=InterpolationMode.HighQualityBicubic;
                DrawCover(graphics,image,1920,1080);
            } return output; } catch {output.Dispose();throw;}
        }
    }
    static void DrawCover(Graphics graphics,Image image,int width,int height) {
        float ratio=Math.Max((float)width/image.Width,(float)height/image.Height);
        float drawWidth=image.Width*ratio,drawHeight=image.Height*ratio;
        graphics.DrawImage(image,new RectangleF((width-drawWidth)/2,(height-drawHeight)/2,drawWidth,drawHeight));
    }
    void EnsureBackdrop(int width,int height) {
        if(backdrop!=null && cacheWidth==width && cacheHeight==height && cacheRevision==revision)return;
        if(backdrop!=null)backdrop.Dispose();backdrop=new Bitmap(width,height,PixelFormat.Format32bppArgb);
        using(var graphics=Graphics.FromImage(backdrop)) {
            if(style==AvatarBackgroundStyle.Picture) {graphics.InterpolationMode=InterpolationMode.HighQualityBicubic;DrawCover(graphics,photo,width,height);}
            else if(style==AvatarBackgroundStyle.DarkStudio || style==AvatarBackgroundStyle.LightStudio) {
                Color top=style==AvatarBackgroundStyle.DarkStudio?Color.FromArgb(42,65,87):Color.FromArgb(239,244,249);
                Color bottom=style==AvatarBackgroundStyle.DarkStudio?Color.FromArgb(11,20,34):Color.FromArgb(181,199,215);
                using(var gradient=new LinearGradientBrush(new Rectangle(0,0,width,height),top,bottom,90f))graphics.FillRectangle(gradient,0,0,width,height);
            } else graphics.Clear(style==AvatarBackgroundStyle.GreenScreen?Color.Lime:colour);
        }
        cacheWidth=width;cacheHeight=height;cacheRevision=revision;
        int size=checked(width*height);
        if(distance==null || distance.Length!=size){distance=new byte[size];connected=new byte[size];queue=new int[size];}
    }
    static int Difference(int a,int b) {return Math.Abs(a-b);}
    static int Clamp(int value) {return Math.Max(0,Math.Min(255,value));}
    // Small corner medians withstand JPEG noise. Each corner is sampled
    // independently so a simple studio gradient can also be removed.
    static int[] Corner(BitmapData pixels,int width,int height,bool right,bool bottom,out int spread) {
        var channels=new[]{new int[81],new int[81],new int[81]};int n=0;
        for(int y=0;y<9;y++)for(int x=0;x<9;x++) {
            int px=Math.Min(width-1,1+x*Math.Max(1,width/220)),py=Math.Min(height-1,1+y*Math.Max(1,height/220));
            if(right)px=width-1-px;if(bottom)py=height-1-py;
            byte* value=(byte*)pixels.Scan0+py*pixels.Stride+px*4;
            for(int c=0;c<3;c++)channels[c][n]=value[c];n++;
        }
        int[] median=new int[3];spread=0;
        for(int c=0;c<3;c++){Array.Sort(channels[c]);median[c]=channels[c][40];spread=Math.Max(spread,channels[c][72]-channels[c][8]);}
        return median;
    }
    static int ColourDistance(int[] left,int[] right) {
        return Math.Max(Difference(left[0],right[0]),Math.Max(Difference(left[1],right[1]),Difference(left[2],right[2])));
    }
    static bool PlainCorners(int[][] corners,int[] spreads,int maximumSpread) {
        int best=-1,bestCount=0;
        for(int candidate=0;candidate<4;candidate++) {
            if(spreads[candidate]>maximumSpread)continue;
            int count=0;for(int other=0;other<4;other++)
                if(spreads[other]<=maximumSpread && ColourDistance(corners[candidate],corners[other])<=72)count++;
            if(count>bestCount){best=candidate;bestCount=count;}
        }
        if(bestCount<2)return false;
        var reference=new int[3];
        for(int channel=0;channel<3;channel++) {
            int[] values=new int[bestCount];int index=0;
            for(int corner=0;corner<4;corner++)if(spreads[corner]<=maximumSpread && ColourDistance(corners[best],corners[corner])<=72)
                values[index++]=corners[corner][channel];
            Array.Sort(values);reference[channel]=values[values.Length/2];
        }
        // A raised hand can cover one corner. Fill that corner from the
        // agreeing background corners instead of alternating effects on/off.
        int[] anchor=corners[best];
        for(int corner=0;corner<4;corner++)if(spreads[corner]>maximumSpread || ColourDistance(anchor,corners[corner])>72)
            corners[corner]=(int[])reference.Clone();
        return true;
    }
    public Bitmap Apply(Bitmap source) {lock(gate) {
        if(disposed || style==AvatarBackgroundStyle.Original)return source;
        int width=source.Width,height=source.Height;
        if(!AvatarProtocol.ValidVideoSize(width,height))throw new InvalidDataException("Invalid avatar image size");
        EnsureBackdrop(width,height);
        Bitmap output=new Bitmap(width,height,PixelFormat.Format32bppArgb);
        BitmapData input=null,background=null,result=null;bool transferred=false;
        try {
            input=source.LockBits(new Rectangle(0,0,width,height),ImageLockMode.ReadOnly,PixelFormat.Format32bppArgb);
            int a,b,c,d;
            int[] tl=Corner(input,width,height,false,false,out a),tr=Corner(input,width,height,true,false,out b),
                bl=Corner(input,width,height,false,true,out c),br=Corner(input,width,height,true,true,out d);
            var corners=new[]{tl,tr,bl,br};
            if(!PlainCorners(corners,new[]{a,b,c,d},Math.Max(24,strength))) {
                notice="Backdrop is not plain at the corners. Original video retained; try a plainer backdrop.";
                return source;
            }
            tl=corners[0];tr=corners[1];bl=corners[2];br=corners[3];
            int inner=Math.Max(4,strength-12),outer=strength+12,size=width*height;
            Array.Clear(connected,0,size);
            for(int y=0;y<height;y++) {
                float t=(float)y/(height-1),lb=tl[0]+(bl[0]-tl[0])*t,lg=tl[1]+(bl[1]-tl[1])*t,lr=tl[2]+(bl[2]-tl[2])*t;
                float sb=(tr[0]+(br[0]-tr[0])*t-lb)/(width-1),sg=(tr[1]+(br[1]-tr[1])*t-lg)/(width-1),sr=(tr[2]+(br[2]-tr[2])*t-lr)/(width-1);
                byte* row=(byte*)input.Scan0+y*input.Stride;int offset=y*width;
                for(int x=0;x<width;x++) {
                    distance[offset+x]=(byte)Math.Min(255,Math.Max(Difference(row[x*4],(int)lb),
                        Math.Max(Difference(row[x*4+1],(int)lg),Difference(row[x*4+2],(int)lr))));
                    lb+=sb;lg+=sg;lr+=sr;
                }
            }
            int tail=0;
            for(int x=0;x<width;x++){Seed(x,outer,ref tail);Seed((height-1)*width+x,outer,ref tail);}
            for(int y=1;y<height-1;y++){Seed(y*width,outer,ref tail);Seed(y*width+width-1,outer,ref tail);}
            for(int head=0;head<tail;head++) {
                int index=queue[head],x=index%width;
                if(x>0)Seed(index-1,outer,ref tail);if(x<width-1)Seed(index+1,outer,ref tail);
                if(index>=width)Seed(index-width,outer,ref tail);if(index<size-width)Seed(index+width,outer,ref tail);
            }
            background=backdrop.LockBits(new Rectangle(0,0,width,height),ImageLockMode.ReadOnly,PixelFormat.Format32bppArgb);
            result=output.LockBits(new Rectangle(0,0,width,height),ImageLockMode.WriteOnly,PixelFormat.Format32bppArgb);
            var alpha=new int[256];for(int v=0;v<256;v++) {
                float value=Math.Max(0f,Math.Min(1f,(float)(v-inner)/(outer-inner)));
                alpha[v]=(int)(255f*value*value*(3f-2f*value)+.5f);
            }
            for(int y=0;y<height;y++) {
                byte* original=(byte*)input.Scan0+y*input.Stride,newBackground=(byte*)background.Scan0+y*background.Stride,
                    destination=(byte*)result.Scan0+y*result.Stride;
                int offset=y*width;
                for(int x=0;x<width;x++) {
                    int foreground=connected[offset+x]!=0?alpha[distance[offset+x]]:255,position=x*4;
                    for(int channel=0;channel<3;channel++)destination[position+channel]=(byte)
                        ((original[position+channel]*foreground+newBackground[position+channel]*(255-foreground)+127)/255);
                    destination[position+3]=255;
                }
            }
            notice="Plain backdrop removed locally · preview and webcam match";
            transferred=true;
            return output;
        }
        catch(Exception) {
            notice="Background effect paused; original video retained. Try another background or restart the receiver.";
            return source;
        }
        finally {
            if(input!=null)source.UnlockBits(input);if(background!=null)backdrop.UnlockBits(background);
            if(result!=null)output.UnlockBits(result);
            if(!transferred)output.Dispose();
        }
    } }
    void Seed(int index,int threshold,ref int tail) {
        if(connected[index]==0 && distance[index]<threshold){connected[index]=1;queue[tail++]=index;}
    }
    public void Dispose() {lock(gate){disposed=true;if(photo!=null)photo.Dispose();if(backdrop!=null)backdrop.Dispose();photo=null;backdrop=null;}}
}

sealed class AvatarBackgroundWindow : Form {
    readonly AvatarBackground background;
    readonly ComboBox styles=new ComboBox();readonly NumericUpDown strength=new NumericUpDown();
    readonly Label information=new Label();readonly Button picture=new Button();
    readonly System.Windows.Forms.Timer timer=new System.Windows.Forms.Timer();
    bool updating,closing;
    public AvatarBackgroundWindow(AvatarBackground compositor) {
        background=compositor;WebcamUi.Form(this,"Webcam background",660,620);
        MinimumSize=new Size(620,460);StartPosition=FormStartPosition.CenterParent;
        var root=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=1,RowCount=3,Padding=new Padding(24)};
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));root.RowStyles.Add(new RowStyle(SizeType.Percent,100));root.RowStyles.Add(new RowStyle(SizeType.Absolute,52));
        var heading=WebcamUi.Stack();WebcamUi.Add(heading,WebcamUi.Text("Choose your background",20,true));
        WebcamUi.Add(heading,WebcamUi.Text("Applies to your PC preview and virtual webcam.",10));root.Controls.Add(heading,0,0);
        var scroll=new Panel{Dock=DockStyle.Fill,AutoScroll=true,Margin=Padding.Empty};var body=WebcamUi.Stack();body.Padding=new Padding(0,8,8,0);
        WebcamUi.Add(body,WebcamUi.Text("Background style",11,true));styles.DropDownStyle=ComboBoxStyle.DropDownList;styles.Dock=DockStyle.Fill;styles.ForeColor=WebcamUi.Canvas;
        styles.BackColor=WebcamUi.Ink;styles.Font=new Font("Segoe UI",11);styles.Margin=new Padding(0,0,0,16);
        styles.Items.AddRange(new object[]{"Original","Dark studio","Light studio","Custom colour","My picture","Green screen"});
        updating=true;styles.SelectedIndex=(int)background.Style;updating=false;
        styles.SelectedIndexChanged+=delegate{if(updating)return;if(styles.SelectedIndex==(int)AvatarBackgroundStyle.Picture && !background.HasPicture){ChoosePicture();return;}Apply((AvatarBackgroundStyle)styles.SelectedIndex,background.Colour);};
        WebcamUi.Add(body,styles);
        var choices=new TableLayoutPanel{Dock=DockStyle.Top,ColumnCount=2,RowCount=1,Height=48,Margin=new Padding(0,0,0,16)};
        choices.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,50));choices.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,50));
        var colour=new Button();WebcamUi.Button(colour,"Choose colour…");WebcamUi.Button(picture,"Choose PNG/JPG…");picture.Margin=new Padding(10,0,0,0);
        colour.Click+=delegate{using(var picker=new ColorDialog{Color=background.Colour,FullOpen=true})if(picker.ShowDialog(this)==DialogResult.OK)Apply(AvatarBackgroundStyle.Colour,picker.Color);};
        picture.Click+=delegate{ChoosePicture();};choices.Controls.Add(colour,0,0);choices.Controls.Add(picture,1,0);WebcamUi.Add(body,choices);
        var fit=WebcamUi.Stack();WebcamUi.Add(fit,WebcamUi.Text("Refine the edges",12,true));
        var edges=new TableLayoutPanel{Dock=DockStyle.Top,ColumnCount=2,RowCount=1,Height=40,Margin=new Padding(0,0,0,8)};
        edges.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));edges.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,82));
        edges.Controls.Add(WebcamUi.Text("Edge strength",10.5f),0,0);strength.Minimum=12;strength.Maximum=60;strength.Value=background.Strength;
        strength.Dock=DockStyle.Top;strength.ForeColor=WebcamUi.Canvas;strength.BackColor=WebcamUi.Ink;strength.ValueChanged+=delegate{Apply(background.Style,background.Colour);};edges.Controls.Add(strength,1,0);WebcamUi.Add(fit,edges);
        WebcamUi.Add(fit,WebcamUi.Text("Lower the value if clothing or hands lose detail. Best with a plain avatar backdrop.",10,false,520));WebcamUi.Add(body,WebcamUi.Card(fit));
        information.AutoSize=true;information.MaximumSize=new Size(550,0);information.ForeColor=WebcamUi.Accent;information.BackColor=Color.Transparent;
        information.Text=background.Notice;information.Margin=new Padding(0,6,0,12);WebcamUi.Add(body,information);
        WebcamUi.Add(body,WebcamUi.Text("Pictures fill the frame. Use Green screen for tools that apply their own cutout.",9.5f,false,550));scroll.Controls.Add(body);root.Controls.Add(scroll,0,1);
        var done=new Button();WebcamUi.Button(done,"Done",true);done.Width=112;done.Dock=DockStyle.Right;done.Margin=new Padding(0,10,0,0);done.Click+=delegate{Close();};root.Controls.Add(done,0,2);CancelButton=done;Controls.Add(root);
        timer.Interval=250;timer.Tick+=delegate{if(!closing)information.Text=background.Notice;};timer.Start();
        FormClosed+=delegate{closing=true;timer.Stop();timer.Dispose();};
    }
    void Apply(AvatarBackgroundStyle selected,Color colour) {
        try {background.Configure(selected,colour,(int)strength.Value);}
        catch(Exception error){information.Text=error.Message;MessageBox.Show(this,error.Message,"Background");}
        RefreshChoice();
    }
    void RefreshChoice(){updating=true;styles.SelectedIndex=(int)background.Style;updating=false;}
    async void ChoosePicture() {
        using(var picker=new OpenFileDialog{Title="Choose your webcam background",Filter="PNG/JPG pictures|*.png;*.jpg;*.jpeg",CheckFileExists=true,Multiselect=false}) {
            if(picker.ShowDialog(this)!=DialogResult.OK){RefreshChoice();return;}
            picture.Enabled=false;styles.Enabled=false;strength.Enabled=false;information.Text="Opening background picture…";
            string path=picker.FileName;
            try {await Task.Run(delegate{background.ChoosePicture(path);});}
            catch(Exception error){if(!closing)MessageBox.Show(this,error.Message,"Background picture");}
            finally {if(!closing){picture.Enabled=true;styles.Enabled=true;strength.Enabled=true;RefreshChoice();}}
        }
    }
}


