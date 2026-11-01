using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Collections.Generic;
using System.Windows.Forms;

static class WebcamUi {
    static readonly Icon ApplicationIcon=Icon.ExtractAssociatedIcon(Application.ExecutablePath);
    internal static readonly Color Canvas=Color.FromArgb(12,18,28),Surface=Color.FromArgb(23,34,49),
        Stage=Color.FromArgb(9,16,26),Ink=Color.FromArgb(235,242,249),Muted=Color.FromArgb(158,179,199),
        Accent=Color.FromArgb(121,222,225),Line=Color.FromArgb(44,63,82),Soft=Color.FromArgb(34,51,69);
    internal static Label Text(string text,float size=10.5f,bool bold=false,int maximumWidth=0) {
        return new Label {Text=text,AutoSize=true,ForeColor=bold?Ink:Muted,BackColor=Color.Transparent,
            Font=new Font("Segoe UI",size,bold?FontStyle.Bold:FontStyle.Regular),
            Margin=new Padding(0,0,0,10),MaximumSize=maximumWidth>0?new Size(maximumWidth,0):Size.Empty};
    }
    internal static void Button(Button button,string text,bool primary=false) {
        button.Text=text;button.AutoSize=false;button.Height=42;button.Dock=DockStyle.Fill;
        button.FlatStyle=FlatStyle.Flat;button.FlatAppearance.BorderSize=primary?0:1;
        button.FlatAppearance.BorderColor=Line;button.BackColor=primary?Accent:Soft;
        button.ForeColor=primary?Canvas:Ink;button.Font=new Font("Segoe UI",10.5f,FontStyle.Bold);
        button.Cursor=Cursors.Hand;button.Margin=new Padding(0,0,0,10);
        button.FlatAppearance.MouseOverBackColor=primary?Color.FromArgb(160,237,239):Color.FromArgb(45,67,88);
        button.FlatAppearance.MouseDownBackColor=primary?Color.FromArgb(92,195,203):Color.FromArgb(25,43,60);
    }
    internal static TableLayoutPanel Stack() {
        var result=new TableLayoutPanel {AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,
            Dock=DockStyle.Top,ColumnCount=1,RowCount=0,BackColor=Color.Transparent,Margin=Padding.Empty,Padding=Padding.Empty};
        result.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));return result;
    }
    internal static void Add(TableLayoutPanel stack,Control control) {
        int row=stack.RowCount++;stack.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        stack.Controls.Add(control,0,row);
    }
    internal static WebcamCard Card(TableLayoutPanel contents) {
        var card=new WebcamCard {AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,Dock=DockStyle.Top,
            Padding=new Padding(18),Margin=new Padding(0,0,0,14)};
        card.Controls.Add(contents);return card;
    }
    internal static string BackgroundName(AvatarBackgroundStyle style) {
        switch(style) {case AvatarBackgroundStyle.DarkStudio:return "Dark studio";
            case AvatarBackgroundStyle.LightStudio:return "Light studio";case AvatarBackgroundStyle.Colour:return "Custom colour";
            case AvatarBackgroundStyle.Picture:return "My picture";case AvatarBackgroundStyle.GreenScreen:return "Green screen";default:return "Original";}
    }
    internal static void Form(Form form,string title,int width,int height) {
        form.Text=title;form.Icon=ApplicationIcon;form.Size=new Size(width,height);form.BackColor=Canvas;form.ForeColor=Ink;
        form.Font=new Font("Segoe UI",10.5f);form.AutoScaleMode=AutoScaleMode.Dpi;
        form.AutoScaleDimensions=new SizeF(96,96);form.StartPosition=FormStartPosition.CenterScreen;
    }
    internal static GraphicsPath Round(RectangleF rectangle,float radius) {
        var path=new GraphicsPath();float diameter=radius*2;
        path.AddArc(rectangle.X,rectangle.Y,diameter,diameter,180,90);
        path.AddArc(rectangle.Right-diameter,rectangle.Y,diameter,diameter,270,90);
        path.AddArc(rectangle.Right-diameter,rectangle.Bottom-diameter,diameter,diameter,0,90);
        path.AddArc(rectangle.X,rectangle.Bottom-diameter,diameter,diameter,90,90);path.CloseFigure();return path;
    }
}

