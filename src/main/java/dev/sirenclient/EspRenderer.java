package dev.sirenclient;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.sirenclient.modules.BlockEsp;
import dev.sirenclient.modules.ChunkAge;
import dev.sirenclient.modules.Hit;
import dev.sirenclient.modules.ModuleManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Through-walls ESP using a custom no-depth-test pipeline (follows Fabric's 1.21.11 rendering docs). */
public final class EspRenderer {
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("sirenclient", "pipeline/esp_through_walls"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .build());

    private static final ByteBufferBuilder allocator = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static MappableRingBuffer vertexBuffer;

    private static final int MAX_BOXES = 900;

    private record Box(double x1, double y1, double z1, double x2, double y2, double z2,
                       float r, float g, float b, float fillA, float edgeA, boolean frame) {}

    private EspRenderer() {}

    public static void init() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(EspRenderer::onRender);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> close());
    }

    // ---- extraction: decide what to draw ----
    private static List<Box> collect() {
        List<Box> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return out;
        ModuleManager m = SirenClient.modules;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        double max2 = (double) Config.espDist * Config.espDist;
        float fill = Config.espAlpha / 100f * 0.35f;

        if (m.base.isEnabled()) for (Hit h : m.base.values()) addHit(out, h, 0x2EFF8A, fill, px, pz, max2);
        if (m.sus.isEnabled()) for (Hit h : m.sus.values()) addHit(out, h, 0xFF8C1A, fill, px, pz, max2);

        if (m.newChunks.isEnabled()) {
            double y = py - 1.5;
            for (var e : ChunkAge.all().entrySet()) {
                if (out.size() >= MAX_BOXES) break;
                long k = e.getKey();
                int cx = (int) (k >> 32), cz = (int) k;
                double dx = cx * 16 + 8 - px, dz = cz * 16 + 8 - pz;
                if (dx * dx + dz * dz > max2) continue;
                out.add(box(cx * 16, y, cz * 16, cx * 16 + 16, y + 0.1, cz * 16 + 16,
                        e.getValue() ? 0xFF3B3B : 0x4D7CFF, fill * 0.6f, 0.5f, true));
            }
        }

        if (m.blockEsp.isEnabled()) {
            for (BlockEsp.Found f : m.blockEsp.values()) {
                for (int i = 0; i < f.size() && out.size() < MAX_BOXES; i++) {
                    int x = f.xyz()[i * 3], y = f.xyz()[i * 3 + 1], z = f.xyz()[i * 3 + 2];
                    double dx = x + 0.5 - px, dz = z + 0.5 - pz;
                    if (dx * dx + dz * dz > max2) continue;
                    out.add(box(x, y, z, x + 1, y + 1, z + 1, f.rgb()[i], fill, 0.95f, true));
                }
            }
        }

        if (m.playerEsp.isEnabled()) {
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            for (AbstractClientPlayer p : mc.level.players()) {
                if (p == mc.player || out.size() >= MAX_BOXES) continue;
                double x = Mth.lerp(pt, p.xo, p.getX()), y = Mth.lerp(pt, p.yo, p.getY()), z = Mth.lerp(pt, p.zo, p.getZ());
                double dx = x - px, dz = z - pz;
                if (dx * dx + dz * dz > max2) continue;
                double hw = p.getBbWidth() / 2.0;
                out.add(box(x - hw, y, z - hw, x + hw, y + p.getBbHeight(), z + hw, 0xFF3B3B, fill + 0.1f, 1f, true));
            }
        }

        // Waypoints are always drawn, at any distance.
        for (Config.Waypoint w : Config.waypoints) {
            out.add(box(w.x(), w.y(), w.z(), w.x() + 1, w.y() + 1, w.z() + 1, 0x3BE8FF, fill + 0.15f, 1f, true));
            out.add(box(w.x() + 0.4, w.y() + 1, w.z() + 0.4, w.x() + 0.6, w.y() + 64, w.z() + 0.6, 0x3BE8FF, fill, 0f, false));
        }
        return out;
    }

    private static void addHit(List<Box> out, Hit h, int rgb, float fill, double px, double pz, double max2) {
        if (out.size() >= MAX_BOXES - 3) return;
        double dx = h.x() + 0.5 - px, dz = h.z() + 0.5 - pz;
        if (dx * dx + dz * dz > max2) return;
        out.add(box(h.x(), h.y(), h.z(), h.x() + 1, h.y() + 1, h.z() + 1, rgb, fill + 0.15f, 1f, true));
        if (Config.beams)
            out.add(box(h.x() + 0.4, h.y() + 1, h.z() + 0.4, h.x() + 0.6, h.y() + 64, h.z() + 0.6, rgb, fill, 0f, false));
        if (Config.plates) {
            int ox = (h.x() >> 4) << 4, oz = (h.z() >> 4) << 4;
            out.add(box(ox, h.y(), oz, ox + 16, h.y() + 0.1, oz + 16, rgb, fill * 0.7f, 0.6f, true));
        }
    }

    private static Box box(double x1, double y1, double z1, double x2, double y2, double z2,
                           int rgb, float fillA, float edgeA, boolean frame) {
        return new Box(x1, y1, z1, x2, y2, z2,
                ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, fillA, edgeA, frame);
    }

    // ---- drawing ----
    private static void onRender(WorldRenderContext context) {
        ModuleManager m = SirenClient.modules;
        if (m == null || !(m.base.isEnabled() || m.sus.isEnabled() || m.newChunks.isEnabled() || m.blockEsp.isEnabled()
                || m.playerEsp.isEnabled() || !Config.waypoints.isEmpty())) return;
        List<Box> boxes = collect();
        if (boxes.isEmpty()) return;

        PoseStack matrices = context.matrices();
        Vec3 camera = context.worldState().cameraRenderState.pos;
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        BufferBuilder buffer = new BufferBuilder(allocator, PIPELINE.getVertexFormatMode(), PIPELINE.getVertexFormat());
        Matrix4fc pose = matrices.last().pose();
        for (Box b : boxes) {
            if (b.fillA() > 0f)
                filled(pose, buffer, b.x1(), b.y1(), b.z1(), b.x2(), b.y2(), b.z2(), b.r(), b.g(), b.b(), b.fillA());
            if (b.frame()) frame(pose, buffer, b);
        }
        matrices.popPose();

        MeshData built = buffer.build();
        if (built == null) return;
        drawBuilt(Minecraft.getInstance(), built);
    }

    private static void frame(Matrix4fc p, BufferBuilder buf, Box b) {
        double t = 0.03;
        double[] ys = {b.y1(), b.y2() - t}, zs = {b.z1(), b.z2() - t}, xs = {b.x1(), b.x2() - t};
        for (double y : ys) for (double z : zs)
            filled(p, buf, b.x1(), y, z, b.x2(), y + t, z + t, b.r(), b.g(), b.b(), b.edgeA());
        for (double x : xs) for (double z : zs)
            filled(p, buf, x, b.y1(), z, x + t, b.y2(), z + t, b.r(), b.g(), b.b(), b.edgeA());
        for (double x : xs) for (double y : ys)
            filled(p, buf, x, y, b.z1(), x + t, y + t, b.z2(), b.r(), b.g(), b.b(), b.edgeA());
    }

    private static void filled(Matrix4fc m, BufferBuilder buf, double dMinX, double dMinY, double dMinZ,
                               double dMaxX, double dMaxY, double dMaxZ, float r, float g, float b, float a) {
        float minX = (float) dMinX, minY = (float) dMinY, minZ = (float) dMinZ;
        float maxX = (float) dMaxX, maxY = (float) dMaxY, maxZ = (float) dMaxZ;
        // front
        buf.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
        // back
        buf.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
        // left
        buf.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
        // right
        buf.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
        // top
        buf.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
        // bottom
        buf.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
    }

    private static void drawBuilt(Minecraft client, MeshData builtBuffer) {
        MeshData.DrawState drawParameters = builtBuffer.drawState();
        VertexFormat format = drawParameters.format();

        GpuBuffer vertices = upload(drawParameters, format, builtBuffer);
        draw(client, PIPELINE, builtBuffer, drawParameters, vertices, format);

        vertexBuffer.rotate();
    }

    private static GpuBuffer upload(MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = new MappableRingBuffer(() -> "sirenclient esp", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBufferSize);
        }

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
                vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
        }
        return vertexBuffer.currentBuffer();
    }

    private static void draw(Minecraft client, RenderPipeline pipeline, MeshData builtBuffer,
                             MeshData.DrawState drawParameters, GpuBuffer vertices, VertexFormat format) {
        GpuBuffer indices;
        VertexFormat.IndexType indexType;

        if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            builtBuffer.sortQuads(allocator, RenderSystem.getProjectionType().vertexSorting());
            indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
            indexType = builtBuffer.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
            indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
            indexType = shapeIndexBuffer.type();
        }

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "sirenclient esp rendering",
                        client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                        client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(0 / format.getVertexSize(), 0, drawParameters.indexCount(), 1);
        }
        builtBuffer.close();
    }

    public static void close() {
        allocator.close();
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
