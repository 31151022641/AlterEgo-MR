import com.meta.spatial.core.Pose;
import com.meta.spatial.core.Quaternion;
import com.meta.spatial.core.Vector3;
import com.oculus.camerademo.ml.FaceEmotionState;
import com.oculus.camerademo.mirror.AvatarMirrorFraming;
import com.oculus.camerademo.mirror.WearerAvatarMesh;
import com.oculus.camerademo.model3d.AuthoredPortraitStore;
import com.oculus.camerademo.model3d.PortraitGeometry;
import com.oculus.camerademo.model3d.PortraitMesh;
import com.oculus.camerademo.model3d.PortraitStyle;
import com.oculus.camerademo.model3d.PortraitSurface;
import com.oculus.camerademo.renderer.PortraitDepthBuffer;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import javax.imageio.ImageIO;

/** Offline QA of the actual production upper-body mesh and depth renderer.
 * All input poses below are synthetic; this is not a headset/camera capture. */
public final class WearerAvatarPreview {
    private static final int WIDTH = 1280, HEIGHT = 720;
    private static final int BACKGROUND = 0xff0f1723;
    private record Case(String name, String style, String pose, float headYaw) { }
    private record Skeleton(Pose head, List<Pose> body, List<List<Pose>> hands) { }
    private record Timing(long preparationNs, long rasterNs, int pixel) { }

    private static Pose at(float x, float y, float z) { return new Pose(new Vector3(x,y,z), new Quaternion()); }
    private static List<Pose> empty(int count) { return new ArrayList<>(Collections.nCopies(count, null)); }

    private static Skeleton skeleton(String kind, float yaw) {
        List<Pose> body = empty(18);
        body.set(1, at(0,-.65f,-.035f)); body.set(5,at(0,-.33f,-.035f));
        body.set(6, at(0,-.13f,-.015f)); body.set(7,at(0,0,0));
        List<List<Pose>> hands = new ArrayList<>();
        for (int side=0;side<2;side++) {
            float sign=side==0?1:-1;
            int shoulder=side==0?8:13, upper=side==0?10:15, elbow=side==0?11:16, wrist=side==0?12:17;
            body.set(shoulder,at(sign*.21f,-.205f,-.035f));
            body.set(shoulder+1,at(sign*.15f,-.21f,-.08f));
            body.set(upper,at(sign*.23f,-.24f,-.02f));
            float wx=sign*.29f, wy=-.63f, wz=.04f;
            boolean raised=kind.equals("raised");
            if (raised) { wx=sign*.36f; wy=-.10f; wz=.15f; body.set(elbow,at(sign*.43f,-.30f,.015f)); }
            else if(kind.equals("crossed")) {
                wx=side==0?-.035f:.035f; wy=side==0?-.025f:-.36f; wz=side==0?.27f:.22f;
                body.set(elbow,side==0?at(.20f,-.25f,.08f):at(-.30f,-.43f,.10f));
            } else body.set(elbow,at(sign*.30f,-.44f,.00f));
            body.set(wrist,at(wx,wy,wz));
            List<Pose> hand=empty(26); hand.set(1,at(wx,wy,wz));
            float direction=raised?1:-1;
            hand.set(0,at(wx,wy+direction*.045f,wz+.008f));
            for (int joint=2;joint<=5;joint++) {
                float t=(joint-1)/4f;
                hand.set(joint,at(wx-sign*(.012f+t*.045f),wy+direction*(.025f+t*.045f),wz+.015f));
            }
            for (int finger=0;finger<4;finger++) for (int segment=0;segment<5;segment++) {
                float spread=(finger-1.5f)*.019f;
                float length=(finger==0?.078f:finger==1?.091f:finger==2?.081f:.062f);
                hand.set(6+finger*5+segment,at(wx+sign*spread,
                    wy+direction*(.025f+segment/4f*length),wz+.012f));
            }
            hands.add(hand);
        }
        if(kind.equals("neutral")) { body=empty(18); hands=List.of(empty(26),empty(26)); }
        return new Skeleton(new Pose(new Vector3(0,0,0),new Quaternion(0,yaw,0)),body,hands);
    }