sealed class WebcamCard : Panel {
    internal Color Fill=WebcamUi.Surface;
    internal WebcamCard() {DoubleBuffered=true;BackColor=WebcamUi.Canvas;}
    protected override void OnPaint(PaintEventArgs args) {
        base.OnPaint(args);if(Width<2 || Height<2)return;
        args.Graphics.SmoothingMode=SmoothingMode.AntiAlias;
        using(var path=WebcamUi.Round(new RectangleF(.5f,.5f,Width-1,Height-1),14f))
        using(var brush=new SolidBrush(Fill))using(var pen=new Pen(WebcamUi.Line)) {
            args.Graphics.FillPath(brush,path);args.Graphics.DrawPath(pen,path);
        }
    }
}

sealed class WebcamEmptyView : Panel {
    string heading="",body="";
    internal int PaintPasses;
    internal WebcamEmptyView(){DoubleBuffered=true;ResizeRedraw=true;BackColor=WebcamUi.Stage;}
    internal void SetText(string title,string hint){if(title==heading && hint==body)return;heading=title;body=hint;Invalidate();}
    protected override void OnPaint(PaintEventArgs args) {
        base.OnPaint(args);PaintPasses++;float scale=Font.SizeInPoints/10.5f;
        int width=Math.Max(80,Math.Min((int)(410*scale),ClientSize.Width-(int)(28*scale))),left=(ClientSize.Width-width)/2;
        int top=Math.Max(10,(ClientSize.Height-(int)(210*scale))/2);
        using(var icon=new Font("Segoe UI",32*scale,FontStyle.Bold))using(var title=new Font("Segoe UI",20*scale,FontStyle.Bold))using(var hint=new Font("Segoe UI",11*scale)) {
            // GDI+ respects the translated graphics used by native print/view
            // capture as well as the normal window paint path.
            args.Graphics.TextRenderingHint=System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
            using(var format=new StringFormat{Alignment=StringAlignment.Center,LineAlignment=StringAlignment.Near})
            using(var accent=new SolidBrush(WebcamUi.Accent))using(var ink=new SolidBrush(WebcamUi.Ink))using(var muted=new SolidBrush(WebcamUi.Muted)) {
                args.Graphics.DrawString("AE",icon,accent,new RectangleF(left,top,width,58*scale),format);
                args.Graphics.DrawString(heading,title,ink,new RectangleF(left,top+78*scale,width,66*scale),format);
                args.Graphics.DrawString(body,hint,muted,new RectangleF(left,top+148*scale,width,62*scale),format);
            }
        }
    }
}

sealed partial class WebcamWindow {
    readonly bool layoutPreview;
    readonly Label receiverBadge=WebcamUi.Text("Receiver off",10.5f,true),sourceBadge=WebcamUi.Text("No video",10),
        backgroundName=WebcamUi.Text("Original",12,true),backgroundHint=WebcamUi.Text("",9.5f,false,276);
    readonly Button cameraOptions=new Button();
    TableLayoutPanel manualContents,cameraContents;
    WebcamEmptyView emptyView;
    bool manualExpanded,cameraExpanded;
    string previewState="off";

    public WebcamWindow(bool preview=false) {
        layoutPreview=preview;
        background=new AvatarBackground(preview?Path.Combine(Environment.GetEnvironmentVariable("ALTEREGO_MR_STATE_DIR") ?? Path.GetTempPath(),"ui-layout-"+Guid.NewGuid().ToString("N")):null);
        WebcamUi.Form(this,"AlterEgo MR · Avatar Webcam 1.5.0",1160,800);MinimumSize=new Size(920,640);
        BuildLayout();PopulateNetworks();
        start.Click+=delegate(object sender,EventArgs args){if(!layoutPreview)StartReceiver(sender,args);};
        install.Click+=delegate(object sender,EventArgs args){if(!layoutPreview)InstallCamera(sender,args);};
        checkCamera.Click+=delegate(object sender,EventArgs args){if(!layoutPreview)CheckCamera(sender,args);};
        reset.Click+=delegate(object sender,EventArgs args){if(!layoutPreview)ResetPairing(sender,args);};
        manual.Click+=delegate{ToggleManualPairing();};cameraOptions.Click+=delegate{ToggleCameraOptions();};
        copy.Click+=delegate {if(layoutPreview)return;try {Clipboard.SetText(pairing.Text);ShowStatus("Pairing key copied. Paste the whole key into Quest PC Link.");}catch(Exception error){ShowStatus(error.Message);}};
        changeBackground.Click+=delegate{using(var window=new AvatarBackgroundWindow(background))window.ShowDialog(this);};
        statusMessage="Ready to connect.";
        timer.Interval=66;timer.Tick+=delegate {
            var image=System.Threading.Interlocked.Exchange(ref latest,null);
            if(image!=null){var old=picture.Image;picture.Image=image;if(old!=null)old.Dispose();}
            RefreshLayoutState();
        };timer.Start();FormClosing+=OnClosing;RefreshLayoutState();
    }

