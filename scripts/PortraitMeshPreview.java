import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exports the actual shared JVM portrait mesh; no Android framework or textures are used. */
public final class PortraitMeshPreview {
    private static final String MODELS = "com.oculus.camerademo.model3d.";
    private static final String EMOTIONS = "com.oculus.camerademo.ml.FaceEmotionState";

    private record Case(String title, String subtitle, String style, float yaw,
                        boolean hair, Map<String, Float> expression, float gazeX, float gazeY) {
        Case(String title, String subtitle, String style, float yaw,
             boolean hair, Map<String, Float> expression) {
            this(title, subtitle, style, yaw, hair, expression, 0, 0);
        }
    }

    private static Map<String, Float> expression(Object... pairs) {
        Map<String, Float> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], ((Number) pairs[i + 1]).floatValue());
        }
        return result;
    }

    private static int color(String rgb) { return (int) (0xff000000L | Long.parseLong(rgb, 16)); }

    private static Object style(Class<?> type, String name) throws ReflectiveOperationException {
        String skin = "E1AF91", hair = "1C1717", lips = "B45D72";
        boolean beard = name.equals("beard"), metallic = name.equals("gold");
        String character = List.of("lion", "dragon", "unicorn").contains(name) ? name : "human";
        String variant = name.equals("natural") ? "classic" : name.equals("beard") ? "bearded" : name;
        if (beard) { skin = "DAA884"; hair = "34241D"; lips = "A56661"; }
        if (metallic) { skin = "DCB432"; hair = "8F6923"; lips = "B98831"; }
        if (name.equals("lion")) { skin = "FFB300"; hair = "683D21"; lips = "82462F"; }
        if (name.equals("dragon")) { skin = "4CAF50"; hair = "234E32"; lips = "336A3E"; }
        if (name.equals("unicorn")) { skin = "FFFFFF"; hair = "7A4C8E"; lips = "B97091"; }
        if (name.equals("cyber")) { skin = "3D5B77"; hair = "0E1A27"; lips = "379FB3"; }
        if (name.equals("holo")) { skin = "00FFB4"; hair = "076B5B"; lips = "49D3AE"; }
        try {
            return type.getConstructor(int.class, int.class, int.class, boolean.class, boolean.class, String.class, String.class)
                    .newInstance(color(skin), color(hair), color(lips), beard, metallic, character, variant);
        } catch (NoSuchMethodException previousStyle) {
            // Compatibility permits faithful rendering of the preserved v1 source.
        }
        try {
            return type.getConstructor(int.class, int.class, int.class, boolean.class, boolean.class, String.class)
                    .newInstance(color(skin), color(hair), color(lips), beard, metallic, character);
        } catch (NoSuchMethodException noCharacterStyle) {
            if (List.of("lion", "dragon", "unicorn").contains(name)) throw noCharacterStyle;
            return type.getConstructor(int.class, int.class, int.class, boolean.class, boolean.class)
                    .newInstance(color(skin), color(hair), color(lips), beard, metallic);
        }
    }

    private static Object emotion(Class<?> type, Map<String, Float> weights) throws ReflectiveOperationException {
        Object result = type.getConstructor().newInstance();
        // Fresh independent state per case. Write the backing values so this harness stays
        // independent of Kotlin's synthetic default constructor and parameter masks.
        for (Map.Entry<String, Float> entry : weights.entrySet()) {
            Field field = type.getDeclaredField(entry.getKey());
            if (field.getType() != float.class) throw new IllegalArgumentException(entry.getKey());
            field.setAccessible(true);
            field.setFloat(result, entry.getValue());
        }
        return result;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void floats(StringBuilder json, float[] values) {
        json.append('[');
        for (int i = 0; i < values.length; i++) {
            if (!Float.isFinite(values[i])) throw new IllegalArgumentException("Non-finite mesh value at " + i);
            if (i != 0) json.append(',');
            json.append(values[i]);
        }
        json.append(']');
    }

    private static void ints(StringBuilder json, int[] values) {
        json.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i != 0) json.append(',');
            json.append(values[i]);
        }
        json.append(']');
    }

    /** Export the real Kotlin lighting as well as geometry for reference renderers. */
    private static int[] shadedColors(Case item, float[] normals, int[] colors, int[] surfaces) throws Exception {
        float radians = (float) Math.toRadians(item.yaw), co = (float) Math.cos(radians), si = (float) Math.sin(radians);
        Method shade = Class.forName(MODELS + "PortraitSurface").getMethod("shade", int.class,
                float.class, float.class, float.class, int.class, boolean.class);
        int[] shaded = new int[colors.length];
        for (int i = 0; i < colors.length; i++) {
            int at = i * 3;
            float nx = co * normals[at] + si * normals[at + 2], ny = normals[at + 1];
            float nz = -si * normals[at] + co * normals[at + 2];
            if (surfaces != null) {
                shaded[i] = (Integer) shade.invoke(null, colors[i], nx, ny, nz, surfaces[i], item.style.equals("gold"));
            } else {
                // Preserved source directories use the historical light for fair comparisons.
                float light = .60f + .36f * Math.max(0, Math.min(1, -.35f * nx + .45f * ny + .82f * nz));
                float shine = (float) Math.pow(Math.max(nz, 0), 22) * (item.style.equals("gold") ? .18f : .035f);
                int r = Math.min(255, (int) (((colors[i] >>> 16) & 255) * light + 255 * shine));
                int g = Math.min(255, (int) (((colors[i] >>> 8) & 255) * light + 244 * shine));
                int b = Math.min(255, (int) ((colors[i] & 255) * light + 229 * shine));
                shaded[i] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        return shaded;
    }

    /** Use the exact pure Kotlin renderer installed in the application. */
    private static Path applicationRaster(Path directory, int caseIndex, Case item, float[] positions,
                                          int[] shaded, int[] indices) throws Exception {
        final int width = 800, height = 816;
        boolean creature = List.of("lion", "dragon", "unicorn").contains(item.style);
        float unit = Math.min(744f / (creature ? .40f : .28f), 692f / (creature ? .44f : .38f));
        float centerX = width * .5f, centerY = 44f + 692f * .5f;
        float radians = (float) Math.toRadians(item.yaw), co = (float) Math.cos(radians), si = (float) Math.sin(radians);
        float[] projected = new float[shaded.length * 2], depths = new float[shaded.length];
        for (int i = 0; i < shaded.length; i++) {
            int at = i * 3;
            projected[i * 2] = centerX + (co * positions[at] + si * positions[at + 2]) * unit;
            projected[i * 2 + 1] = centerY - positions[at + 1] * unit;
            depths[i] = -si * positions[at] + co * positions[at + 2];
        }
        Class<?> rasterType = Class.forName("com.oculus.camerademo.renderer.PortraitDepthBuffer");
        Object raster = rasterType.getConstructor().newInstance();
        rasterType.getMethod("begin", int.class, int.class).invoke(raster, width, height);
        int[] pixels = (int[]) rasterType.getMethod("getPixels").invoke(raster);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double glow = Math.max(0, 1 - Math.hypot((x - width / 2d) / width, (y - height * .45) / height) * 1.45);
            int red = (int) (17 + glow * 9), green = (int) (24 + glow * 11), blue = (int) (35 + glow * 16);
            pixels[y * width + x] = 0xff000000 | (red << 16) | (green << 8) | blue;
        }
        rasterType.getMethod("draw", float[].class, float[].class, int[].class, int[].class, float.class, float.class)
                .invoke(raster, projected, depths, shaded, indices, 1f, 1f);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        Files.createDirectories(directory);
        Path output = directory.resolve(String.format("%02d-%s.png", caseIndex, item.style));
        ImageIO.write(image, "png", output.toFile());
        return output.toAbsolutePath();
    }

    public static void main(String[] args) throws Exception {
        try {
            Class.forName(MODELS + "AuthoredPortraitStore").getMethod("installDirectory", String.class)
                    .invoke(null, "app/src/main/assets");
        } catch (ClassNotFoundException legacySource) { /* Preserved procedural sources. */ }
        Path output = Path.of(args.length > 0 ? args[0] : "scratch/portrait-mesh-preview.json");
        int detail = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        Class<?> meshType = Class.forName(MODELS + "PortraitMesh");
        Class<?> styleType = Class.forName(MODELS + "PortraitStyle");
        Class<?> emotionType = Class.forName(EMOTIONS);
        Object mesh = meshType.getConstructor().newInstance();
        Method build = meshType.getMethod("build", styleType, emotionType,
                float.class, float.class, boolean.class, int.class);
        List<Case> cases = new ArrayList<>();
        boolean quick = args.length > 2 && args[2].equals("--quick");
        boolean art = args.length > 2 && args[2].equals("--art");
        String[][] styles = {{"natural", "Rain Portrait"}, {"beard", "Snow Portrait"},
                {"gold", "Golden sculpture"}, {"cyber", "Cyber synth"}, {"holo", "Holographic"},
                {"lion", "Lion"}, {"dragon", "Dragon"}, {"unicorn", "Unicorn"}};
        for (String[] item : styles) {
            String style = item[0], title = item[1];
            if (args.length > 3 && !args[3].equals("all") && !List.of(args[3].split(",")).contains(style)) continue;
            if (art) {
                cases.add(new Case(title, "25-degree turn / soft smile", style, 25, true,
                        expression("mouthSmileLeft", .60, "mouthSmileRight", .60, "jawOpen", .18,
                                "cheekSquintLeft", .2, "cheekSquintRight", .2)));
                continue;
            }
            cases.add(new Case(title, "Neutral / front", style, 0, true, expression()));
            if (quick) continue;
            cases.add(new Case(title + " / left", "35-degree left turn", style, -35, true, expression()));
            cases.add(new Case(title + " / right", "35-degree right turn", style, 35, true, expression()));
            cases.add(new Case(title + " / profile", "75-degree profile", style, 75, true, expression()));
            cases.add(new Case(title + " / smile", "Smile + cheek lift", style, 0, true,
                    expression("mouthSmileLeft", .85, "mouthSmileRight", .85,
                            "cheekSquintLeft", .5, "cheekSquintRight", .5)));
            cases.add(new Case(title + " / speaking", "Jaw opening / 70%", style, 0, true,
                    expression("jawOpen", .7)));
            cases.add(new Case(title + " / blink", "Both eyelids closed", style, 0, true,
                    expression("eyeBlinkLeft", 1, "eyeBlinkRight", 1)));
            cases.add(new Case(title + " / wink", "Independent left eyelid", style, 0, true,
                    expression("eyeBlinkLeft", 1, "mouthSmileLeft", .5, "mouthSmileRight", .35)));
            cases.add(new Case(title + " / gaze", "Independent gaze / upper right", style, 0, true,
                    expression(), .8f, .55f));
            if (List.of("natural", "beard").contains(style)) {
                cases.add(new Case(title + " / pucker", "Pucker + funnel stress test", style, 0, true,
                        expression("mouthPucker", 1, "mouthFunnel", .7)));
                cases.add(new Case(title + " / surprise", "Full jaw + raised brow + wide eyes", style, 0, true,
                        expression("jawOpen", 1, "browInnerUp", 1, "browOuterUpLeft", 1,
                                "browOuterUpRight", 1, "eyeWideLeft", 1, "eyeWideRight", 1)));
                cases.add(new Case(title + " / frown", "Downturned mouth + inner brow", style, 0, true,
                        expression("mouthFrownLeft", 1, "mouthFrownRight", 1, "browInnerUp", .65)));
                cases.add(new Case(title + " / focus", "Brow + squint stress test", style, 0, true,
                        expression("browDownLeft", 1, "browDownRight", 1, "eyeSquintLeft", .75, "eyeSquintRight", .75)));
            }
        }
        if (cases.isEmpty()) throw new IllegalArgumentException("No preview styles matched. Use comma-separated natural,beard,gold,cyber,holo,lion,dragon,unicorn.");
        StringBuilder json = new StringBuilder(10_000_000);
        json.append("{\"source\":\"PortraitMesh.build\",\"detail\":").append(detail).append(",\"cases\":[");
        for (int c = 0; c < cases.size(); c++) {
            Case item = cases.get(c);
            Object geometry = build.invoke(mesh, style(styleType, item.style),
                    emotion(emotionType, item.expression), item.gazeX, item.gazeY, item.hair, detail);
            Class<?> geometryType = geometry.getClass();
            float[] positions = (float[]) geometryType.getMethod("getPositions").invoke(geometry);
            float[] normals = (float[]) geometryType.getMethod("getNormals").invoke(geometry);
            int[] colors = (int[]) geometryType.getMethod("getColors").invoke(geometry);
            int[] indices = (int[]) geometryType.getMethod("getIndices").invoke(geometry);
            int[] surfaces = null;
            try { surfaces = (int[]) geometryType.getMethod("getSurfaces").invoke(geometry); }
            catch (NoSuchMethodException legacyMesh) { /* Render preserved meshes with their original light. */ }
            int[] shaded = shadedColors(item, normals, colors, surfaces);
            if (positions.length % 3 != 0 || positions.length != normals.length ||
                    positions.length / 3 != colors.length || indices.length % 3 != 0) {
                throw new IllegalArgumentException("Invalid geometry buffers: " + item.title);
            }
            for (int index : indices) if (index < 0 || index >= colors.length) {
                throw new IllegalArgumentException("Index outside vertex buffer: " + item.title);
            }
            if (c != 0) json.append(',');
            json.append("{\"title\":").append(quote(item.title)).append(",\"subtitle\":").append(quote(item.subtitle))
                    .append(",\"yaw\":").append(item.yaw).append(",\"metallic\":").append(item.style.equals("gold"))
                    .append(",\"character\":").append(quote(List.of("lion", "dragon", "unicorn").contains(item.style) ? item.style : "human"))
                    .append(",\"positions\":");
            floats(json, positions);
            json.append(",\"normals\":"); floats(json, normals);
            json.append(",\"colors\":"); ints(json, colors);
            json.append(",\"shadedColors\":"); ints(json, shaded);
            json.append(",\"indices\":"); ints(json, indices);
            if (args.length > 4 && !args[4].isEmpty()) {
                Path raster = applicationRaster(Path.of(args[4]), c, item, positions, shaded, indices);
                json.append(",\"applicationRaster\":").append(quote(raster.toString()));
            }
            json.append('}');
            System.out.printf("%s: %,d vertices, %,d triangles%n", item.title, colors.length, indices.length / 3);
        }
        json.append("]}\n");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, json, StandardCharsets.UTF_8);
        System.out.println("Mesh JSON: " + output.toAbsolutePath());
    }
}