    private static PortraitStyle style(String name) {
        return switch(name) {
            case "snow" -> new PortraitStyle(0xffdaa884,0xff34241d,0xffa56661,true,false,"human","bearded");
            case "unicorn" -> new PortraitStyle(0xffffffff,0xff7a4c8e,0xffb97091,false,false,"unicorn","classic");
            case "lion" -> new PortraitStyle(0xffffb300,0xff683d21,0xff82462f,false,false,"lion","classic");
            case "dragon" -> new PortraitStyle(0xff4caf50,0xff234e32,0xff336a3e,false,false,"dragon","classic");
            default -> new PortraitStyle(0xffe1af91,0xff1c1717,0xffb45d72,false,false,"human","classic");
        };
    }

    private static FaceEmotionState expression() throws Exception {
        FaceEmotionState result=new FaceEmotionState();
        for (String name:List.of("mouthSmileLeft","mouthSmileRight")) {
            Field field=FaceEmotionState.class.getDeclaredField(name); field.setAccessible(true); field.setFloat(result,.35f);
        }
        return result;
    }

    private static String quote(String text) { return "\""+text.replace("\\","\\\\").replace("\"","\\\"")+"\""; }
    private static int authoredVertices(String name) throws Exception {
        try(DataInputStream input=new DataInputStream(new GZIPInputStream(Files.newInputStream(Path.of("app/src/main/assets/portraits/"+name+".aeph"))))) {
            if(!new String(input.readNBytes(8),StandardCharsets.US_ASCII).equals("AEPH0001"))throw new IllegalStateException("Invalid authored model: "+name);
            return input.readInt();
        }
    }
    private static float[] bounds(float[] positions) {
        float[] values={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(int i=0;i<positions.length;i+=3)for(int axis=0;axis<3;axis++) {
            values[axis]=Math.min(values[axis],positions[i+axis]); values[axis+3]=Math.max(values[axis+3],positions[i+axis]);
        }
        return values;
    }

    /** A desktop JVM workload check, not Android Bitmap/encoding or Quest FPS. */
    private static Timing timedFrame(WearerAvatarMesh mesh, PortraitDepthBuffer raster,
                                     int width, int height, int detail) throws Exception {
        Skeleton pose=skeleton("raised",0);
        long began=System.nanoTime();
        PortraitGeometry geometry=mesh.build(pose.head,pose.body,pose.hands,style("rain"),expression(),0,0,true,detail);
        float[] positions=geometry.getPositions(),normals=geometry.getNormals();
        int[] colors=geometry.getColors(),surfaces=geometry.getSurfaces();
        float[] projected=new float[colors.length*2],depths=new float[colors.length];
        int[] shaded=new int[colors.length];
        for(int v=0;v<colors.length;v++) {
            int at=v*3;
            projected[v*2]=AvatarMirrorFraming.projectX(positions[at],width,height);
            projected[v*2+1]=AvatarMirrorFraming.projectY(positions[at+1],width,height);
            depths[v]=positions[at+2];
            shaded[v]=PortraitSurface.shade(colors[v],normals[at],normals[at+1],normals[at+2],surfaces[v],false);
        }
        long prepared=System.nanoTime();
        raster.begin(width,height);Arrays.fill(raster.getPixels(),0,width*height,BACKGROUND);
        raster.draw(projected,depths,shaded,geometry.getIndices(),1,1);
        long rendered=System.nanoTime();
        return new Timing(prepared-began,rendered-prepared,raster.getPixels()[(height/2)*width+width/2]);
    }

    private static void benchmark(WearerAvatarMesh mesh, int detail, Path directory) throws Exception {
        StringBuilder json=new StringBuilder("{\n  \"scope\":\"PC JVM production mesh + shade + projection + depth raster; excludes Android Bitmap, encoding and headset runtime\",\n  \"warmupsPerSize\":2,\n  \"samplesPerSize\":3,\n  \"resolutions\":[\n");
        int[][] sizes={{1920,1080},{960,540}};
        for(int index=0;index<sizes.length;index++) {
            int width=sizes[index][0],height=sizes[index][1];PortraitDepthBuffer raster=new PortraitDepthBuffer();
            for(int warmup=0;warmup<2;warmup++)timedFrame(mesh,raster,width,height,detail);
            double preparation=0,rasterTime=0,max=0;int checksum=0;
            for(int sample=0;sample<3;sample++) {
                Timing timing=timedFrame(mesh,raster,width,height,detail);
                preparation+=timing.preparationNs/1e6;rasterTime+=timing.rasterNs/1e6;
                max=Math.max(max,(timing.preparationNs+timing.rasterNs)/1e6);checksum^=timing.pixel;
            }
            preparation/=3;rasterTime/=3;
            if(index>0)json.append(",\n");
            json.append(String.format(Locale.ROOT,"    {\"width\":%d,\"height\":%d,\"meanPreparationMs\":%.3f,\"meanRasterMs\":%.3f,\"meanCombinedMs\":%.3f,\"maxCombinedMs\":%.3f,\"pixelChecksum\":%d}",
                width,height,preparation,rasterTime,preparation+rasterTime,max,checksum));
            System.out.printf(Locale.ROOT,"PC JVM %dx%d, 3 warmed runs: prepare %.2fms, depth raster %.2fms, combined %.2fms (max %.2fms); NOT Quest FPS%n",
                width,height,preparation,rasterTime,preparation+rasterTime,max);
        }
        json.append("\n  ]\n}\n");Files.writeString(directory.resolve("benchmark.json"),json.toString(),StandardCharsets.UTF_8);
    }

    private static void hero(List<BufferedImage> images, Path output) throws Exception {
        final int gap=22,width=960,height=540,header=100,label=50;
        BufferedImage hero=new BufferedImage(width*2+gap*3,header+label+height+gap,BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics=hero.createGraphics();graphics.setColor(new Color(0x0c1420));graphics.fillRect(0,0,hero.getWidth(),hero.getHeight());
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(0xe9eff5));graphics.setFont(new Font("SansSerif",Font.BOLD,28));
        graphics.drawString("Full upper-body avatar",gap,38);
        graphics.setColor(new Color(0xa9bdce));graphics.setFont(new Font("SansSerif",Font.PLAIN,19));
        graphics.drawString("Actual production geometry / lighting / depth · synthetic neutral poses · not a headset capture",gap,73);
        for(int i=0;i<2;i++) {
            int x=gap+i*(width+gap);
            graphics.setColor(new Color(0xe9eff5));graphics.setFont(new Font("SansSerif",Font.BOLD,24));graphics.drawString(i==0?"Rain Portrait":"Snow Portrait",x,header+31);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(images.get(i),x,header+label,width,height,null);
        }
        graphics.dispose();ImageIO.write(hero,"png",output.toFile());
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless","true");
        Path output=Path.of(args.length>0?args[0]:"assets/previews/upper-body-avatar-v1.png");
        Path casesDirectory=Path.of(args.length>1?args[1]:"scratch/upper-body-preview");
        int detail=args.length>2?Integer.parseInt(args[2]):14;
        Files.createDirectories(output.toAbsolutePath().getParent()); Files.createDirectories(casesDirectory);
        AuthoredPortraitStore.installDirectory("app/src/main/assets");
        WearerAvatarMesh mesh=new WearerAvatarMesh();
        List<Case> cases=List.of(new Case("Rain · neutral upper body","rain","neutral",0),
            new Case("Snow · neutral upper body","snow","neutral",0),
            new Case("Unicorn · neutral upper body","unicorn","neutral",0),
            new Case("Lion · neutral upper body","lion","neutral",0),
            new Case("Dragon · neutral upper body","dragon","neutral",0),
            new Case("Rain · raised hands","rain","raised",0),
            new Case("Rain · hand / face depth","rain","crossed",0),
            new Case("Snow · 35° head turn","snow","rest",35));
        int tileWidth=768,tileHeight=432,gap=20,titleHeight=66,headerHeight=100;
        BufferedImage sheet=new BufferedImage(tileWidth*2+gap*3,headerHeight+(tileHeight+titleHeight+gap)*4+gap,BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics=sheet.createGraphics(); graphics.setColor(new Color(0x0c1420));graphics.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(0xe9eff5));graphics.setFont(new Font("SansSerif",Font.BOLD,26));
        graphics.drawString("Upper-body avatar preview",gap,36);
        graphics.setColor(new Color(0xa9bdce));graphics.setFont(new Font("SansSerif",Font.PLAIN,17));
        graphics.drawString("Actual production mesh, shading and depth renderer · synthetic poses · not a headset capture",gap,66);
        StringBuilder report=new StringBuilder("{\n  \"source\":\"WearerAvatarMesh.build + PortraitDepthBuffer\",\n  \"syntheticPoses\":true,\n  \"cases\":[\n");
        List<BufferedImage> humanImages=new ArrayList<>();
        for(int c=0;c<cases.size();c++) {
            Case item=cases.get(c); Skeleton pose=skeleton(item.pose,item.headYaw);
            int headVertices=new PortraitMesh().build(style(item.style),expression(),0,0,true,detail).getColors().length;
            if(item.style.equals("rain")&&headVertices!=authoredVertices("rain")||item.style.equals("snow")&&headVertices!=authoredVertices("snow"))
                throw new IllegalStateException("Preview did not load the packaged authored head: "+item.style);
            PortraitGeometry geometry=mesh.build(pose.head,pose.body,pose.hands,style(item.style),expression(),0f,0f,true,detail);
            float[] positions=geometry.getPositions(),normals=geometry.getNormals(); int[] indices=geometry.getIndices(),colors=geometry.getColors(),surfaces=geometry.getSurfaces();
            if(positions.length!=colors.length*3||normals.length!=positions.length||indices.length%3!=0)throw new IllegalArgumentException("Invalid buffers: "+item.name);
            for(float value:positions)if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite vertex: "+item.name);
            for(int index:indices)if(index<0||index>=colors.length)throw new IllegalArgumentException("Invalid index: "+item.name);
            int bodyVertices=colors.length-headVertices;
            if(bodyVertices<500)throw new IllegalStateException("Upper body is absent from production mesh: "+item.name);
            float[] projected=new float[colors.length*2],depths=new float[colors.length]; int[] shaded=new int[colors.length];
            int clipped=0;
            for(int v=0;v<colors.length;v++) {
                int p=v*3;
                projected[v*2]=AvatarMirrorFraming.projectX(positions[p],WIDTH,HEIGHT);
                projected[v*2+1]=AvatarMirrorFraming.projectY(positions[p+1],WIDTH,HEIGHT);
                depths[v]=positions[p+2];
                shaded[v]=PortraitSurface.shade(colors[v],normals[p],normals[p+1],normals[p+2],surfaces[v],false);
                if(projected[v*2]<0||projected[v*2]>=WIDTH||projected[v*2+1]<0||projected[v*2+1]>=HEIGHT)clipped++;
            }
            PortraitDepthBuffer raster=new PortraitDepthBuffer();raster.begin(WIDTH,HEIGHT); Arrays.fill(raster.getPixels(),BACKGROUND);
            raster.draw(projected,depths,shaded,indices,1,1);
            BufferedImage image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,WIDTH,HEIGHT,raster.getPixels(),0,WIDTH);
            Path caseFile=casesDirectory.resolve(String.format("%02d-%s-%s.png",c,item.style,item.pose));ImageIO.write(image,"png",caseFile.toFile());
            if(c<2)humanImages.add(image);
            int x=gap+(c%2)*(tileWidth+gap),y=headerHeight+(c/2)*(tileHeight+titleHeight+gap);
            graphics.setColor(new Color(0xe9eff5));graphics.setFont(new Font("SansSerif",Font.BOLD,21));graphics.drawString(item.name,x,y+25);
            graphics.setColor(new Color(0xa9bdce));graphics.setFont(new Font("SansSerif",Font.PLAIN,15));
            graphics.drawString(item.pose.equals("neutral")?"Neutral body presentation; no body tracking asserted":"Synthetic tracked-joint fixture",x,y+49);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image,x,y+titleHeight,tileWidth,tileHeight,null);
            float[] extent=bounds(positions); if(c>0)report.append(",\n");
            report.append("    {\"title\":").append(quote(item.name)).append(",\"vertices\":").append(colors.length)
                .append(",\"headVertices\":").append(headVertices).append(",\"bodyVertices\":").append(bodyVertices)
                .append(",\"triangles\":").append(indices.length/3).append(",\"bounds\":").append(Arrays.toString(extent))
                .append(",\"clippedVertices\":").append(clipped)
                .append(",\"image\":").append(quote(caseFile.toString())).append('}');
            System.out.printf("%s: %,d vertices / %,d triangles; %,d clipped vertices; bounds %s%n",item.name,colors.length,indices.length/3,clipped,Arrays.toString(extent));
        }
        graphics.dispose();ImageIO.write(sheet,"png",output.toFile());report.append("\n  ]\n}\n");
        Files.writeString(casesDirectory.resolve("report.json"),report.toString(),StandardCharsets.UTF_8);
        hero(humanImages,output.toAbsolutePath().resolveSibling("upper-body-avatar-hero.png"));
        benchmark(mesh,detail,casesDirectory);
        System.out.println("Contact sheet: "+output.toAbsolutePath());
    }
}