    void BuildLayout() {
        var root=new TableLayoutPanel {Dock=DockStyle.Fill,ColumnCount=1,RowCount=3,Padding=new Padding(24,20,24,12),BackColor=WebcamUi.Canvas};
        root.RowStyles.Add(new RowStyle(SizeType.Absolute,82));root.RowStyles.Add(new RowStyle(SizeType.Percent,100));root.RowStyles.Add(new RowStyle(SizeType.Absolute,44));
        var header=new TableLayoutPanel {Dock=DockStyle.Fill,ColumnCount=2,RowCount=1,Margin=Padding.Empty};
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var heading=WebcamUi.Stack();WebcamUi.Add(heading,WebcamUi.Text("AlterEgo MR",23,true));
        var subtitle=WebcamUi.Text("AVATAR WEBCAM",9.5f);subtitle.Margin=Padding.Empty;WebcamUi.Add(heading,subtitle);header.Controls.Add(heading,0,0);
        receiverBadge.Padding=new Padding(14,9,14,9);receiverBadge.BackColor=WebcamUi.Soft;receiverBadge.Margin=new Padding(12,12,0,0);header.Controls.Add(receiverBadge,1,0);root.Controls.Add(header,0,0);

        var content=new TableLayoutPanel {Dock=DockStyle.Fill,ColumnCount=2,RowCount=1,Margin=Padding.Empty};
        content.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));content.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,338));
        var previewCard=new WebcamCard {Dock=DockStyle.Fill,Fill=WebcamUi.Stage,Padding=new Padding(20),Margin=new Padding(0,0,20,0)};
        var previewLayout=new TableLayoutPanel {Dock=DockStyle.Fill,ColumnCount=1,RowCount=3,BackColor=WebcamUi.Stage,Margin=Padding.Empty};
        previewLayout.RowStyles.Add(new RowStyle(SizeType.Absolute,44));previewLayout.RowStyles.Add(new RowStyle(SizeType.Percent,100));previewLayout.RowStyles.Add(new RowStyle(SizeType.Absolute,52));
        var previewHeader=new TableLayoutPanel {Dock=DockStyle.Fill,ColumnCount=2,RowCount=1,Margin=Padding.Empty};
        previewHeader.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));previewHeader.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        previewHeader.Controls.Add(WebcamUi.Text("Live preview",13,true),0,0);sourceBadge.Margin=new Padding(8,2,0,0);previewHeader.Controls.Add(sourceBadge,1,0);previewLayout.Controls.Add(previewHeader,0,0);
        var stage=new Panel {Dock=DockStyle.Fill,BackColor=WebcamUi.Stage,Margin=Padding.Empty};
        picture.Dock=DockStyle.Fill;picture.SizeMode=PictureBoxSizeMode.Zoom;picture.BackColor=WebcamUi.Stage;stage.Controls.Add(picture);
        emptyView=new WebcamEmptyView {Dock=DockStyle.Fill};
        stage.Controls.Add(emptyView);emptyView.BringToFront();previewLayout.Controls.Add(stage,0,1);
        var caption=WebcamUi.Text("This is the picture your call app receives.\nKeep the Quest mirror and this receiver open.",10);caption.Dock=DockStyle.Fill;caption.Margin=Padding.Empty;caption.Padding=new Padding(0,12,0,0);previewLayout.Controls.Add(caption,0,2);
        previewCard.Controls.Add(previewLayout);content.Controls.Add(previewCard,0,0);

        var scroll=new Panel {Dock=DockStyle.Fill,AutoScroll=true,Margin=Padding.Empty,BackColor=WebcamUi.Canvas};
        var sidebar=WebcamUi.Stack();sidebar.Padding=new Padding(0,0,4,0);
        var connection=WebcamUi.Stack();WebcamUi.Add(connection,WebcamUi.Text("Connect your Quest",13,true));
        WebcamUi.Add(connection,WebcamUi.Text("Network shared with your headset",9.5f,false,276));
        adapters.DropDownStyle=ComboBoxStyle.DropDownList;adapters.Dock=DockStyle.Fill;adapters.ForeColor=WebcamUi.Canvas;
        adapters.BackColor=WebcamUi.Ink;adapters.Font=new Font("Segoe UI",10);adapters.Margin=new Padding(0,0,0,14);adapters.DropDownWidth=440;WebcamUi.Add(connection,adapters);
        WebcamUi.Button(start,"Start receiver",true);WebcamUi.Add(connection,start);
        address.AutoSize=true;address.MaximumSize=new Size(276,0);address.ForeColor=WebcamUi.Muted;address.BackColor=Color.Transparent;address.Font=new Font("Segoe UI",9.5f);address.Margin=new Padding(0,0,0,10);
        address.Text="Your PC address appears after starting.";WebcamUi.Add(connection,address);
        status.AutoSize=true;status.MaximumSize=new Size(276,0);status.BackColor=Color.Transparent;status.ForeColor=WebcamUi.Ink;status.Font=new Font("Segoe UI",10);status.Margin=new Padding(0,0,0,10);WebcamUi.Add(connection,status);
        WebcamUi.Button(manual,"Show manual pairing");manual.Height=32;manual.Margin=Padding.Empty;WebcamUi.Add(connection,manual);
        manualContents=WebcamUi.Stack();manualContents.Visible=false;
        WebcamUi.Add(manualContents,WebcamUi.Text("Use this only when Find PC is unavailable.",9.5f,false,276));
        pairing.ReadOnly=true;pairing.Multiline=true;pairing.WordWrap=true;pairing.Height=64;pairing.Dock=DockStyle.Fill;
        pairing.Font=new Font("Consolas",9.5f);pairing.BackColor=WebcamUi.Stage;pairing.ForeColor=WebcamUi.Ink;
        pairing.BorderStyle=BorderStyle.FixedSingle;pairing.Margin=new Padding(0,0,0,10);pairing.Text="Start the receiver to display the secure key.";WebcamUi.Add(manualContents,pairing);
        WebcamUi.Button(copy,"Copy full pairing key");copy.Enabled=false;WebcamUi.Add(manualContents,copy);WebcamUi.Add(connection,manualContents);
        WebcamUi.Add(sidebar,WebcamUi.Card(connection));

        var appearance=WebcamUi.Stack();WebcamUi.Add(appearance,WebcamUi.Text("Background",13,true));
        WebcamUi.Add(appearance,backgroundName);WebcamUi.Button(changeBackground,"Change background…");WebcamUi.Add(appearance,changeBackground);
        WebcamUi.Add(appearance,backgroundHint);WebcamUi.Add(sidebar,WebcamUi.Card(appearance));

        var camera=WebcamUi.Stack();WebcamUi.Add(camera,WebcamUi.Text("Camera tools",13,true));
        WebcamUi.Button(checkCamera,"Check Windows camera");WebcamUi.Add(camera,checkCamera);
        WebcamUi.Button(cameraOptions,"More camera options");cameraOptions.Height=32;cameraOptions.Margin=Padding.Empty;WebcamUi.Add(camera,cameraOptions);
        cameraContents=WebcamUi.Stack();cameraContents.Visible=false;
        WebcamUi.Button(install,"Install or repair webcam");WebcamUi.Add(cameraContents,install);
        WebcamUi.Button(reset,"Reset pairing");WebcamUi.Add(cameraContents,reset);
        WebcamUi.Add(cameraContents,WebcamUi.Text("Reset pairing only when you want to pair your Quest again.",9.5f,false,276));
        WebcamUi.Add(camera,cameraContents);WebcamUi.Add(sidebar,WebcamUi.Card(camera));
        scroll.Controls.Add(sidebar);content.Controls.Add(scroll,1,0);root.Controls.Add(content,0,1);

        var footer=new TableLayoutPanel {Dock=DockStyle.Fill,ColumnCount=2,RowCount=1,Margin=Padding.Empty,Padding=new Padding(0,12,0,0)};
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var output=WebcamUi.Text("In your call app, select “AlterEgo MR Avatar”. Use your usual microphone.",9);output.AutoEllipsis=true;output.Dock=DockStyle.Fill;footer.Controls.Add(output,0,0);
        footer.Controls.Add(WebcamUi.Text(layoutPreview?"SYNTHETIC LAYOUT CHECK":"v1.5.0",9),1,0);root.Controls.Add(footer,0,2);Controls.Add(root);
    }
    void PopulateNetworks() {
        if(layoutPreview) {
            adapters.Items.Add(new AdapterChoice("Home Wi-Fi",IPAddress.Parse("192.0.2.10")));
            adapters.Items.Add(new AdapterChoice("Ethernet",IPAddress.Parse("192.0.2.20")));adapters.SelectedIndex=0;return;
        }
        var choices=new List<AdapterChoice>();
        foreach(var adapter in NetworkInterface.GetAllNetworkInterfaces())if(adapter.OperationalStatus==OperationalStatus.Up)
            foreach(var item in adapter.GetIPProperties().UnicastAddresses) {
                byte[] bytes=item.Address.GetAddressBytes();
                if(bytes.Length==4 && (bytes[0]==10 || bytes[0]==172 && bytes[1]>=16 && bytes[1]<=31 || bytes[0]==192 && bytes[1]==168)) {
                    var choice=new AdapterChoice(adapter.Name,item.Address);
                    if(adapter.NetworkInterfaceType==NetworkInterfaceType.Wireless80211 || adapter.NetworkInterfaceType==NetworkInterfaceType.Ethernet)choices.Insert(0,choice);else choices.Add(choice);
                }
            }
        foreach(var choice in choices)adapters.Items.Add(choice);
        if(adapters.Items.Count==0)adapters.Items.Add(new AdapterChoice("No LAN found",IPAddress.Loopback));adapters.SelectedIndex=0;
    }
    void ToggleManualPairing() {manualExpanded=!manualExpanded;manualContents.Visible=manualExpanded;manual.Text=manualExpanded?"Hide manual pairing":"Show manual pairing";}
    void ToggleCameraOptions() {cameraExpanded=!cameraExpanded;cameraContents.Visible=cameraExpanded;cameraOptions.Text=cameraExpanded?"Hide camera options":"More camera options";}
    void RefreshLayoutState() {
        bool hasImage=picture.Image!=null,listening=receiver!=null || layoutPreview && previewState!="off";
        bool starting=!start.Enabled && receiver==null && !installing;
        receiverBadge.Text=installing?"Updating webcam":starting?"Starting…":hasImage?"Video live":listening?"Waiting for Quest":"Receiver off";
        receiverBadge.BackColor=hasImage?Color.FromArgb(24,69,61):WebcamUi.Soft;
        receiverBadge.ForeColor=hasImage?Color.FromArgb(144,233,197):WebcamUi.Ink;
        sourceBadge.Text=hasImage?picture.Image.Width+" × "+picture.Image.Height:"No video";
        picture.Visible=hasImage;emptyView.Visible=!hasImage;emptyView.SetText(listening?"Waiting for your avatar":"Your avatar appears here",
            listening?"Open My mirror on Quest, then connect to this PC in PC Link.":"Start the receiver on the right, then connect your Quest.");
        address.Visible=listening;status.Text=hasImage && statusMessage.StartsWith("Avatar webcam is live")?"Connected. Your webcam is ready for calls.":statusMessage;
        backgroundName.Text=WebcamUi.BackgroundName(background.Style);backgroundHint.Text=background.Notice;backgroundHint.Visible=background.Style!=AvatarBackgroundStyle.Original;
        start.BackColor=listening?WebcamUi.Soft:WebcamUi.Accent;start.ForeColor=listening?WebcamUi.Ink:WebcamUi.Canvas;
    }
    internal void SyntheticLayoutState(string state) {
        if(!layoutPreview)throw new InvalidOperationException("Synthetic UI state requires an isolated preview window.");
        previewState=state;
        if(state=="waiting" || state=="live" || state=="manual") {
            start.Text="Stop receiver";address.Text="PC address: 192.0.2.10:38991";
            statusMessage="Layout check only · No camera or headset is connected.";
            adapters.Enabled=false;reset.Enabled=false;
        }
        if(state=="live")picture.Image=AvatarBackgroundTests.Fixture(1280,720);
        if(state=="manual") {pairing.Text="SYNTHETIC TEST KEY — NOT A REAL PAIRING KEY";copy.Enabled=true;ToggleManualPairing();ToggleCameraOptions();}
        RefreshLayoutState();
    }
    internal void CheckSyntheticLayout() {
        if(!layoutPreview)throw new InvalidOperationException("Layout checks require synthetic state.");
        foreach(Control item in new Control[]{start,manual,changeBackground,checkCamera,cameraOptions})
            if(item.Width<150 || item.Height<30)throw new InvalidOperationException("A primary control is clipped: "+item.Text);
        if(picture.Image!=null && (picture.ClientSize.Width<300 || picture.ClientSize.Height<180))throw new InvalidOperationException("Preview is too small.");
        if(emptyView.ClientSize.Width<300 || emptyView.ClientSize.Height<180)throw new InvalidOperationException("Waiting view is too small.");
        if(previewState=="off") {
            if(manualContents.Visible || cameraContents.Visible || pairing.Visible)throw new InvalidOperationException("Advanced fields should start collapsed.");
            ToggleManualPairing();PerformLayout();Application.DoEvents();
            if(!pairing.Visible || !copy.Visible)throw new InvalidOperationException("Manual pairing did not expand.");
            ToggleManualPairing();PerformLayout();Application.DoEvents();
            if(pairing.Visible)throw new InvalidOperationException("Manual pairing did not close.");
        }
        CheckScrollbars(this);
    }
    internal void CheckEmptyPaint(Bitmap image){
        if(picture.Image!=null)return;
        if(emptyView.PaintPasses==0)throw new InvalidOperationException("Waiting instructions did not paint.");
        Point position=emptyView.PointToScreen(Point.Empty);position.Offset(-Left,-Top);int visible=0;
        for(int y=position.Y+emptyView.Height/4;y<position.Y+emptyView.Height*3/4;y+=2)
            for(int x=position.X+emptyView.Width/4;x<position.X+emptyView.Width*3/4;x+=2)
                if(x>=0 && x<image.Width && y>=0 && y<image.Height){Color pixel=image.GetPixel(x,y);if(pixel.R>70 && pixel.G>140 && pixel.B>140)visible++;}
        if(visible<20)throw new InvalidOperationException("Waiting instructions are missing from the rendered view. "+position+" "+emptyView.ClientSize+" "+emptyView.Visible);
    }
    static void CheckScrollbars(Control parent) {
        var scrolling=parent as ScrollableControl;
        if(scrolling!=null && scrolling.AutoScroll && scrolling.HorizontalScroll.Visible)
            throw new InvalidOperationException("Horizontal scrolling is required by "+parent.GetType().Name);
        foreach(Control child in parent.Controls)CheckScrollbars(child);
    }
}

