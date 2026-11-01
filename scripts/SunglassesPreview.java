import com.oculus.camerademo.ml.FaceEmotionState;
import com.oculus.camerademo.model3d.AuthoredPortraitStore;
import com.oculus.camerademo.model3d.PortraitGeometry;
import com.oculus.camerademo.model3d.PortraitMesh;
import com.oculus.camerademo.model3d.PortraitStyle;
import com.oculus.camerademo.model3d.PortraitSunglasses;
import com.oculus.camerademo.model3d.PortraitSurface;
import com.oculus.camerademo.renderer.PortraitDepthBuffer;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Real production portrait/accessory geometry and depth raster; synthetic poses. */
public final class SunglassesPreview {
    private static final int SIZE=480, BACKGROUND=0xff101b2a;
    private record Case(String title,String style,float yaw) { }

    private static PortraitStyle style(String name) {
        return switch(name) {
            case "sunglasses" -> new PortraitStyle(0xff111b25,0xff111b25,0xff111b25,false,false,"sunglasses","classic");
            case "snow" -> new PortraitStyle(0xffdaa884,0xff34241d,0xffa56661,true,false,"human","bearded");
            case "unicorn" -> new PortraitStyle(0xffffffff,0xff7a4c8e,0xffb97091,false,false,"unicorn","classic");
            case "lion" -> new PortraitStyle(0xffffb300,0xff683d21,0xff82462f,false,false,"lion","classic");
            case "dragon" -> new PortraitStyle(0xff4caf50,0xff234e32,0xff336a3e,false,false,"dragon","classic");
            default -> new PortraitStyle(0xffe1af91,0xff1c1717,0xffb45d72,false,false,"human","classic");
        };
    }
    private static String quote(String text) {return "\""+text.replace("\\","\\\\").replace("\"","\\\"")+"\"";}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless","true");
        Path output=Path.of(args.length>0?args[0]:"assets/previews/standalone-sunglasses.png");
        Path directory=Path.of(args.length>1?args[1]:"scratch/sunglasses-preview");
        Files.createDirectories(output.toAbsolutePath().getParent());Files.createDirectories(directory);
        AuthoredPortraitStore.installDirectory("app/src/main/assets");
        List<String> styles=List.of("sunglasses","rain","snow","unicorn","lion","dragon");
        List<String> names=List.of("Sunglasses","Rain Portrait","Snow Portrait","Unicorn","Lion","Dragon");
        int tile=360,gap=16,header=104,label=46;
        BufferedImage sheet=new BufferedImage(tile*3+gap*4,header+(tile+label+gap)*2+gap,BufferedImage.TYPE_INT_ARGB);
        Graphics2D canvas=sheet.createGraphics();
        canvas.setColor(new Color(0x0b1320));canvas.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
        canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        canvas.setColor(new Color(0xecf3fa));canvas.setFont(new Font("SansSerif",Font.BOLD,30));
        canvas.drawString("Sunglasses — a separate passthrough face",gap,41);
        canvas.setFont(new Font("SansSerif",Font.PLAIN,19));canvas.setColor(new Color(0xa9bfd3));
        canvas.drawString("Production geometry · other faces unchanged · synthetic preview",gap,77);
        StringBuilder report=new StringBuilder("{\n  \"source\":\"PortraitMesh + PortraitSunglasses + PortraitDepthBuffer\",\n  \"syntheticPoses\":true,\n  \"cases\":[\n");
        PortraitMesh portrait=new PortraitMesh();int totalClipped=0;
        for(int row=0;row<2;row++)for(int col=0;col<3;col++) {
            int number=row*3+col;String name=styles.get(number);float yaw=0;
            Case item=new Case(names.get(number),name,yaw);PortraitStyle appearance=style(name);
            PortraitGeometry source=portrait.build(appearance,new FaceEmotionState(),0,0,true,20);
            PortraitGeometry mesh=source;
            int[] colors=mesh.getColors(),surfaces=mesh.getSurfaces(),indices=mesh.getIndices();
            float[] positions=mesh.getPositions(),normals=mesh.getNormals();
            int verticesAdded=name.equals("sunglasses")?colors.length:0;
            int trianglesAdded=name.equals("sunglasses")?indices.length/3:0;
            if(name.equals("sunglasses")&&(verticesAdded<=0||trianglesAdded<=0||trianglesAdded>3000))throw new IllegalArgumentException("Missing/overbudget sunglasses: "+name);
            if(name.equals("sunglasses")) for(int surface:surfaces) if(surface==PortraitSurface.SKIN||surface==PortraitSurface.HAIR||surface==PortraitSurface.MOUTH)throw new IllegalArgumentException("Sunglasses must not contain a face");
            if(positions.length!=colors.length*3||normals.length!=positions.length||indices.length%3!=0)throw new IllegalArgumentException("Invalid buffers: "+name);
            for(float value:positions)if(!Float.isFinite(value))throw new IllegalArgumentException("Invalid position: "+name);
            for(float value:normals)if(!Float.isFinite(value))throw new IllegalArgumentException("Invalid normal: "+name);
            for(int index:indices)if(index<0||index>=colors.length)throw new IllegalArgumentException("Invalid index: "+name);
            float radians=(float)Math.toRadians(yaw),co=(float)Math.cos(radians),si=(float)Math.sin(radians);
            float unit=name.equals("sunglasses")?1800f:1000f,centerY=name.equals("sunglasses")?285:250;
            float[] projected=new float[colors.length*2],depths=new float[colors.length];int[] shaded=new int[colors.length];int clipped=0;
            for(int v=0;v<colors.length;v++) {
                int at=v*3;float x=co*positions[at]+si*positions[at+2];float z=-si*positions[at]+co*positions[at+2];
                projected[v*2]=SIZE*.5f+x*unit;projected[v*2+1]=centerY-positions[at+1]*unit;depths[v]=z;
                float nx=co*normals[at]+si*normals[at+2],nz=-si*normals[at]+co*normals[at+2];
                shaded[v]=PortraitSurface.shade(colors[v],nx,normals[at+1],nz,surfaces[v],false);
                if(projected[v*2]<0||projected[v*2]>=SIZE||projected[v*2+1]<0||projected[v*2+1]>=SIZE)clipped++;
            }
            totalClipped+=clipped;
            PortraitDepthBuffer raster=new PortraitDepthBuffer();raster.begin(SIZE,SIZE);Arrays.fill(raster.getPixels(),0,SIZE*SIZE,BACKGROUND);
            raster.draw(projected,depths,shaded,indices,1,1);
            BufferedImage image=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0,0,SIZE,SIZE,raster.getPixels(),0,SIZE);
            Path imagePath=directory.resolve(String.format(Locale.ROOT,"%02d-%s-%s.png",number,name,row==0?"front":"turned"));ImageIO.write(image,"png",imagePath.toFile());
            int x=gap+col*(tile+gap),y=header+row*(tile+label+gap);
            canvas.setColor(new Color(0xecf3fa));canvas.setFont(new Font("SansSerif",Font.BOLD,21));canvas.drawString(item.title,x,y+26);
            canvas.setColor(new Color(0xa9bfd3));canvas.setFont(new Font("SansSerif",Font.PLAIN,15));canvas.drawString(name.equals("sunglasses")?"Eyewear only":"Original face",x+tile-95,y+25);
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);canvas.drawImage(image,x,y+label,tile,tile,null);
            if(number>0)report.append(",\n");
            report.append("    {\"style\":").append(quote(name)).append(",\"yaw\":").append(yaw).append(",\"verticesAdded\":").append(verticesAdded)
                .append(",\"trianglesAdded\":").append(trianglesAdded).append(",\"clippedVertices\":").append(clipped).append(",\"image\":").append(quote(imagePath.toString())).append('}');
            System.out.println(item.title+" yaw "+yaw+": "+verticesAdded+" sunglasses vertices, "+trianglesAdded+" triangles, "+clipped+" clipped vertices");
        }
        canvas.dispose();ImageIO.write(sheet,"png",output.toFile());report.append("\n  ]\n}\n");Files.writeString(directory.resolve("report.json"),report.toString(),StandardCharsets.UTF_8);
        if(totalClipped!=0)throw new IllegalStateException("Preview framing clips "+totalClipped+" vertices");
        System.out.println("Saved "+output);
    }
}