sealed class WebcamPairingWindow : Form {
    internal WebcamPairingWindow(string code,Action approve,Action cancel) {
        WebcamUi.Form(this,"Confirm Quest pairing",560,340);StartPosition=FormStartPosition.CenterParent;
        FormBorderStyle=FormBorderStyle.FixedDialog;MaximizeBox=false;MinimizeBox=false;
        var root=WebcamUi.Stack();root.Dock=DockStyle.Fill;root.Padding=new Padding(26);
        WebcamUi.Add(root,WebcamUi.Text("Confirm your Quest",19,true));
        WebcamUi.Add(root,WebcamUi.Text("Compare this exact code on both devices.",11));
        var display=WebcamUi.Text(code,25,true);display.ForeColor=WebcamUi.Accent;display.Font=new Font("Consolas",25,FontStyle.Bold);display.Margin=new Padding(0,14,0,20);WebcamUi.Add(root,display);
        WebcamUi.Add(root,WebcamUi.Text("Continue only if the two codes match.",10));
        var buttons=new TableLayoutPanel{Dock=DockStyle.Top,ColumnCount=2,RowCount=1,Height=48,Margin=Padding.Empty};
        buttons.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,40));buttons.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,60));
        var no=new Button();var yes=new Button();WebcamUi.Button(no,"Cancel");WebcamUi.Button(yes,"Codes match",true);yes.Margin=new Padding(10,0,0,0);
        no.Click+=delegate{cancel();Close();};yes.Click+=delegate{approve();Close();};buttons.Controls.Add(no,0,0);buttons.Controls.Add(yes,1,0);WebcamUi.Add(root,buttons);
        FormClosed+=delegate{cancel();};CancelButton=no;Controls.Add(root);
    }
}

static class WebcamLayoutCheck {
    public static void Run() {
        string folder=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"layout-check");Directory.CreateDirectory(folder);
        var sizes=new[]{new Size(1160,800),new Size(1160,800),new Size(1160,800),new Size(1160,800),new Size(920,640),new Size(920,640)};
        string[] names={"ready","waiting","live","manual","compact","scaled-150"};string[] states={"off","waiting","live","manual","off","off"};
        for(int index=0;index<names.Length;index++)using(var window=new WebcamWindow(true)) {
            window.Opacity=0;window.ShowInTaskbar=false;window.Size=sizes[index];window.Show();
            window.SyntheticLayoutState(states[index]);Application.DoEvents();window.PerformLayout();
            if(index==5){window.Scale(new SizeF(1.5f,1.5f));ScaleText(window,1.5f);window.PerformLayout();Application.DoEvents();}
            window.CheckSyntheticLayout();window.PerformLayout();Application.DoEvents();
            using(var image=new Bitmap(window.Width,window.Height)) {
                window.DrawToBitmap(image,new Rectangle(0,0,image.Width,image.Height));
                image.Save(Path.Combine(folder,names[index]+".png"));
                window.CheckEmptyPaint(image);
            }Console.WriteLine("Synthetic native layout checked: "+names[index]+" "+window.Width+"x"+window.Height);
        }
        using(var window=new WebcamPairingWindow("TEST-ONLY-CODE",delegate{},delegate{})) {
            window.Opacity=0;window.ShowInTaskbar=false;window.Show();Application.DoEvents();window.PerformLayout();
            using(var image=new Bitmap(window.Width,window.Height)){window.DrawToBitmap(image,new Rectangle(0,0,image.Width,image.Height));image.Save(Path.Combine(folder,"pairing.png"));}
        }
        foreach(string action in new[]{"Codes match","Cancel","Close"}) {
            var answer=new System.Threading.Tasks.TaskCompletionSource<bool>();
            using(var window=new WebcamPairingWindow("TEST-ONLY-CODE",delegate{answer.TrySetResult(true);},delegate{answer.TrySetResult(false);})) {
                window.Opacity=0;window.ShowInTaskbar=false;window.Show();Application.DoEvents();
                if(action=="Close")window.Close();else FindButton(window,action).PerformClick();
                if(!answer.Task.IsCompleted || answer.Task.Result!=(action=="Codes match"))throw new InvalidOperationException("Pairing button changed its approval behaviour: "+action);
            }
        }
        using(var background=new AvatarBackground(Path.Combine(Environment.GetEnvironmentVariable("ALTEREGO_MR_STATE_DIR"),"ui-layout-background-"+Guid.NewGuid().ToString("N"))))
        using(var window=new AvatarBackgroundWindow(background)) {
            window.Opacity=0;window.ShowInTaskbar=false;window.Show();Application.DoEvents();window.PerformLayout();
            using(var image=new Bitmap(window.Width,window.Height)){window.DrawToBitmap(image,new Rectangle(0,0,image.Width,image.Height));image.Save(Path.Combine(folder,"background.png"));}
        }
        Console.WriteLine("Layout check complete: 8 synthetic views, 3 pairing actions; cameras=0 accounts=0 savedPairing=0");
    }
    static void ScaleText(Control root,float scale) {
        var controls=new List<Control>();var fonts=new List<Font>();CaptureFonts(root,controls,fonts);
        for(int index=0;index<controls.Count;index++)controls[index].Font=new Font(fonts[index].FontFamily,fonts[index].SizeInPoints*scale,fonts[index].Style);
    }
    static void CaptureFonts(Control parent,List<Control> controls,List<Font> fonts) {
        controls.Add(parent);fonts.Add(parent.Font);foreach(Control child in parent.Controls)CaptureFonts(child,controls,fonts);
    }
    static Button FindButton(Control parent,string text) {
        foreach(Control child in parent.Controls){var button=child as Button;if(button!=null && button.Text==text)return button;
            var found=FindButton(child,text);if(found!=null)return found;}return null;
    }
}

